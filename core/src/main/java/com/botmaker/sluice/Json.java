package com.botmaker.sluice;

/**
 * JSON written by hand: the little a state file and a GitHub request need, without a JSON library in the jar. It
 * is read back as the YAML it also is.
 */
public final class Json {

    private Json() {
    }

    /** {@code text} as a JSON string, quoted and escaped. */
    public static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // Escaped too: what YAML, which reads the state back, refuses unescaped.
                    if (c < 0x20 || (c >= 0x7F && c <= 0x9F) || c == 0xFFFE || c == 0xFFFF) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
