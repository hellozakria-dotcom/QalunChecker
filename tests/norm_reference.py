import re
DROP=set(range(0x64B,0x653))|{0x653,0x654,0x655,0x656,0x657,0x658,0x65E,0x640,0x200F,0x200E,0x670}|set(range(0x6D6,0x6EE))-{0x6E7}
MAP={'أ':'','إ':'','آ':'','ٱ':'','ة':'ت','ؤ':'','ئ':'','ء':'','ے':'ي','ۧ':'ي','ا':''}
def norm(w):
    w=w.replace('ے','ي')
    w=re.sub('[يى]\u0670(?=[\u0621-\u064A])','',w.replace('و\u0670',''))  # waw written in place of alif
    s=[]
    for ch in w:
        o=ord(ch)
        if o in DROP or '٠'<=ch<='٩' or ch.isdigit(): continue
        s.append(ch)
    # alif maqsura: final -> ي, medial -> alif (dropped)
    t=[]
    for i,ch in enumerate(s):
        if ch=='ى': t.append('ي' if i==len(s)-1 else ''); continue
        t.append(MAP.get(ch,ch))
    w=''.join(t)
    w=re.sub(r'(.)\1+',r'\1',w)   # collapse doubled letters (shadda / lam-lam spelling)
    return w
def lev(a,b):
    p=list(range(len(b)+1))
    for i,ca in enumerate(a,1):
        c=[i]
        for j,cb in enumerate(b,1):
            c.append(min(p[j]+1,c[j-1]+1,p[j-1]+(ca!=cb)))
        p=c
    return p[-1]
