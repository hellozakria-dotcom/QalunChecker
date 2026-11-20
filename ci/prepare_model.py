"""Download the Quran Whisper model, fill in standard processor files, export custom ONNX graphs."""
import os, sys, shutil, subprocess
from huggingface_hub import snapshot_download

model_id, out = sys.argv[1], sys.argv[2]
local = snapshot_download(model_id, local_dir=out + "_src", allow_patterns=["*.json", "*.txt", "*.safetensors", "*.bin", "*.model"])
base = snapshot_download("openai/whisper-tiny", local_dir=out + "_base", allow_patterns=["*.json", "*.txt"])
for f in os.listdir(base):
    if f not in os.listdir(local):
        shutil.copy(os.path.join(base, f), os.path.join(local, f)); print("added from openai/whisper-tiny:", f)
subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "export_custom.py"), local, os.path.join(out, "fp32")], check=True)
