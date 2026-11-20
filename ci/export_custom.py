"""
Export Tarteel's Quran Whisper as two small ONNX graphs built for fast on-phone decoding:

  encoder.onnx : input_features [1,80,3000] -> cross_k, cross_v [L,1,H,1500,D]
                 (the decoder's cross-attention keys/values, computed once per audio piece)
  decoder.onnx : input_ids [1,T], offset [1], past_k/past_v [L,1,H,P,D], cross_k, cross_v
                 -> logits [1,V] (last token only), new_k/new_v [L,1,H,P+T,D]

The decoder reads only the new token(s) plus its memory (KV cache), so each word costs the
same instead of re-reading the whole sentence. Checked numerically against transformers.
"""
import os, sys, json, numpy as np, torch, torch.nn.functional as F
from transformers import WhisperForConditionalGeneration, WhisperTokenizer, WhisperFeatureExtractor, GenerationConfig

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
model = WhisperForConditionalGeneration.from_pretrained(src, torch_dtype=torch.float32).eval()
for _p in model.parameters(): _p.requires_grad_(False)  # weights are constants in the exported graphs
cfg = model.config
L, H, Dm = cfg.decoder_layers, cfg.decoder_attention_heads, cfg.d_model
D = Dm // H
dec = model.model.decoder


class Enc(torch.nn.Module):
    def forward(self, feats):
        h = model.model.encoder(feats).last_hidden_state            # [1, S, Dm]
        ks, vs = [], []
        for layer in dec.layers:
            a = layer.encoder_attn
            ks.append(a.k_proj(h).view(1, -1, H, D).transpose(1, 2))  # [1, H, S, D]
            vs.append(a.v_proj(h).view(1, -1, H, D).transpose(1, 2))
        return torch.stack(ks), torch.stack(vs)


class Dec(torch.nn.Module):
    def forward(self, ids, offset, past_k, past_v, cross_k, cross_v):
        T = ids.shape[1]
        P = past_k.shape[3]
        pos = offset + torch.arange(T, dtype=torch.long)
        x = dec.embed_tokens(ids) + dec.embed_positions.weight.index_select(0, pos).unsqueeze(0)
        i = torch.arange(T).unsqueeze(1)
        j = torch.arange(P + T).unsqueeze(0)
        mask = torch.where(j > i + P, torch.tensor(float("-inf")), torch.tensor(0.0))  # [T, P+T]
        nk, nv = [], []
        for li, layer in enumerate(dec.layers):
            a = layer.self_attn
            h = layer.self_attn_layer_norm(x)
            q = (a.q_proj(h) * a.scaling).view(1, T, H, D).transpose(1, 2)
            k = a.k_proj(h).view(1, T, H, D).transpose(1, 2)
            v = a.v_proj(h).view(1, T, H, D).transpose(1, 2)
            k = torch.cat([past_k[li], k], dim=2)
            v = torch.cat([past_v[li], v], dim=2)
            nk.append(k); nv.append(v)
            w = torch.softmax(q @ k.transpose(2, 3) + mask, dim=-1)
            x = x + a.out_proj((w @ v).transpose(1, 2).reshape(1, T, Dm))

            c = layer.encoder_attn
            h = layer.encoder_attn_layer_norm(x)
            q = (c.q_proj(h) * c.scaling).view(1, T, H, D).transpose(1, 2)
            w = torch.softmax(q @ cross_k[li].transpose(2, 3), dim=-1)
            x = x + c.out_proj((w @ cross_v[li]).transpose(1, 2).reshape(1, T, Dm))

            h = layer.final_layer_norm(x)
            x = x + layer.fc2(layer.activation_fn(layer.fc1(h)))
        x = dec.layer_norm(x[:, -1:, :])
        logits = F.linear(x, dec.embed_tokens.weight)[:, 0, :]       # tied output projection
        return logits, torch.stack(nk), torch.stack(nv)


fe = WhisperFeatureExtractor.from_pretrained(src)
rng = np.random.default_rng(0)
audio = (0.1 * rng.standard_normal(16000 * 5)).astype(np.float32)
feats = fe(audio, sampling_rate=16000, return_tensors="pt").input_features
enc, dmod = Enc().eval(), Dec().eval()

with torch.no_grad():
    ck, cv = enc(feats)
    ids = torch.tensor([[50258, 50272, 50359, 50363]])
    pk = torch.zeros(L, 1, H, 0, D); pv = torch.zeros(L, 1, H, 0, D)
    logits, nk, nv = dmod(ids, torch.tensor([0]), pk, pv, ck, cv)
    # reference: transformers forward on the same 4 + 1 tokens
    ref = model(input_features=feats, decoder_input_ids=ids).logits[:, -1, :]
    nxt = torch.tensor([[int(logits.argmax())]])
    logits2, _, _ = dmod(nxt, torch.tensor([4]), nk, nv, ck, cv)
    ref2 = model(input_features=feats, decoder_input_ids=torch.cat([ids, nxt], 1)).logits[:, -1, :]
    e1 = float((logits - ref).abs().max()); e2 = float((logits2 - ref2).abs().max())
print(f"::notice title=custom export check::max logit diff vs transformers: step1 {e1:.2e}, step2 (with cache) {e2:.2e}")
assert e1 < 1e-3 and e2 < 1e-3, "custom decoder does not match transformers"

with torch.no_grad():
    torch.onnx.export(enc, (feats,), os.path.join(out, "encoder.onnx"), opset_version=17,
                      input_names=["input_features"], output_names=["cross_k", "cross_v"])
    pk3 = torch.zeros(L, 1, H, 3, D); pv3 = torch.zeros(L, 1, H, 3, D)
    torch.onnx.export(dmod, (ids, torch.tensor([3]), pk3, pv3, ck, cv), os.path.join(out, "decoder.onnx"), opset_version=17,
                      input_names=["input_ids", "offset", "past_k", "past_v", "cross_k", "cross_v"],
                      output_names=["logits", "new_k", "new_v"],
                      dynamic_axes={"input_ids": {1: "T"}, "past_k": {3: "P"}, "past_v": {3: "P"},
                                    "new_k": {3: "PT"}, "new_v": {3: "PT"}})

# ONNX Runtime check, including the empty memory on the first step
import onnxruntime as ort
e = ort.InferenceSession(os.path.join(out, "encoder.onnx"))
d = ort.InferenceSession(os.path.join(out, "decoder.onnx"))
ok, okv = e.run(None, {"input_features": feats.numpy()})
lg, k1, v1 = d.run(None, {"input_ids": ids.numpy(), "offset": np.array([0]), "past_k": pk.numpy(), "past_v": pv.numpy(), "cross_k": ok, "cross_v": okv})
print(f"::notice title=onnxruntime check::max diff {float(np.abs(lg - ref.numpy()).max()):.2e}")

tok = WhisperTokenizer.from_pretrained(src)
vocab = tok.get_vocab(); inv = {i: t for t, i in vocab.items()}
with open(os.path.join(out, "tokens.txt"), "w", encoding="utf-8") as f:
    for i in range(max(inv) + 1): f.write(inv.get(i, "").replace("\n", " ") + "\n")
cid = tok.convert_tokens_to_ids
props = {"sot": cid("<|startoftranscript|>"), "lang_ar": cid("<|ar|>"), "transcribe": cid("<|transcribe|>"),
         "notimestamps": cid("<|notimestamps|>"), "eot": cid("<|endoftext|>"), "vocab_size": cfg.vocab_size,
         "layers": L, "heads": H, "head_dim": D, "format": "kv1"}
try:
    gc = GenerationConfig.from_pretrained(src)
    props["suppress"] = ",".join(str(x) for x in (gc.suppress_tokens or []))
    props["begin_suppress"] = ",".join(str(x) for x in (gc.begin_suppress_tokens or []))
except Exception as ex:
    print("no generation config:", ex)
with open(os.path.join(out, "whisper.properties"), "w") as f:
    for k, v in props.items(): f.write(f"{k}={v}\n")
np.asarray(fe.mel_filters, dtype="<f4").T.tofile(os.path.join(out, "mel_filters.bin"))
print({n: os.path.getsize(os.path.join(out, n)) for n in os.listdir(out)})
