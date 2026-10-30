package se.jobbdialer;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phone book storage: loads/saves contacts.json in the app's files dir (atomic write), sorts,
 * searches, matches numbers for the keypad and merges imported contact files.
 * One shared instance per process ({@link #get(Context)}); all methods are thread safe.
 */
final class ContactStore {

    private static final String TAG = "Jobbdialer";
    private static final String FILE_NAME = "contacts.json";

    /** Top-level keys of the export file. */
    private static final String J_FORMAT = "format";
    private static final String J_VERSION = "version";
    private static final String J_EXPORTED = "exported";
    private static final String J_CONTACTS = "contacts";
    private static final String J_CATEGORIES = "categories";
    private static final String FORMAT_ID = "jobbdialer-contacts";
    private static final int FORMAT_VERSION = 2;

    /** Maximum number of favorites (the "Favoriter" group shows at most this many). */
    static final int MAX_FAVORITES = 10;

    /** Categories created the first time the app runs (alphabetical, sv-SE). */
    private static final String[] SEED_CATEGORIES = {
            "Avdelningar", "Konsulttelefoner", "Läkare", "Operationssalar"};

    /** Upper bound for files read from disk / via SAF (protects against picking a huge file). */
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    static final Locale SWEDISH = new Locale("sv", "SE");

    /** Sort orders offered in the contacts list. */
    enum SortMode {
        LAST_NAME(R.string.sort_last_name),
        FIRST_NAME(R.string.sort_first_name),
        WORK_NUMBER(R.string.sort_work_number),
        PRIVATE_NUMBER(R.string.sort_private_number);

        final int labelResId;

        SortMode(int labelResId) {
            this.labelResId = labelResId;
        }

        static SortMode parse(String name) {
            if (name != null) {
                for (SortMode m : values()) {
                    if (m.name().equals(name)) {
                        return m;
                    }
                }
            }
            return LAST_NAME;
        }
    }

    /** A keypad match: which contact and which of its numbers matched. */
    static final class NumberMatch {
        final Contact contact;
        final boolean work;
        final String number;

        NumberMatch(Contact contact, boolean work, String number) {
            this.contact = contact;
            this.work = work;
            this.number = number;
        }
    }

    /** Outcome of an import. {@code added < 0} means the file could not be read/parsed. */
    static final class ImportResult {
        final int added;
        final int skipped;

        ImportResult(int added, int skipped) {
            this.added = added;
            this.skipped = skipped;
        }

        boolean failed() {
            return added < 0;
        }
    }

    private static ContactStore instance;

    private final File file;
    private final List<Contact> contacts = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();

    /** A parsed contacts file; {@code categories} is null for the v1 format / when absent. */
    private static final class ParsedFile {
        final List<Contact> contacts;
        final List<String> categories;

        ParsedFile(List<Contact> contacts, List<String> categories) {
            this.contacts = contacts;
            this.categories = categories;
        }
    }

    private ContactStore(Context appContext) {
        file = new File(appContext.getFilesDir(), FILE_NAME);
        load();
    }

    static synchronized ContactStore get(Context context) {
        if (instance == null) {
            instance = new ContactStore(context.getApplicationContext());
        }
        return instance;
    }

    // ============================================================================ persistence

    private synchronized void load() {
        contacts.clear();
        categories.clear();
        List<String> fileCategories = null;
        String text = readFile(file);
        if (text != null && !text.trim().isEmpty()) {
            try {
                ParsedFile parsed = parseFile(text);
                if (parsed != null) {
                    contacts.addAll(parsed.contacts);
                    fileCategories = parsed.categories;
                }
            } catch (JSONException e) {
                Log.w(TAG, "contacts.json is corrupt; starting with an empty phone book", e);
            }
        }
        if (fileCategories == null) {
            // First run or a v1 file: seed the default categories.
            for (String c : SEED_CATEGORIES) {
                categories.add(c);
            }
        } else {
            for (String c : fileCategories) {
                addCategoryInternal(c);
            }
        }
        // Contacts may reference categories that are not in the list: show them anyway.
        for (Contact c : contacts) {
            if (!c.category.isEmpty()) {
                c.category = canonicalCategory(c.category);
            }
        }
    }

    private synchronized boolean save() {
        try {
            return writeFileAtomic(file, buildDocument(false).toString());
        } catch (JSONException e) {
            Log.w(TAG, "Could not serialise contacts", e);
            return false;
        }
    }

    /** Builds the v2 document (shared by save and export). */
    private JSONObject buildDocument(boolean withExportDate) throws JSONException {
        JSONObject root = new JSONObject();
        root.put(J_FORMAT, FORMAT_ID);
        root.put(J_VERSION, FORMAT_VERSION);
        if (withExportDate) {
            root.put(J_EXPORTED, new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(new Date()));
        }
        JSONArray cats = new JSONArray();
        for (String c : categories) {
            cats.put(c);
        }
        root.put(J_CATEGORIES, cats);
        JSONArray arr = new JSONArray();
        List<Contact> source = withExportDate ? getAll(SortMode.LAST_NAME) : contacts;
        for (Contact c : source) {
            arr.put(c.toJson());
        }
        root.put(J_CONTACTS, arr);
        return root;
    }

    // ============================================================================ categories

    /** Snapshot of the categories in the user's order. */
    synchronized List<String> getCategories() {
        return new ArrayList<>(categories);
    }

    /** Adds a category (trimmed, non-empty, no case-insensitive duplicate). */
    synchronized boolean addCategory(String name) {
        if (!addCategoryInternal(name)) {
            return false;
        }
        save();
        return true;
    }

    private boolean addCategoryInternal(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || indexOfCategory(n) >= 0) {
            return false;
        }
        categories.add(n);
        return true;
    }

    /** Removes a category; its contacts get category "" (shown under "Övriga"). */
    synchronized boolean removeCategory(String name) {
        int idx = indexOfCategory(name);
        if (idx < 0) {
            return false;
        }
        String removed = categories.remove(idx);
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.get(i);
            if (c.category.equalsIgnoreCase(removed)) {
                c.category = "";
            }
        }
        save();
        return true;
    }

    synchronized int countInCategory(String name) {
        int n = 0;
        String key = name == null ? "" : name.trim();
        for (Contact c : contacts) {
            if (c.category.equalsIgnoreCase(key)) {
                n++;
            }
        }
        return n;
    }

    private int indexOfCategory(String name) {
        String n = name == null ? "" : name.trim();
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).equalsIgnoreCase(n)) {
                return i;
            }
        }
        return -1;
    }

    /** The list's spelling of a category, adding it to the list when unknown. */
    private String canonicalCategory(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            return "";
        }
        int idx = indexOfCategory(n);
        if (idx >= 0) {
            return categories.get(idx);
        }
        categories.add(n);
        return n;
    }

    // ============================================================================ favorites

    synchronized int favoriteCount() {
        int n = 0;
        for (Contact c : contacts) {
            if (c.favorite) {
                n++;
            }
        }
        return n;
    }

    /** Up to {@code max} visible (not hidden) favorites in the given order. */
    synchronized List<Contact> getFavorites(SortMode sort, int max) {
        List<Contact> result = new ArrayList<>();
        for (Contact c : getVisible(sort)) {
            if (c.favorite) {
                if (result.size() >= max) {
                    break;
                }
                result.add(c);
            }
        }
        return result;
    }

    /** Sets the favorite flag; false when it would exceed {@link #MAX_FAVORITES} or id is unknown. */
    synchronized boolean setFavorite(String id, boolean favorite) {
        Contact c = findById(id);
        if (c == null) {
            return false;
        }
        if (favorite && !c.favorite && favoriteCount() >= MAX_FAVORITES) {
            return false;
        }
        c.favorite = favorite;
        save();
        return true;
    }

    // ============================================================================ CRUD

    /** Snapshot of all contacts in the given order. */
    synchronized List<Contact> getAll(SortMode sort) {
        List<Contact> copy = new ArrayList<>(contacts);
        Collections.sort(copy, comparator(sort));
        return copy;
    }

    /** Snapshot of the contacts that are not hidden, in the given order. */
    synchronized List<Contact> getVisible(SortMode sort) {
        List<Contact> result = new ArrayList<>(contacts.size());
        for (Contact c : getAll(sort)) {
            if (!c.hidden) {
                result.add(c);
            }
        }
        return result;
    }

    synchronized boolean isEmpty() {
        return contacts.isEmpty();
    }

    synchronized int size() {
        return contacts.size();
    }

    synchronized Contact findById(String id) {
        if (id == null) {
            return null;
        }
        for (Contact c : contacts) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** Inserts a new contact or replaces the one with the same id. */
    synchronized boolean put(Contact contact) {
        if (contact == null) {
            return false;
        }
        contact.category = canonicalCategory(contact.category);
        for (int i = 0; i < contacts.size(); i++) {
            if (contacts.get(i).id.equals(contact.id)) {
                contacts.set(i, contact);
                return save();
            }
        }
        contacts.add(contact);
        return save();
    }

    synchronized boolean delete(String id) {
        for (int i = 0; i < contacts.size(); i++) {
            if (contacts.get(i).id.equals(id)) {
                contacts.remove(i);
                return save();
            }
        }
        return false;
    }

    /**
     * Removes every contact whose id is in {@code ids} and saves once.
     *
     * @return the number of contacts removed (0 when none matched, nothing is saved then), or -1
     *         when contacts were removed but the file could not be written.
     */
    synchronized int deleteAll(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Set<String> wanted = new HashSet<>(ids);
        int removed = 0;
        for (int i = contacts.size() - 1; i >= 0; i--) {
            if (wanted.contains(contacts.get(i).id)) {
                contacts.remove(i);
                removed++;
            }
        }
        if (removed == 0) {
            return 0;
        }
        return save() ? removed : -1;
    }

    /**
     * Sets the hidden flag of every contact whose id is in {@code ids} and saves once.
     *
     * @return the number of contacts whose flag changed (0 when none did, nothing is saved then),
     *         or -1 when the file could not be written.
     */
    synchronized int setHidden(Collection<String> ids, boolean hidden) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Set<String> wanted = new HashSet<>(ids);
        int changed = 0;
        for (Contact c : contacts) {
            if (c.hidden != hidden && wanted.contains(c.id)) {
                c.hidden = hidden;
                changed++;
            }
        }
        if (changed == 0) {
            return 0;
        }
        return save() ? changed : -1;
    }

    // ============================================================================ sorting

    static Comparator<Contact> comparator(SortMode sort) {
        final Collator collator = Collator.getInstance(SWEDISH);
        collator.setStrength(Collator.SECONDARY);
        final Comparator<Contact> byLastThenFirst = new Comparator<Contact>() {
            @Override
            public int compare(Contact a, Contact b) {
                int r = compareText(collator, a.lastName, b.lastName);
                if (r != 0) {
                    return r;
                }
                return compareText(collator, a.firstName, b.firstName);
            }
        };
        final Comparator<Contact> byFirstThenLast = new Comparator<Contact>() {
            @Override
            public int compare(Contact a, Contact b) {
                int r = compareText(collator, a.firstName, b.firstName);
                if (r != 0) {
                    return r;
                }
                return compareText(collator, a.lastName, b.lastName);
            }
        };
        SortMode mode = sort == null ? SortMode.LAST_NAME : sort;
        switch (mode) {
            case FIRST_NAME:
                return withTieBreak(byFirstThenLast);
            case WORK_NUMBER:
                return new Comparator<Contact>() {
                    @Override
                    public int compare(Contact a, Contact b) {
                        int r = compareNumber(a.workNumber, b.workNumber);
                        return r != 0 ? r : byLastThenFirst.compare(a, b);
                    }
                };
            case PRIVATE_NUMBER:
                return new Comparator<Contact>() {
                    @Override
                    public int compare(Contact a, Contact b) {
                        int r = compareNumber(a.privateNumber, b.privateNumber);
                        return r != 0 ? r : byLastThenFirst.compare(a, b);
                    }
                };
            case LAST_NAME:
            default:
                return withTieBreak(byLastThenFirst);
        }
    }

    private static Comparator<Contact> withTieBreak(final Comparator<Contact> base) {
        return new Comparator<Contact>() {
            @Override
            public int compare(Contact a, Contact b) {
                int r = base.compare(a, b);
                if (r != 0) {
                    return r;
                }
                r = compareNumber(a.workNumber, b.workNumber);
                return r != 0 ? r : compareNumber(a.privateNumber, b.privateNumber);
            }
        };
    }

    /** Collator compare with empty strings sorted last. */
    private static int compareText(Collator collator, String a, String b) {
        boolean ea = a == null || a.isEmpty();
        boolean eb = b == null || b.isEmpty();
        if (ea || eb) {
            return ea == eb ? 0 : (ea ? 1 : -1);
        }
        return collator.compare(a, b);
    }

    /** Ascending string compare on the digits; contacts without the number last. */
    private static int compareNumber(String a, String b) {
        String da = Numbers.digitsOnly(a);
        String db = Numbers.digitsOnly(b);
        boolean ea = da.isEmpty();
        boolean eb = db.isEmpty();
        if (ea || eb) {
            return ea == eb ? 0 : (ea ? 1 : -1);
        }
        return da.compareTo(db);
    }

    // ============================================================================ search

    /**
     * Filters on name and numbers; hidden contacts are included (a search is how they are found).
     * Every whitespace-separated word of the query must match the
     * name (case-insensitive substring) or, when it contains digits, one of the numbers.
     */
    synchronized List<Contact> search(String query, SortMode sort) {
        List<Contact> all = getAll(sort);
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return all;
        }
        String[] words = q.toLowerCase(SWEDISH).split("\\s+");
        List<Contact> result = new ArrayList<>();
        for (Contact c : all) {
            if (matchesAllWords(c, words)) {
                result.add(c);
            }
        }
        return result;
    }

    private static boolean matchesAllWords(Contact c, String[] words) {
        String name = c.displayName().toLowerCase(SWEDISH);
        String work = Numbers.digitsOnly(c.workNumber);
        String priv = Numbers.digitsOnly(c.privateNumber);
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (name.contains(w)) {
                continue;
            }
            String digits = Numbers.digitsOnly(w);
            if (!digits.isEmpty() && (work.contains(digits) || priv.contains(digits))) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Keypad matching: contacts whose work or private number contains the typed digits.
     * Numbers that start with the digits are listed first, then alphabetically.
     */
    synchronized List<NumberMatch> matchNumber(String typedDigits, int max) {
        List<NumberMatch> result = new ArrayList<>();
        final String digits = Numbers.digitsOnly(typedDigits);
        if (digits.isEmpty() || max <= 0) {
            return result;
        }
        for (Contact c : getAll(SortMode.LAST_NAME)) {
            if (Numbers.digitsOnly(c.workNumber).contains(digits)) {
                result.add(new NumberMatch(c, true, c.workNumber));
            }
            if (Numbers.digitsOnly(c.privateNumber).contains(digits)) {
                result.add(new NumberMatch(c, false, c.privateNumber));
            }
        }
        // Stable sort: prefix matches first, otherwise keep the alphabetical order.
        Collections.sort(result, new Comparator<NumberMatch>() {
            @Override
            public int compare(NumberMatch a, NumberMatch b) {
                boolean pa = Numbers.digitsOnly(a.number).startsWith(digits);
                boolean pb = Numbers.digitsOnly(b.number).startsWith(digits);
                return pa == pb ? 0 : (pa ? -1 : 1);
            }
        });
        if (result.size() > max) {
            return new ArrayList<>(result.subList(0, max));
        }
        return result;
    }

    /**
     * Best-effort name lookup for the call history. {@code rawInput} is what was typed or
     * tapped, {@code dialed} the full number that was dialed (with prefix).
     */
    synchronized String findNameForCall(Dialer.CallMode mode, String rawInput, String dialed, String internalPrefix) {
        if (mode == Dialer.CallMode.SWITCHBOARD) {
            return null;
        }
        String raw = Numbers.clean(rawInput);
        String prefix = Numbers.clean(internalPrefix);
        String rawWithoutPrefix = (!prefix.isEmpty() && raw.startsWith(prefix) && raw.length() > prefix.length())
                ? raw.substring(prefix.length()) : raw;
        // 1) exact work number (short extension), with or without the internal prefix
        if (!raw.isEmpty()) {
            for (Contact c : contacts) {
                String w = Numbers.clean(c.workNumber);
                if (!w.isEmpty() && (w.equals(raw) || w.equals(rawWithoutPrefix))) {
                    return nameOrNull(c);
                }
            }
        }
        // 2) private number, compared like phone numbers (handles +46 / 0 and #31#)
        for (Contact c : contacts) {
            if (c.hasPrivate() && (Numbers.numbersMatch(c.privateNumber, raw)
                    || Numbers.numbersMatch(c.privateNumber, dialed))) {
                return nameOrNull(c);
            }
        }
        return null;
    }

    private static String nameOrNull(Contact c) {
        String n = c.displayName();
        return n.isEmpty() ? null : n;
    }

    // ============================================================================ export / import

    /** Export document (pretty printed JSON, format version 2). */
    synchronized String toJson() {
        try {
            return buildDocument(true).toString(2);
        } catch (JSONException e) {
            Log.w(TAG, "Could not build export JSON", e);
            return null;
        }
    }

    /**
     * Parses a contacts file (v1 bare array, or the v2 object). Returns null when the text is
     * valid JSON but contains no contact list.
     */
    private static ParsedFile parseFile(String text) throws JSONException {
        String t = text.trim();
        if (t.startsWith("\ufeff")) {
            t = t.substring(1);
        }
        JSONArray arr;
        List<String> cats = null;
        if (t.startsWith("[")) {
            arr = new JSONArray(t);
        } else {
            JSONObject root = new JSONObject(t);
            arr = root.optJSONArray(J_CONTACTS);
            if (arr == null) {
                return null;
            }
            JSONArray catArr = root.optJSONArray(J_CATEGORIES);
            if (catArr != null) {
                cats = new ArrayList<>();
                for (int i = 0; i < catArr.length(); i++) {
                    String name = catArr.optString(i, "").trim();
                    if (!name.isEmpty()) {
                        cats.add(name);
                    }
                }
            }
        }
        List<Contact> list = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            Contact c = Contact.fromJson(arr.optJSONObject(i));
            if (c != null) {
                list.add(c);
            }
        }
        return new ParsedFile(list, cats);
    }

    /** Parses an export file and returns its contacts (null when there is no contact list). */
    private static List<Contact> parseContacts(String text) throws JSONException {
        ParsedFile f = parseFile(text);
        return f == null ? null : f.contacts;
    }

    /**
     * Merges contacts from an export file (v1 or v2). A contact is skipped when it is empty, when
     * an existing contact has the same (non-empty) work number, or when an existing contact has
     * the same name and the same private number (and no conflicting work number). Categories in
     * the file that do not exist yet are added.
     */
    synchronized ImportResult importJson(String text) {
        ParsedFile parsed;
        try {
            parsed = text == null ? null : parseFile(text);
        } catch (JSONException e) {
            Log.w(TAG, "Import: not a valid JSON file", e);
            parsed = null;
        }
        if (parsed == null) {
            return new ImportResult(-1, 0);
        }
        int categoriesBefore = categories.size();
        if (parsed.categories != null) {
            for (String c : parsed.categories) {
                addCategoryInternal(c);
            }
        }
        int added = 0;
        int skipped = 0;
        Set<String> ids = new HashSet<>();
        for (Contact c : contacts) {
            ids.add(c.id);
        }
        int favorites = favoriteCount();
        for (Contact c : parsed.contacts) {
            if (c.isEmpty() || isDuplicate(c)) {
                skipped++;
                continue;
            }
            c.category = canonicalCategory(c.category);
            if (c.favorite) {
                if (favorites >= MAX_FAVORITES) {
                    c.favorite = false;
                } else {
                    favorites++;
                }
            }
            Contact toAdd = ids.contains(c.id) ? c.withNewId() : c;
            contacts.add(toAdd);
            ids.add(toAdd.id);
            added++;
        }
        if ((added > 0 || categories.size() != categoriesBefore) && !save()) {
            return new ImportResult(-1, 0);
        }
        return new ImportResult(added, skipped);
    }

    /**
     * Merges a pasted/CSV list, one contact per line:
     * {@code Förnamn Efternamn ; Jobbnr ; Privatnr ; Kategori}. The separator (';', tab or ',')
     * is the first of those found on the line. Blank lines and a header line are ignored.
     */
    synchronized ImportResult importText(String text) {
        if (text == null) {
            return new ImportResult(-1, 0);
        }
        String t = text.startsWith("\ufeff") ? text.substring(1) : text;
        String[] lines = t.split("\\r?\\n|\\r");
        int categoriesBefore = categories.size();
        int added = 0;
        int skipped = 0;
        boolean firstLine = true;
        int favorites = favoriteCount();
        for (String raw : lines) {
            if (raw.trim().isEmpty()) {
                continue;
            }
            String[] f = splitFields(raw);
            if (firstLine) {
                firstLine = false;
                if (isHeaderLine(raw, f)) {
                    continue;
                }
            }
            String name = f[0];
            String first = name;
            String last = "";
            int cut = lastWhitespace(name);
            if (cut > 0) {
                first = name.substring(0, cut).trim();
                last = name.substring(cut + 1).trim();
            }
            Contact c = new Contact(null, first, last, f[1], f[2], "", false);
            if (c.isEmpty() || isDuplicate(c)) {
                skipped++;
                continue;
            }
            c.category = canonicalCategory(f[3]);
            contacts.add(c);
            added++;
        }
        if ((added > 0 || categories.size() != categoriesBefore) && !save()) {
            return new ImportResult(-1, 0);
        }
        return new ImportResult(added, skipped);
    }

    /** Splits a line into exactly four trimmed fields: name, work, private, category. */
    private static String[] splitFields(String line) {
        String sep = null;
        for (String candidate : new String[]{";", "\t", ","}) {
            if (line.contains(candidate)) {
                sep = candidate;
                break;
            }
        }
        String[] result = new String[]{"", "", "", ""};
        if (sep == null) {
            result[0] = line.trim();
            return result;
        }
        String[] parts = line.split(java.util.regex.Pattern.quote(sep), -1);
        for (int i = 0; i < result.length && i < parts.length; i++) {
            result[i] = parts[i].trim();
        }
        return result;
    }

    private static boolean isHeaderLine(String line, String[] fields) {
        String lower = line.toLowerCase(SWEDISH);
        if (!lower.contains("namn") && !lower.contains("jobb")) {
            return false;
        }
        return !hasDigit(fields[1]) && !hasDigit(fields[2]);
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Index of the last whitespace character in a trimmed string, or -1. */
    private static int lastWhitespace(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isDuplicate(Contact in) {
        String inWork = Numbers.clean(in.workNumber);
        String inPriv = Numbers.clean(in.privateNumber);
        String inName = in.displayName().toLowerCase(SWEDISH);
        for (Contact c : contacts) {
            String work = Numbers.clean(c.workNumber);
            if (!inWork.isEmpty() && inWork.equals(work)) {
                return true;
            }
            boolean sameName = inName.equals(c.displayName().toLowerCase(SWEDISH));
            boolean samePrivate = inPriv.equals(Numbers.clean(c.privateNumber));
            boolean conflictingWork = !inWork.isEmpty() && !work.isEmpty() && !inWork.equals(work);
            if (sameName && samePrivate && !conflictingWork) {
                return true;
            }
        }
        return false;
    }

    /** Writes the export JSON to a SAF document. Returns the number of contacts, or -1. */
    int exportTo(Context context, Uri uri) {
        String json = toJson();
        if (json == null || uri == null) {
            return -1;
        }
        int count = size();
        ContentResolver cr = context.getContentResolver();
        OutputStream os = null;
        try {
            try {
                os = cr.openOutputStream(uri, "wt");
            } catch (IOException | IllegalArgumentException | UnsupportedOperationException e) {
                // Some document providers do not support truncate mode.
                os = cr.openOutputStream(uri, "w");
            }
            if (os == null) {
                return -1;
            }
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
            return count;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Export failed", e);
            return -1;
        } finally {
            closeQuietly(os);
        }
    }

    /** Reads a SAF document and merges it. */
    ImportResult importFrom(Context context, Uri uri) {
        if (uri == null) {
            return new ImportResult(-1, 0);
        }
        InputStream is = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
            if (is == null) {
                return new ImportResult(-1, 0);
            }
            String text = readAll(is);
            if (text == null) {
                return new ImportResult(-1, 0);
            }
            String head = text.trim();
            if (head.startsWith("\ufeff")) {
                head = head.substring(1).trim();
            }
            if (head.startsWith("{") || head.startsWith("[")) {
                return importJson(text);
            }
            return importText(text);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Import failed", e);
            return new ImportResult(-1, 0);
        } finally {
            closeQuietly(is);
        }
    }

    /** Suggested file name for an export, e.g. jobbdialer-kontakter-2026-10-29.json. */
    static String exportFileName(Context context) {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        return context.getString(R.string.export_file_name, date);
    }

    // ============================================================================ file helpers
    // Shared with RecentsStore.

    /** Reads a whole UTF-8 file; null when missing or unreadable. */
    static String readFile(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            return readAll(in);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not read " + f.getName(), e);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** Writes to "name.tmp", then renames over the target. */
    static boolean writeFileAtomic(File target, String content) {
        if (target == null) {
            return false;
        }
        File tmp = new File(target.getPath() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            if (tmp.renameTo(target)) {
                return true;
            }
            // Fallback for file systems where rename does not replace an existing file.
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Could not replace " + target.getName());
                return false;
            }
            if (tmp.renameTo(target)) {
                return true;
            }
            Log.w(TAG, "Could not rename " + tmp.getName());
            return false;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not write " + target.getName(), e);
            return false;
        } finally {
            closeQuietly(out);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_FILE_BYTES) {
                Log.w(TAG, "File too large, ignoring");
                return null;
            }
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException | RuntimeException ignored) {
                // best effort
            }
        }
    }
}
