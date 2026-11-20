package com.qalun.checker.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Qalun text (KFGQPC v10) loaded from qalun.tsv: sura \t aya \t page \t text */
public final class QuranText {

    public static final class Ayah {
        public final int sura, aya;
        public final String page, text;
        Ayah(int sura, int aya, String page, String text) {
            this.sura = sura; this.aya = aya; this.page = page; this.text = text;
        }
    }

    public static final class Sura {
        public final int number, ayahCount;
        public final String nameAr, nameEn;
        Sura(int number, String nameAr, String nameEn, int ayahCount) {
            this.number = number; this.nameAr = nameAr; this.nameEn = nameEn; this.ayahCount = ayahCount;
        }
    }

    private final Map<Integer, List<Ayah>> bySura = new HashMap<>();
    private final List<Ayah> all = new ArrayList<>();
    private final List<Sura> suras = new ArrayList<>();

    public static QuranText load(InputStream ayat, InputStream suraList) throws IOException {
        QuranText q = new QuranText();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ayat, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", 4);
                if (f.length < 4) continue;
                int s = Integer.parseInt(f[0].trim());
                List<Ayah> list = q.bySura.get(s);
                if (list == null) { list = new ArrayList<>(); q.bySura.put(s, list); }
                Ayah a = new Ayah(s, Integer.parseInt(f[1].trim()), f[2], f[3]);
                list.add(a);
                q.all.add(a);
            }
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(suraList, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t");
                if (f.length < 4) continue;
                q.suras.add(new Sura(Integer.parseInt(f[0].trim()), f[1], f[2], Integer.parseInt(f[3].trim())));
            }
        }
        return q;
    }

    public List<Sura> suras() { return suras; }

    /** Every ayah of the mushaf in order. */
    public List<Ayah> all() { return all; }

    public static int pageOf(Ayah a) {
        try { return Integer.parseInt(a.page.trim()); } catch (NumberFormatException e) { return 1; }
    }

    public List<Ayah> ayat(int sura) {
        List<Ayah> l = bySura.get(sura);
        return l == null ? new ArrayList<Ayah>() : l;
    }
}
