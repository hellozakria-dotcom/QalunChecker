"""Download a few Qalun surahs (Sheikh Ahmad Attarabolsi, archive.org) and cut them into <=25 s chunks at pauses."""
import json, os, re, subprocess, sys, urllib.request, numpy as np

out = sys.argv[1]; os.makedirs(out, exist_ok=True)
item = "Al-Quran_Qaloon_Ahmad_Attarabolsi"
meta = json.load(urllib.request.urlopen(f"https://archive.org/metadata/{item}"))
mp3s = sorted(f["name"] for f in meta["files"] if f["name"].lower().endswith(".mp3"))
print("::notice title=archive files::" + " | ".join(mp3s[:6]) + f" ... ({len(mp3s)} mp3)")

def find(sura):
    for n in mp3s:
        base = os.path.basename(n)
        m = re.match(r"^\D*0*(\d{1,3})\D", base)
        if m and int(m.group(1)) == sura: return n
    return None

def chunks(audio, sr=16000, maxlen=24.0, pause=0.6):
    """Cut at every pause of >= 0.6 s, like the app does (pieces of 1-24 s)."""
    fl = int(0.1 * sr)
    n = len(audio) // fl
    rms = np.sqrt((audio[: n * fl].reshape(n, fl) ** 2).mean(axis=1))
    thr = max(0.008, 0.12 * np.percentile(rms, 90))
    res, start, quiet_run = [], 0, 0
    for k in range(n):
        quiet_run = quiet_run + 1 if rms[k] < thr else 0
        end = (k + 1) * fl
        seg = (end - start) / sr
        if (quiet_run * 0.1 >= pause and seg > 1.5) or seg >= maxlen:
            if np.max(rms[start // fl: k + 1]) > thr: res.append(audio[start:end])
            start, quiet_run = end, 0
    if len(audio) - start > sr // 2 and np.max(rms[start // fl:]) > thr: res.append(audio[start:])
    return res

listing = []
for sura in (1, 112, 113, 114, 108):
    name = find(sura)
    if not name: print(f"::warning::surah {sura} not found"); continue
    url = f"https://archive.org/download/{item}/" + urllib.request.quote(name)
    mp3 = os.path.join(out, f"{sura}.mp3")
    urllib.request.urlretrieve(url, mp3)
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", mp3, "-f", "f32le", "-ac", "1", "-ar", "16000", "-"],
                         capture_output=True, check=True).stdout
    audio = np.frombuffer(raw, dtype="<f4").copy()
    for i, c in enumerate(chunks(audio)):
        p = os.path.join(out, f"{sura:03d}_{i:02d}.f32"); c.astype("<f4").tofile(p)
        listing.append(f"{sura}\t{p}")
    print(f"surah {sura}: {name} {len(audio)/16000:.1f}s")
open(os.path.join(out, "list.tsv"), "w").write("\n".join(listing) + "\n")
