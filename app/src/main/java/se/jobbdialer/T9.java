package se.jobbdialer;

import java.util.Locale;

/** Swedish T9 keypad mapping used to match contact names against typed digits. */
final class T9 {

    /** Letters per key, index = digit (0 = space, 1 = none). */
    static final String[] KEYS = {" ", "", "abcåä", "defé", "ghi", "jkl", "mnoö", "pqrs", "tuv", "wxyz"};

    /** Extra diacritics that fall back to the key of their base letter. */
    private static final String EXTRAS = "é3ü8ø6æ2";

    private static final Locale SV = Locale.forLanguageTag("sv-SE");

    private T9() {
    }

    /** Maps text to keypad digits: letters to their key, space to '0', digits kept, rest dropped. */
    static String toDigits(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(SV);
        StringBuilder b = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append(c);
            } else if (c == ' ') {
                b.append('0');
            } else {
                char d = digitFor(c);
                if (d != 0) {
                    b.append(d);
                }
            }
        }
        return b.toString();
    }

    private static char digitFor(char c) {
        for (int d = 2; d < KEYS.length; d++) {
            if (KEYS[d].indexOf(c) >= 0) {
                return (char) ('0' + d);
            }
        }
        for (int i = 0; i + 1 < EXTRAS.length(); i += 2) {
            if (EXTRAS.charAt(i) == c) {
                return EXTRAS.charAt(i + 1);
            }
        }
        return 0;
    }

    /**
     * True when {@code digits} (at least 1, digits only) is the T9 start of a word of the first
     * or last name (words split on spaces and hyphens), of the whole first or last name, of both
     * joined without space, or of "first last" (where 0 stands for the space). Null-safe.
     */
    static boolean matchesName(String firstName, String lastName, String digits) {
        if (digits == null || digits.isEmpty()) {
            return false;
        }
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        String f = firstName == null ? "" : firstName.trim();
        String l = lastName == null ? "" : lastName.trim();
        if (f.isEmpty() && l.isEmpty()) {
            return false;
        }
        if (toDigits(f).startsWith(digits)
                || toDigits(l).startsWith(digits)
                || toDigits(f + l).startsWith(digits)
                || (!f.isEmpty() && !l.isEmpty() && toDigits(f + " " + l).startsWith(digits))) {
            return true;
        }
        return anyWordStartsWith(f, digits) || anyWordStartsWith(l, digits);
    }

    /** True when a word of {@code name} (split on whitespace and hyphens) starts with the digits. */
    private static boolean anyWordStartsWith(String name, String digits) {
        if (name.isEmpty()) {
            return false;
        }
        for (String word : name.split("[\\s\\-]+")) {
            if (!word.isEmpty() && toDigits(word).startsWith(digits)) {
                return true;
            }
        }
        return false;
    }
}
