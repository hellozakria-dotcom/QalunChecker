"""
Letter-level Qalun recitation model  (Step 3 of the plan)
=========================================================

Fine-tunes a multilingual wav2vec2 model with CTC so it writes out, letter by
letter and WITH harakat, what a reciter actually said in the Qalun riwayah.
Comparing that output with the mushaf text (the app already does this
alignment) is what makes single-letter / single-haraka mistakes detectable,
including ones Google's recogniser "auto-corrects" today.

Run it on a GPU (Google Colab / Kaggle, free tier is enough for a first model):

    !pip install -q "transformers>=4.40" datasets accelerate librosa jiwer soundfile
    !python train_qalun_ctc.py --audio_dirs /content/reciters/* --tsv qalun.tsv --out qalun-ctc

Audio layout expected (the common everyayah.com naming):
    reciters/<reciter_name>/SSSAAA.mp3      e.g. 001001.mp3 = Fatiha, ayah 1
One file per ayah, Qalun riwayah, Qalun ayah numbering (same as qalun.tsv).
For mistake detection to work well you ALSO need recordings of ordinary
people (with their mistakes transcribed exactly as said) in the same layout
plus a `transcript.tsv` (SSSAAA <tab> what was actually said) in that folder;
when present it overrides the mushaf text for those files.

NOTE: this script has not been run yet (no GPU / model downloads were
available where it was written). Expect to fix small things on the first run.
"""
import argparse
import glob
import os
import random
import re
import unicodedata

import numpy as np

TANWEEN_VARIANTS = {"\u0657": "\u064B", "\u0656": "\u064D", "\u065E": "\u064C"}
KEEP_MARKS = set("ًٌٍَُِّْ")  # tanween, harakat, shadda, sukun


def to_target(text: str) -> str:
    """Mushaf text -> training target: letters + harakat, no waqf/annotation marks."""
    text = text.replace("ے", "ي").replace("ٱ", "ا").replace(" ", " ")
    out = []
    for ch in text:
        cat = unicodedata.category(ch)
        if ch == " ":
            out.append("|")  # word separator for CTC
        elif "ء" <= ch <= "ي" and ch != "ـ":
            out.append(ch)
        elif ch in KEEP_MARKS:
            out.append(ch)
        elif ch in TANWEEN_VARIANTS:  # KFGQPC tanween forms (idgham/ikhfa) -> plain tanween
            out.append(TANWEEN_VARIANTS[ch])
        elif ch == "ٰ":  # dagger alif is a pronounced long vowel
            out.append("ا")
        # everything else (waqf signs, small marks, digits) is dropped
    s = re.sub(r"\|+", "|", "".join(out)).strip("|")
    return s


def load_texts(tsv):
    texts = {}
    with open(tsv, encoding="utf-8") as f:
        for line in f:
            s, a, _page, t = line.rstrip("\n").split("\t", 3)
            texts[f"{int(s):03d}{int(a):03d}"] = to_target(t)
    return texts


def collect(audio_dirs, texts):
    items = []
    for d in audio_dirs:
        override = {}
        tp = os.path.join(d, "transcript.tsv")
        if os.path.exists(tp):
            with open(tp, encoding="utf-8") as f:
                for line in f:
                    k, t = line.rstrip("\n").split("\t", 1)
                    override[k] = to_target(t)
        for p in glob.glob(os.path.join(d, "*.mp3")) + glob.glob(os.path.join(d, "*.wav")):
            key = os.path.splitext(os.path.basename(p))[0][:6]
            tgt = override.get(key, texts.get(key))
            if tgt:
                items.append({"path": p, "text": tgt, "speaker": os.path.basename(d.rstrip("/"))})
    return items


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--audio_dirs", nargs="+", required=True)
    ap.add_argument("--tsv", default="qalun.tsv")
    ap.add_argument("--base", default="facebook/wav2vec2-xls-r-300m")
    ap.add_argument("--out", default="qalun-ctc")
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--max_seconds", type=float, default=20.0, help="skip longer ayat (memory)")
    args = ap.parse_args()

    import librosa
    import torch
    from datasets import Dataset
    from transformers import (Trainer, TrainingArguments, Wav2Vec2CTCTokenizer,
                              Wav2Vec2FeatureExtractor, Wav2Vec2ForCTC, Wav2Vec2Processor)
    import jiwer

    texts = load_texts(args.tsv)
    items = collect(args.audio_dirs, texts)
    if not items:
        raise SystemExit("No audio matched. Check file names (SSSAAA.mp3) and --tsv.")
    random.seed(0)
    # hold out whole speakers when possible, so the score reflects new voices
    speakers = sorted({i["speaker"] for i in items})
    test_spk = set(speakers[-1:]) if len(speakers) > 2 else set()
    train = [i for i in items if i["speaker"] not in test_spk]
    test = [i for i in items if i["speaker"] in test_spk] or random.sample(items, max(1, len(items) // 20))
    print(f"{len(items)} files, {len(speakers)} speakers; train={len(train)} test={len(test)}")

    vocab = sorted({c for i in items for c in i["text"]})
    vocab_dict = {c: n for n, c in enumerate(vocab)}
    vocab_dict["[UNK]"] = len(vocab_dict)
    vocab_dict["[PAD]"] = len(vocab_dict)
    os.makedirs(args.out, exist_ok=True)
    import json
    with open(os.path.join(args.out, "vocab.json"), "w", encoding="utf-8") as f:
        json.dump(vocab_dict, f, ensure_ascii=False)

    tok = Wav2Vec2CTCTokenizer(os.path.join(args.out, "vocab.json"), unk_token="[UNK]", pad_token="[PAD]", word_delimiter_token="|")
    fe = Wav2Vec2FeatureExtractor(feature_size=1, sampling_rate=16000, padding_value=0.0, do_normalize=True, return_attention_mask=True)
    proc = Wav2Vec2Processor(feature_extractor=fe, tokenizer=tok)

    def prep(batch):
        audio, _ = librosa.load(batch["path"], sr=16000, mono=True)
        batch["input_values"] = proc(audio, sampling_rate=16000).input_values[0]
        batch["input_length"] = len(audio) / 16000
        batch["labels"] = proc.tokenizer(batch["text"]).input_ids
        return batch

    ds_train = Dataset.from_list(train).map(prep, remove_columns=["path", "speaker"])
    ds_test = Dataset.from_list(test).map(prep, remove_columns=["path", "speaker"])
    ds_train = ds_train.filter(lambda x: x["input_length"] <= args.max_seconds)
    ds_test = ds_test.filter(lambda x: x["input_length"] <= args.max_seconds)

    def collate(features):
        inputs = [{"input_values": f["input_values"]} for f in features]
        labels = [{"input_ids": f["labels"]} for f in features]
        batch = proc.feature_extractor.pad(inputs, padding=True, return_tensors="pt")
        lab = proc.tokenizer.pad(labels, padding=True, return_tensors="pt")
        batch["labels"] = lab["input_ids"].masked_fill(lab.attention_mask.ne(1), -100)
        return batch

    model = Wav2Vec2ForCTC.from_pretrained(
        args.base, ctc_loss_reduction="mean", pad_token_id=proc.tokenizer.pad_token_id,
        vocab_size=len(proc.tokenizer), mask_time_prob=0.05, layerdrop=0.05)
    model.freeze_feature_encoder()

    def metrics(pred):
        ids = np.argmax(pred.predictions, axis=-1)
        pred.label_ids[pred.label_ids == -100] = proc.tokenizer.pad_token_id
        hyp = proc.batch_decode(ids)
        ref = proc.batch_decode(pred.label_ids, group_tokens=False)
        return {"cer": jiwer.cer(ref, hyp)}  # letter (+haraka) error rate

    targs = TrainingArguments(
        output_dir=args.out, per_device_train_batch_size=8, gradient_accumulation_steps=2,
        eval_strategy="epoch", save_strategy="epoch", num_train_epochs=args.epochs,
        fp16=torch.cuda.is_available(), learning_rate=3e-4, warmup_steps=500,
        save_total_limit=2, load_best_model_at_end=True, metric_for_best_model="cer",
        greater_is_better=False, group_by_length=True, length_column_name="input_length",
        logging_steps=50, report_to="none")
    trainer = Trainer(model=model, args=targs, data_collator=collate, compute_metrics=metrics,
                      train_dataset=ds_train, eval_dataset=ds_test, tokenizer=proc.feature_extractor)
    trainer.train()
    trainer.save_model(args.out)
    proc.save_pretrained(args.out)
    print("Final:", trainer.evaluate())


if __name__ == "__main__":
    main()
