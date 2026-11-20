"""Export Tarteel's Quran Whisper model to ONNX (fp32 + int8) plus the small files the app needs."""
import os, sys, shutil, numpy as np
from transformers import WhisperTokenizer, WhisperFeatureExtractor, WhisperConfig, GenerationConfig
from optimum.exporters.onnx import main_export
from onnxruntime.quantization import quantize_dynamic, QuantType

model_id, out = sys.argv[1], sys.argv[2]
tmp = out + "_export"

# Work from a local copy; fill in any processor/tokenizer files the fine-tuned repo lacks
# from the original openai/whisper-tiny (same tokenizer and audio front-end).
from huggingface_hub import snapshot_download
local = snapshot_download(model_id, local_dir=out + "_src", allow_patterns=["*.json", "*.txt", "*.safetensors", "*.bin", "*.model"])
base = snapshot_download("openai/whisper-tiny", local_dir=out + "_base", allow_patterns=["*.json", "*.txt"])
for f in os.listdir(base):
    if f not in os.listdir(local) and f != "model.safetensors":
        shutil.copy(os.path.join(base, f), os.path.join(local, f)); print("added from openai/whisper-tiny:", f)
print("model files:", sorted(os.listdir(local)))
model_id = local
main_export(model_id, output=tmp, task="automatic-speech-recognition-with-past")  # merged decoder with KV cache
print("exported:", os.listdir(tmp))
VARIANTS = ("fp32", "int8", "mixed")
for variant in ("fp32", "int8"):
    d = os.path.join(out, variant); os.makedirs(d, exist_ok=True)
    for part, srcname, dstname in (("encoder", "encoder_model.onnx", "encoder.onnx"),
                                   ("decoder", "decoder_model_merged.onnx", "decoder_merged.onnx")):
        src = os.path.join(tmp, srcname)
        dst = os.path.join(d, dstname)
        if variant == "fp32":
            shutil.copy(src, dst)
            if os.path.exists(src + "_data"): shutil.copy(src + "_data", dst + "_data")
        else:
            quantize_dynamic(src, dst, weight_type=QuantType.QUInt8, op_types_to_quantize=["MatMul", "Gather"])

# mixed: full-precision encoder, compressed decoder
os.makedirs(os.path.join(out, "mixed"), exist_ok=True)
shutil.copy(os.path.join(out, "fp32", "encoder.onnx"), os.path.join(out, "mixed", "encoder.onnx"))
shutil.copy(os.path.join(out, "int8", "decoder_merged.onnx"), os.path.join(out, "mixed", "decoder_merged.onnx"))

tok = WhisperTokenizer.from_pretrained(model_id)
vocab = tok.get_vocab()
inv = {i: t for t, i in vocab.items()}
cfg = WhisperConfig.from_pretrained(model_id)
fe = WhisperFeatureExtractor.from_pretrained(model_id)
ids = lambda t: tok.convert_tokens_to_ids(t)
props = {"sot": ids("<|startoftranscript|>"), "lang_ar": ids("<|ar|>"), "transcribe": ids("<|transcribe|>"),
         "notimestamps": ids("<|notimestamps|>"), "eot": ids("<|endoftext|>"), "vocab_size": cfg.vocab_size,
         "layers": cfg.decoder_layers, "heads": cfg.decoder_attention_heads,
         "head_dim": cfg.d_model // cfg.decoder_attention_heads}
try:
    gc = GenerationConfig.from_pretrained(model_id)
    props["suppress"] = ",".join(str(x) for x in (gc.suppress_tokens or []))
    props["begin_suppress"] = ",".join(str(x) for x in (gc.begin_suppress_tokens or []))
except Exception as e:
    print("no generation config:", e)
print(props)
for variant in VARIANTS:
    d = os.path.join(out, variant)
    with open(os.path.join(d, "tokens.txt"), "w", encoding="utf-8") as f:
        for i in range(max(inv) + 1):
            f.write(inv.get(i, "").replace("\n", " ") + "\n")
    with open(os.path.join(d, "whisper.properties"), "w") as f:
        for k, v in props.items(): f.write(f"{k}={v}\n")
    np.asarray(fe.mel_filters, dtype="<f4").T.tofile(os.path.join(d, "mel_filters.bin"))
    print(variant, {n: os.path.getsize(os.path.join(d, n)) for n in os.listdir(d)})
