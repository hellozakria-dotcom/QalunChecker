"""Builds test fixtures from the KFGQPC data repo (git clone https://github.com/thetruetruth/quran-data-kfgqpc)."""
import json, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from norm_reference import norm
src, out = sys.argv[1], sys.argv[2]
q = json.load(open(f'{src}/qaloon/data/QaloonData_v10.json', encoding='utf-8'))
h = json.load(open(f'{src}/hafs/data/hafsData_v18.json', encoding='utf-8'))
ws = set()
for a in q: ws.update(a['aya_text'].replace('\xa0', ' ').split())
for a in h: ws.update(a['aya_text'].replace('\xa0', ' ').split()); ws.update(a['aya_text_emlaey'].split())
os.makedirs(out, exist_ok=True)
with open(f'{out}/norm_expected.tsv', 'w', encoding='utf-8') as f:
    for w in sorted(ws): f.write(w + '\t' + norm(w) + '\n')
with open(f'{out}/hafs.tsv', 'w', encoding='utf-8') as f, open(f'{out}/hafs_imlaei.tsv', 'w', encoding='utf-8') as g:
    for a in h:
        f.write(f"{a['sora']}\t{a['aya_no']}\t{a['page']}\t{a['aya_text'].replace(chr(160), ' ').strip()}\n")
        g.write(f"{a['sora']}\t{a['aya_no']}\t{a['aya_text_emlaey']}\n")
