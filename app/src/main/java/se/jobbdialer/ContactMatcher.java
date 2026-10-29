package se.jobbdialer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keypad contact matching for the dial pane (pure Java, no android.* calls, so it can be
 * tested on a plain JVM).
 *
 * <p>Ranking, one row per contact, at most {@code max} rows:
 * <ol>
 *   <li>T9 name matches (from the first digit): the digits are the T9 start of a word of the
 *       first or last name, of the joined name, or of "first last" with 0 as the space
 *       (see {@link T9#matchesName}). The row shows the work number if present, else the
 *       private number. Only when the field holds nothing but digits.</li>
 *   <li>Number matches on the digits of the work / private number: for 1-2 typed digits only
 *       numbers that start with them; from 3 digits numbers that start with them first, then
 *       numbers that contain them.</li>
 * </ol>
 * Within each step the order of the input list is kept.
 */
final class ContactMatcher {

    /** One keypad match: which contact, and which of its numbers fills the field on tap. */
    static final class Match {
        final Contact contact;
        final boolean work;
        final String number;

        Match(Contact contact, boolean work, String number) {
            this.contact = contact;
            this.work = work;
            this.number = number;
        }

        @Override
        public String toString() {
            return (contact == null ? "?" : contact.displayName()) + (work ? " work " : " private ") + number;
        }
    }

    /** From this many typed digits a number may also match anywhere ("contains"). */
    static final int MIN_DIGITS_FOR_CONTAINS = 3;

    private ContactMatcher() {
    }

    /**
     * Matches {@code typed} (the raw field text; spaces/dashes are ignored, a leading #31# /
     * *31# hide-caller-id prefix is skipped) against {@code contacts}. Null-safe; returns an
     * empty list when nothing was typed.
     */
    static List<Match> match(List<Contact> contacts, String typed, int max) {
        List<Match> result = new ArrayList<>();
        if (contacts == null || max <= 0) {
            return result;
        }
        String cleaned = Numbers.clean(typed);
        // Digits used for number matching: without a hide-caller-id prefix and without '+'.
        String digits = Numbers.digitsOnly(Numbers.normalizeForMatch(cleaned));
        if (digits.isEmpty()) {
            return result;
        }
        Set<String> shown = new HashSet<>();

        // 1) T9 names. Only for pure digit input: "+", "*" or "#" mean a number is being typed.
        if (cleaned.equals(digits)) {
            for (Contact c : contacts) {
                if (result.size() >= max) {
                    return result;
                }
                if (c == null || shown.contains(c.id) || !T9.matchesName(c.firstName, c.lastName, digits)) {
                    continue;
                }
                boolean work = hasDigits(c.workNumber);
                String number = work ? c.workNumber : c.privateNumber;
                if (!hasDigits(number)) {
                    continue; // nothing to fill in
                }
                shown.add(c.id);
                result.add(new Match(c, work, number));
            }
        }

        // 2) Numbers: prefix matches first; "contains" only from MIN_DIGITS_FOR_CONTAINS digits.
        addNumberMatches(contacts, digits, false, shown, result, max);
        if (digits.length() >= MIN_DIGITS_FOR_CONTAINS) {
            addNumberMatches(contacts, digits, true, shown, result, max);
        }
        return result;
    }

    private static void addNumberMatches(List<Contact> contacts, String digits, boolean contains,
            Set<String> shown, List<Match> result, int max) {
        for (Contact c : contacts) {
            if (result.size() >= max) {
                return;
            }
            if (c == null || shown.contains(c.id)) {
                continue;
            }
            if (numberMatches(c.workNumber, digits, contains)) {
                shown.add(c.id);
                result.add(new Match(c, true, c.workNumber));
            } else if (numberMatches(c.privateNumber, digits, contains)) {
                shown.add(c.id);
                result.add(new Match(c, false, c.privateNumber));
            }
        }
    }

    private static boolean numberMatches(String number, String digits, boolean contains) {
        String d = Numbers.digitsOnly(number);
        if (d.isEmpty()) {
            return false;
        }
        return contains ? d.contains(digits) : d.startsWith(digits);
    }

    private static boolean hasDigits(String number) {
        return !Numbers.digitsOnly(number).isEmpty();
    }
}
