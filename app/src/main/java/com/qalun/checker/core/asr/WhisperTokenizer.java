package com.qalun.checker.core.asr;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Decodes Whisper (GPT-2 byte-level BPE) token ids to text. tokens.txt = one token string per line, line n = id n. */
public final class WhisperTokenizer {
    private final String[] tokens;
    private final int firstSpecial;
    private static final Map<Character, Integer> BYTE_DECODER = new HashMap<>();

    static {
        List<Integer> bs = new ArrayList<>();
        for (int b = '!'; b <= '~'; b++) bs.add(b);
        for (int b = 0xA1; b <= 0xAC; b++) bs.add(b);
        for (int b = 0xAE; b <= 0xFF; b++) bs.add(b);
        List<Integer> cs = new ArrayList<>(bs);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) { bs.add(b); cs.add(256 + n); n++; }
        }
        for (int i = 0; i < bs.size(); i++) BYTE_DECODER.put((char) (int) cs.get(i), bs.get(i));
    }

    public WhisperTokenizer(InputStream tokensTxt, int firstSpecialId) throws IOException {
        List<String> list = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(tokensTxt, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) list.add(line);
        }
        tokens = list.toArray(new String[0]);
        firstSpecial = firstSpecialId;
    }

    public String decode(List<Integer> ids) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int id : ids) {
            if (id < 0 || id >= firstSpecial || id >= tokens.length) continue;
            String t = tokens[id];
            for (int i = 0; i < t.length(); i++) {
                Integer b = BYTE_DECODER.get(t.charAt(i));
                if (b != null) out.write(b);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
    }
}
