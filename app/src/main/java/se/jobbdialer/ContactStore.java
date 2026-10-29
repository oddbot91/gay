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
    private static final String FORMAT_ID = "jobbdialer-contacts";
    private static final int FORMAT_VERSION = 1;

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
        String text = readFile(file);
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        try {
            List<Contact> parsed = parseContacts(text);
            if (parsed != null) {
                contacts.addAll(parsed);
            }
        } catch (JSONException e) {
            Log.w(TAG, "contacts.json is corrupt; starting with an empty phone book", e);
        }
    }

    private synchronized boolean save() {
        try {
            JSONArray arr = new JSONArray();
            for (Contact c : contacts) {
                arr.put(c.toJson());
            }
            return writeFileAtomic(file, arr.toString());
        } catch (JSONException e) {
            Log.w(TAG, "Could not serialise contacts", e);
            return false;
        }
    }

    // ============================================================================ CRUD

    /** Snapshot of all contacts in the given order. */
    synchronized List<Contact> getAll(SortMode sort) {
        List<Contact> copy = new ArrayList<>(contacts);
        Collections.sort(copy, comparator(sort));
        return copy;
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
     * Filters on name and numbers. Every whitespace-separated word of the query must match the
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

    /** Export document (pretty printed JSON). */
    synchronized String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put(J_FORMAT, FORMAT_ID);
            root.put(J_VERSION, FORMAT_VERSION);
            root.put(J_EXPORTED, new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(new Date()));
            JSONArray arr = new JSONArray();
            for (Contact c : getAll(SortMode.LAST_NAME)) {
                arr.put(c.toJson());
            }
            root.put(J_CONTACTS, arr);
            return root.toString(2);
        } catch (JSONException e) {
            Log.w(TAG, "Could not build export JSON", e);
            return null;
        }
    }

    /**
     * Parses an export file: either {"contacts":[...]} or a bare array of contact objects.
     * Returns null when the text is valid JSON but contains no contact list.
     */
    private static List<Contact> parseContacts(String text) throws JSONException {
        String t = text.trim();
        if (t.startsWith("﻿")) {
            t = t.substring(1);
        }
        JSONArray arr;
        if (t.startsWith("[")) {
            arr = new JSONArray(t);
        } else {
            JSONObject root = new JSONObject(t);
            arr = root.optJSONArray(J_CONTACTS);
            if (arr == null) {
                return null;
            }
        }
        List<Contact> list = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            Contact c = Contact.fromJson(arr.optJSONObject(i));
            if (c != null) {
                list.add(c);
            }
        }
        return list;
    }

    /**
     * Merges contacts from an export file. A contact is skipped when it is empty, when an
     * existing contact has the same (non-empty) work number, or when an existing contact has the
     * same name and the same private number (and no conflicting work number).
     */
    synchronized ImportResult importJson(String text) {
        List<Contact> incoming;
        try {
            incoming = text == null ? null : parseContacts(text);
        } catch (JSONException e) {
            Log.w(TAG, "Import: not a valid JSON file", e);
            incoming = null;
        }
        if (incoming == null) {
            return new ImportResult(-1, 0);
        }
        int added = 0;
        int skipped = 0;
        Set<String> ids = new HashSet<>();
        for (Contact c : contacts) {
            ids.add(c.id);
        }
        for (Contact c : incoming) {
            if (c.isEmpty() || isDuplicate(c)) {
                skipped++;
                continue;
            }
            Contact toAdd = ids.contains(c.id) ? c.withNewId() : c;
            contacts.add(toAdd);
            ids.add(toAdd.id);
            added++;
        }
        if (added > 0 && !save()) {
            return new ImportResult(-1, 0);
        }
        return new ImportResult(added, skipped);
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
            return importJson(text);
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
