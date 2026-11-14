package com.bowentown.oldtimecamera;

import java.util.Locale;

/**
 * Tiny dotted-version comparator used by the update flow. Kept free of Android types so
 * it can be asserted on the JVM without Robolectric.
 */
final class VersionMath {

    private VersionMath() {}

    /**
     * Compares dotted versions, tolerating a leading {@code v} and a pre-release suffix
     * ({@code 1.4.0-beta} == {@code 1.4.0}). Returns {@code > 0} when {@code candidate}
     * is newer than {@code current}.
     */
    static int compare(String candidate, String current) {
        int[] a = parse(candidate);
        int[] b = parse(current);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i] ? 1 : -1;
        }
        return 0;
    }

    static int[] parse(String raw) {
        int[] parts = new int[3];
        if (raw == null) return parts;
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.startsWith("v")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash > 0) s = s.substring(0, dash);
        String[] tokens = s.split("\\.");
        for (int i = 0; i < 3 && i < tokens.length; i++) {
            try {
                parts[i] = Integer.parseInt(tokens[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
                parts[i] = 0;
            }
        }
        return parts;
    }
}
