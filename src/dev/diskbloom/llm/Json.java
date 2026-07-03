package dev.diskbloom.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * Just enough JSON for talking to Ollama: escape strings when building a
 * request, and pull string field values out of a response. Not a general
 * parser — we only ever need string fields ("content", "name"), so this stays
 * tiny and dependency-free.
 */
public final class Json {

    public static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    /** First string value for the given key, or null. */
    public static String field(String json, String key) {
        return nextString(json, key, new int[]{0});
    }

    /** Every string value for the given key, in order. */
    public static List<String> fields(String json, String key) {
        List<String> out = new ArrayList<>();
        int[] pos = {0};
        String v;
        while ((v = nextString(json, key, pos)) != null) out.add(v);
        return out;
    }

    private static String nextString(String s, String key, int[] pos) {
        String pat = "\"" + key + "\"";
        int k = s.indexOf(pat, pos[0]);
        if (k < 0) { pos[0] = s.length(); return null; }
        int i = k + pat.length();
        while (i < s.length() && " \t\r\n:".indexOf(s.charAt(i)) >= 0) i++;
        if (i >= s.length() || s.charAt(i) != '"') { pos[0] = k + pat.length(); return nextString(s, key, pos); }
        StringBuilder b = new StringBuilder();
        i++; // past opening quote
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') { pos[0] = i; return b.toString(); }
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        if (i + 4 <= s.length()) { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    }
                    default -> b.append(e);
                }
            } else {
                b.append(c);
            }
        }
        pos[0] = s.length();
        return null;
    }

    // ponytail: one runnable check for the fiddly escape/decode + field extraction.
    static void selfCheck() {
        String sample = "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"Hi \\\"there\\\"\\nline2\"},\"done\":true}";
        assert "Hi \"there\"\nline2".equals(field(sample, "content")) : field(sample, "content");
        String req = "{\"a\":\"" + escape("q\"x\n\\y") + "\"}";
        assert "q\"x\n\\y".equals(field(req, "a")) : field(req, "a");
        List<String> names = fields("{\"models\":[{\"name\":\"a:1\"},{\"name\":\"b:2\"}]}", "name");
        assert names.size() == 2 && names.get(0).equals("a:1") && names.get(1).equals("b:2") : names;
        System.out.println("json selfcheck OK");
    }

    private Json() {}
}
