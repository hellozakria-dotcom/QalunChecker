# مُدقّق قالون — Qalun Recitation Checker (Android)

Read the Quran in the **Qalun ʿan Nāfiʿ** riwayah while the app listens. Each word turns
green, red or orange as you go, and the phone vibrates and beeps when you make a mistake.

* **Text:** official King Fahd Complex (KFGQPC) Qalun mushaf text, v10, all 6,214 ayat
  (Madani numbering), shown in the KFGQPC Qalun font. The text is not modified.
* **Detects:** skipped words, wrong words, and letter changes the speech engine hears:
  a dropped letter (نستعن instead of نستعين), a substituted letter, an extra letter.
  Tap a red word to see the letter-by-letter difference.
* **Ignores:** repeating a word, basmala, isti'adha, صدق الله العظيم.
* **Also:** surah search, a hide-the-text mode for memorisation tests, a mistakes list,
  and "start checking from here" on any word.

## Get the APK (free, no Android Studio needed)

1. Create a free account on github.com, then create a new repository (it can be private).
2. Click **Add file → Upload files** and upload everything in this folder, including
   the hidden `.github` folder. Tip: drag the unzipped folder's *contents* in.
   If the `.github` folder didn't upload (browsers sometimes skip hidden folders), go to
   **Actions → set up a workflow yourself**, paste the contents of
   `.github/workflows/build-apk.yml`, and commit.
3. Open the **Actions** tab. The "Build APK" job runs on its own and takes about 5 minutes.
4. Open the finished run and download **QalunChecker-apk** under *Artifacts*. Unzip it, copy
   `app-debug.apk` to your phone and install it (allow "install unknown apps").

With Android Studio: *File → Open* this folder, then press **Run**.

**Phone requirements:** Android 7+, the Google app or "Speech Recognition & Synthesis
by Google" (preinstalled on most phones), and an internet connection.

## How it works

```
mic → Android speech recognizer (ar-SA) → text
    → normalise both sides (core/ArabicNormalizer)
    → align the recognised words with the mushaf words (core/RecitationTracker,
      dynamic programming: match / letter mistake / skipped / extra / repeat)
    → colour words + alert for each committed mistake (ReciteActivity)
```

The engine in `core/` is plain Java with no Android dependency, so it is unit-testable.

## Test results (run `tests/run_tests.sh`)

| Check | Result |
|---|---|
| Normaliser identical to the tuned reference on 52,182 distinct word forms | 100% |
| Correct reading written in ordinary spelling (whole Quran, 77,430 words) | 0.07% false alarms |
| Qalun text with injected mistakes, dropped letter | 552/552 caught |
| … substituted letter | 569/569 caught |
| … skipped word | 641/654 caught (the rest flag the neighbouring identical word) |
| … repeated word (must not be flagged) | 0 false alarms from repeats; 0.01% overall |

These tests simulate what the speech engine *outputs*. Real-world accuracy depends on
what the engine hears, which is the limitation below.

## The honest limitation and the next step

Google's recogniser is built for everyday Arabic. It often "auto-corrects" a
mispronounced word into the correct word, and it ignores harakat. So today the app
reliably catches **missing or wrong words and many letter errors, but not every
letter or haraka**, and it has no notion of tajwid (madd lengths, ghunna, imala…).

Catching every letter needs a model trained on Qalun recitation itself. That's
`training/train_qalun_ctc.py`: it fine-tunes wav2vec2 (XLS-R) to output Qalun text
letter by letter *with* harakat. The app's alignment engine can use its output unchanged.
To train it you need:

1. **Professional Qalun recitations split per ayah** (`001001.mp3` naming), 5–10 reciters
   or more.
2. **Ordinary people reciting, mistakes included**, with what they actually said written
   out (`transcript.tsv`), checked by someone with an ijazah in Qalun. This is what teaches
   the model to *hear* mistakes instead of correcting them.
3. A free GPU: Google Colab or Kaggle.

That script has not been run yet. Expect small fixes on the first run.

## Credits

Quran text and font © King Fahd Glorious Quran Printing Complex (qurancomplex.gov.sa),
distributed via github.com/thetruetruth/quran-data-kfgqpc. Please keep this attribution.
