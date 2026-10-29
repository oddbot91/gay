package se.jobbdialer;

import android.telephony.PhoneNumberUtils;
import android.util.Log;

/**
 * Phone number helpers: cleaning user input, normalising for comparisons, formatting for
 * display and matching two numbers. All methods are null-safe and never throw.
 */
final class Numbers {

    private static final String TAG = "Jobbdialer";

    /** GSM "hide caller id for this call" prefixes. */
    static final String HIDE_PREFIX_HASH = "#31#";
    static final String HIDE_PREFIX_STAR = "*31#";

    /** Minimum number of digits for a suffix ("ends with") match. */
    private static final int MIN_SUFFIX_MATCH_DIGITS = 5;

    private Numbers() {
    }

    /** Keeps ASCII digits, '+', '*' and '#'; drops spaces, dashes and everything else. */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '+' || c == '*' || c == '#') {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Keeps ASCII digits only. */
    static String digitsOnly(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append(c);
            }
        }
        return b.toString();
    }

    static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Normalisation used for matching (SPEC P1 item 6): strips a leading #31# / *31# prefix and
     * keeps only digits plus a leading '+'.
     */
    static String normalizeForMatch(String s) {
        String c = clean(s);
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            if (c.startsWith(HIDE_PREFIX_HASH)) {
                c = c.substring(HIDE_PREFIX_HASH.length());
                stripped = true;
            } else if (c.startsWith(HIDE_PREFIX_STAR)) {
                c = c.substring(HIDE_PREFIX_STAR.length());
                stripped = true;
            }
        }
        String digits = digitsOnly(c);
        if (digits.isEmpty()) {
            return "";
        }
        return c.startsWith("+") ? "+" + digits : digits;
    }

    /**
     * True if two numbers refer to the same line: either PhoneNumberUtils.compare() says so, or
     * one ends with the other and the shorter one has at least 5 digits.
     */
    static boolean numbersMatch(String a, String b) {
        String na = normalizeForMatch(a);
        String nb = normalizeForMatch(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        if (phoneUtilsCompare(na, nb)) {
            return true;
        }
        String da = digitsOnly(na);
        String db = digitsOnly(nb);
        String shorter = da.length() <= db.length() ? da : db;
        String longer = da.length() <= db.length() ? db : da;
        return shorter.length() >= MIN_SUFFIX_MATCH_DIGITS && longer.endsWith(shorter);
    }

    // PhoneNumberUtils.compare is deprecated on API 35 in favour of areSamePhoneNumber (API 31+);
    // it is still the only option on API 26-30 and behaves the same.
    @SuppressWarnings("deprecation")
    private static boolean phoneUtilsCompare(String a, String b) {
        try {
            return PhoneNumberUtils.compare(a, b);
        } catch (RuntimeException e) {
            Log.w(TAG, "PhoneNumberUtils.compare failed", e);
            return false;
        }
    }

    /**
     * Readable grouping of a number for display, e.g.
     * "0313430771" → "031 34 307 71", "0701234567" → "070-123 45 67",
     * "#31#0701234567" → "#31# 070-123 45 67", "30771" → "30771".
     * Never throws; returns the input (trimmed) when it cannot be formatted.
     */
    static String formatForDisplay(String number) {
        if (number == null) {
            return "";
        }
        try {
            String c = clean(number);
            if (c.isEmpty()) {
                return number.trim();
            }
            if (c.startsWith(HIDE_PREFIX_HASH) && c.length() > HIDE_PREFIX_HASH.length()) {
                return HIDE_PREFIX_HASH + " " + formatPlain(c.substring(HIDE_PREFIX_HASH.length()));
            }
            if (c.startsWith(HIDE_PREFIX_STAR) && c.length() > HIDE_PREFIX_STAR.length()) {
                return HIDE_PREFIX_STAR + " " + formatPlain(c.substring(HIDE_PREFIX_STAR.length()));
            }
            return formatPlain(c);
        } catch (RuntimeException e) {
            Log.w(TAG, "formatForDisplay failed", e);
            return number.trim();
        }
    }

    /** Formats a cleaned number (digits with an optional leading '+'). */
    private static String formatPlain(String c) {
        // Swedish numbers in international format: +46 70-123 45 67, +46 31 34 307 71.
        if (c.startsWith("+46")) {
            String rest = c.substring(3);
            if (rest.length() == 9 && isAllDigits(rest)) {
                String national = formatPlain("0" + rest);
                return "+46 " + national.substring(1);
            }
        }
        boolean plus = c.startsWith("+");
        String body = plus ? c.substring(1) : c;
        if (body.isEmpty() || !isAllDigits(body) || body.length() <= 5) {
            // Short numbers (extensions) and service codes containing * or # stay as they are.
            return c;
        }
        String grouped;
        if (!plus && body.length() == 10 && body.startsWith("07")) {
            // Mobile: 070-123 45 67 (3-3-2-2)
            grouped = body.substring(0, 3) + "-" + body.substring(3, 6) + " "
                    + body.substring(6, 8) + " " + body.substring(8);
        } else if (!plus && body.length() == 10 && body.startsWith("0")) {
            // Landline: 031 34 307 71 (3-2-3-2)
            grouped = body.substring(0, 3) + " " + body.substring(3, 5) + " "
                    + body.substring(5, 8) + " " + body.substring(8);
        } else {
            grouped = groupsOfThree(body);
        }
        return plus ? "+" + grouped : grouped;
    }

    private static String groupsOfThree(String digits) {
        StringBuilder b = new StringBuilder(digits.length() + digits.length() / 3);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % 3 == 0) {
                b.append(' ');
            }
            b.append(digits.charAt(i));
        }
        return b.toString();
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** "prefix number" for the live preview; just the number when the prefix is empty. */
    static String joinForPreview(String prefix, String number) {
        String p = clean(prefix);
        String n = clean(number);
        if (p.isEmpty()) {
            return n;
        }
        if (n.isEmpty()) {
            return p;
        }
        return p + " " + n;
    }

    /**
     * Preview for an internal call: the prefix is shown separately and is not added twice when
     * the input already starts with it ("0313430771" → "03134 30771").
     */
    static String previewWithPrefix(String prefix, String input) {
        String p = clean(prefix);
        String n = clean(input);
        if (!p.isEmpty() && n.startsWith(p)) {
            return joinForPreview(p, n.substring(p.length()));
        }
        return joinForPreview(p, n);
    }
}
