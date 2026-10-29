package se.jobbdialer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plain-JVM test of the keypad matching (T9 + ContactMatcher). Lives in package se.jobbdialer so
 * it can use the package-private app classes. Only pure-Java code paths are exercised (android.jar
 * is on the classpath for linking only; its stubs would throw "Stub!" if called).
 *
 * Run: tools/jvmtest/run.sh
 */
public final class T9Test {

    private static int passed;

    private static final Contact IRSHAD = new Contact("irshad", "Irshad", "Mangal", "30771", "");
    private static final Contact ANNA = new Contact("anna", "Anna", "Andersson", "", "0701234567");
    private static final Contact BO = new Contact("bo", "Bo", "Ek", "30123", "");
    private static final Contact ASA = new Contact("asa", "Åsa", "Öberg", "30999", "", "Läkare", true);
    private static final Contact ERIK = new Contact("erik", "Erik", null, "31000", null);

    private static final List<Contact> LIST = Arrays.asList(IRSHAD, ANNA, BO, ASA, ERIK);

    public static void main(String[] args) {
        // ---------------------------------------------------------------- T9.toDigits
        eq("toDigits(Irshad)", "477423", T9.toDigits("Irshad"));
        eq("toDigits(Åsa Öberg)", "272062374", T9.toDigits("Åsa Öberg"));
        eq("toDigits(Anna Andersson)", "26620263377766", T9.toDigits("Anna Andersson"));
        eq("toDigits(ÄÉÜ upper case)", "238", T9.toDigits("ÄÉÜ"));
        eq("toDigits(null)", "", T9.toDigits(null));

        // ---------------------------------------------------------------- T9.matchesName
        yes("Irshad '4' (one digit is enough)", T9.matchesName("Irshad", "Mangal", "4"));
        yes("Irshad '477'", T9.matchesName("Irshad", "Mangal", "477"));
        yes("Mangal '626'", T9.matchesName("Irshad", "Mangal", "626"));
        yes("Irshad Mangal joined '477423626'", T9.matchesName("Irshad", "Mangal", "477423626"));
        yes("Irshad Mangal with space '4774230626'", T9.matchesName("Irshad", "Mangal", "4774230626"));
        yes("Anna '2662'", T9.matchesName("Anna", "Andersson", "2662"));
        yes("'anna ' = '26620'", T9.matchesName("Anna", "Andersson", "26620"));
        yes("'anna an' = '2662026'", T9.matchesName("Anna", "Andersson", "2662026"));
        yes("README example '26620263'", T9.matchesName("Anna", "Andersson", "26620263"));
        no("'262026' is not a prefix of 'anna an' (2662026)", T9.matchesName("Anna", "Andersson", "262026"));
        // "26": Anna (a-n) and Bo (b-o) match; Åsa starts 27, Irshad 4, Erik 3.
        yes("'26' Anna", T9.matchesName("Anna", "Andersson", "26"));
        yes("'26' Bo", T9.matchesName("Bo", "Ek", "26"));
        no("'26' Åsa (27...)", T9.matchesName("Åsa", "Öberg", "26"));
        no("'26' Irshad", T9.matchesName("Irshad", "Mangal", "26"));
        no("'26' Erik", T9.matchesName("Erik", "", "26"));
        yes("'27' Åsa", T9.matchesName("Åsa", "Öberg", "27"));
        yes("'6237' Öberg", T9.matchesName("Åsa", "Öberg", "6237"));
        yes("'35' Ek (last name)", T9.matchesName("Bo", "Ek", "35"));
        no("Erik without last name: no trailing space '37450'", T9.matchesName("Erik", "", "37450"));
        yes("hyphenated word 'Karin' '527'", T9.matchesName("Anna-Karin", "Lund", "527"));
        yes("case-insensitive 'IRSHAD'", T9.matchesName("IRSHAD", "MANGAL", "477"));
        no("null names", T9.matchesName(null, null, "2"));
        no("empty digits", T9.matchesName("Anna", "Andersson", ""));
        no("non-digits rejected", T9.matchesName("Anna", "Andersson", "2*"));

        // ---------------------------------------------------------------- ContactMatcher
        // "4": T9 name match Irshad (I=4) only; no number starts with 4; Bo (2/3) not listed.
        rows("match '4'", ContactMatcher.match(LIST, "4", 5), "Irshad Mangal work 30771");
        // "2": names Anna, Bo, Åsa (list order); no number starts with 2.
        rows("match '2'", ContactMatcher.match(LIST, "2", 5),
                "Anna Andersson private 0701234567", "Bo Ek work 30123", "Åsa Öberg work 30999");
        // "3": names first (Bo via "Ek", Erik), then numbers starting with 3 (Irshad, Åsa);
        // Bo and Erik are not repeated as number matches.
        rows("match '3'", ContactMatcher.match(LIST, "3", 5),
                "Bo Ek work 30123", "Erik work 31000", "Irshad Mangal work 30771", "Åsa Öberg work 30999");
        // "30": no name matches; startsWith on numbers in list order.
        rows("match '30'", ContactMatcher.match(LIST, "30", 5),
                "Irshad Mangal work 30771", "Bo Ek work 30123", "Åsa Öberg work 30999");
        // "07": 2 digits -> startsWith only: Anna's private number, NOT Irshad's 30771.
        rows("match '07' (startsWith only)", ContactMatcher.match(LIST, "07", 5),
                "Anna Andersson private 0701234567");
        // "077": 3 digits -> contains: Irshad's 30771.
        rows("match '077' (contains from 3 digits)", ContactMatcher.match(LIST, "077", 5),
                "Irshad Mangal work 30771");
        // "307": prefix match; formatting characters in the field are ignored.
        rows("match '30 7'", ContactMatcher.match(LIST, "30 7", 5), "Irshad Mangal work 30771");
        // "37": T9 Erik ("3745").
        rows("match '37'", ContactMatcher.match(LIST, "37", 5), "Erik work 31000");
        // "2662": Anna by name, shows her private number (no work number).
        rows("match '2662'", ContactMatcher.match(LIST, "2662", 5), "Anna Andersson private 0701234567");
        // '#', '*', '+': no name matching; the #31# prefix is skipped for number matching.
        rows("match '#31#0701'", ContactMatcher.match(LIST, "#31#0701", 5), "Anna Andersson private 0701234567");
        rows("match '+4' (no T9 with '+')", ContactMatcher.match(LIST, "+4", 5));
        rows("match '*'", ContactMatcher.match(LIST, "*", 5));
        rows("match ''", ContactMatcher.match(LIST, "", 5));
        rows("match null", ContactMatcher.match(LIST, null, 5));
        rows("match on null list", ContactMatcher.match(null, "4", 5));
        // "1": no letters on 1 and no number starts with 1.
        rows("match '1'", ContactMatcher.match(LIST, "1", 5));

        // Max rows and "one row per contact".
        List<Contact> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(new Contact("a" + i, "Anna" + i, "Andersson", "3077" + i, "0701234567"));
        }
        List<ContactMatcher.Match> capped = ContactMatcher.match(many, "2", 5);
        eq("max 5 rows", 5, capped.size());
        List<ContactMatcher.Match> numberRows = ContactMatcher.match(many, "0701", 5);
        eq("one row per contact (private matches) size", 5, numberRows.size());
        eq("distinct contacts", 5, distinctIds(numberRows));

        // A name match without any number is skipped (nothing to fill in); null fields are safe.
        List<Contact> odd = Arrays.asList(
                new Contact("x", "Bertil", "Nummerlös", "", ""),
                new Contact(null, null, null, null, "0311234567"),
                new Contact("y", "Bea", "", "-", "0731112233"));
        rows("name match without number skipped, '-' work number ignored", ContactMatcher.match(odd, "23", 5),
                "Bea private 0731112233");
        rows("unnamed contact via number", ContactMatcher.match(odd, "031", 5), " private 0311234567");

        System.out.println(passed + " assertions passed");
        System.out.println("ALL T9/MATCHER TESTS PASSED");
    }

    // -------------------------------------------------------------------- helpers

    private static void rows(String what, List<ContactMatcher.Match> actual, String... expected) {
        List<String> got = new ArrayList<>();
        for (ContactMatcher.Match m : actual) {
            got.add(m.toString());
        }
        List<String> want = new ArrayList<>(Arrays.asList(expected));
        // Contact.displayName() of an unnamed contact is "", so its row string starts with a space.
        if (!got.equals(want)) {
            throw new AssertionError(what + ": expected " + want + " but got " + got);
        }
        passed++;
        System.out.println("ok  " + what + " -> " + got);
    }

    private static int distinctIds(List<ContactMatcher.Match> matches) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ContactMatcher.Match m : matches) {
            ids.add(m.contact.id);
        }
        return ids.size();
    }

    private static void eq(String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but got <" + actual + ">");
        }
        passed++;
        System.out.println("ok  " + what + " = " + actual);
    }

    private static void yes(String what, boolean actual) {
        if (!actual) {
            throw new AssertionError(what + ": expected true");
        }
        passed++;
        System.out.println("ok  " + what);
    }

    private static void no(String what, boolean actual) {
        if (actual) {
            throw new AssertionError(what + ": expected false");
        }
        passed++;
        System.out.println("ok  " + what + " (false)");
    }
}
