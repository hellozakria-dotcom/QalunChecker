"""Python/transformers reference transcription of every chunk (for comparison with the Java engine)."""
import sys, numpy as np, torch
from transformers import WhisperProcessor, WhisperForConditionalGeneration
model_id, listing, out = sys.argv[1], sys.argv[2], sys.argv[3]
proc = WhisperProcessor.from_pretrained(model_id)
model = WhisperForConditionalGeneration.from_pretrained(model_id).eval()
with open(out, "w", encoding="utf-8") as f:
    for line in open(listing):
        sura, path = line.rstrip("\n").split("\t")
        a = np.fromfile(path, dtype="<f4")
        feats = proc(a, sampling_rate=16000, return_tensors="pt").input_features
        with torch.no_grad():
            ids = model.generate(feats, language="ar", task="transcribe", num_beams=1, do_sample=False, max_new_tokens=200)
        text = proc.batch_decode(ids, skip_special_tokens=True)[0].strip()
        f.write(f"{path}\t{text}\n")
        print(path, text)
