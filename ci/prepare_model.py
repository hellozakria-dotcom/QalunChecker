"""Export Tarteel's Quran Whisper model to ONNX (fp32 + int8) plus the small files the app needs."""
import os, sys, shutil, numpy as np
from transformers import WhisperTokenizer, WhisperFeatureExtractor, WhisperConfig, GenerationConfig
from optimum.exporters.onnx import main_export
from onnxruntime.quantization import quantize_dynamic, QuantType

model_id, out = sys.argv[1], sys.argv[2]
tmp = out + "_export"
main_export(model_id, output=tmp, task="automatic-speech-recognition", no_post_process=True)
print("exported:", os.listdir(tmp))
for variant in ("fp32", "int8"):
    d = os.path.join(out, variant); os.makedirs(d, exist_ok=True)
    for part in ("encoder", "decoder"):
        src = os.path.join(tmp, f"{part}_model.onnx")
        dst = os.path.join(d, f"{part}.onnx")
        if variant == "fp32":
            shutil.copy(src, dst)
            if os.path.exists(src + "_data"): shutil.copy(src + "_data", dst + "_data")
        else:
            quantize_dynamic(src, dst, weight_type=QuantType.QInt8)

tok = WhisperTokenizer.from_pretrained(model_id)
vocab = tok.get_vocab()
inv = {i: t for t, i in vocab.items()}
cfg = WhisperConfig.from_pretrained(model_id)
fe = WhisperFeatureExtractor.from_pretrained(model_id)
ids = lambda t: tok.convert_tokens_to_ids(t)
props = {"sot": ids("<|startoftranscript|>"), "lang_ar": ids("<|ar|>"), "transcribe": ids("<|transcribe|>"),
         "notimestamps": ids("<|notimestamps|>"), "eot": ids("<|endoftext|>"), "vocab_size": cfg.vocab_size}
try:
    gc = GenerationConfig.from_pretrained(model_id)
    props["suppress"] = ",".join(str(x) for x in (gc.suppress_tokens or []))
    props["begin_suppress"] = ",".join(str(x) for x in (gc.begin_suppress_tokens or []))
except Exception as e:
    print("no generation config:", e)
print(props)
for variant in ("fp32", "int8"):
    d = os.path.join(out, variant)
    with open(os.path.join(d, "tokens.txt"), "w", encoding="utf-8") as f:
        for i in range(max(inv) + 1):
            f.write(inv.get(i, "").replace("\n", " ") + "\n")
    with open(os.path.join(d, "whisper.properties"), "w") as f:
        for k, v in props.items(): f.write(f"{k}={v}\n")
    np.asarray(fe.mel_filters, dtype="<f4").T.tofile(os.path.join(d, "mel_filters.bin"))
    print(variant, {n: os.path.getsize(os.path.join(d, n)) for n in os.listdir(d)})
