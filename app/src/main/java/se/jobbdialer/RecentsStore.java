package se.jobbdialer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The app's own call history, newest first, capped at {@link #MAX_ENTRIES}.
 * Stored in recents.json (atomic write). One shared instance per process.
 */
final class RecentsStore {

    private static final String TAG = "Jobbdialer";
    private static final String FILE_NAME = "recents.json";
    static final int MAX_ENTRIES = 200;

    private static RecentsStore instance;

    private final File file;
    private final List<CallRecord> records = new ArrayList<>();

    private RecentsStore(Context appContext) {
        file = new File(appContext.getFilesDir(), FILE_NAME);
        load();
    }

    static synchronized RecentsStore get(Context context) {
        if (instance == null) {
            instance = new RecentsStore(context.getApplicationContext());
        }
        return instance;
    }

    private synchronized void load() {
        records.clear();
        String text = ContactStore.readFile(file);
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(text.trim());
            for (int i = 0; i < arr.length() && records.size() < MAX_ENTRIES; i++) {
                CallRecord r = CallRecord.fromJson(arr.optJSONObject(i));
                if (r != null) {
                    records.add(r);
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "recents.json is corrupt; starting with an empty history", e);
        }
    }

    private synchronized boolean save() {
        try {
            JSONArray arr = new JSONArray();
            for (CallRecord r : records) {
                arr.put(r.toJson());
            }
            return ContactStore.writeFileAtomic(file, arr.toString());
        } catch (JSONException e) {
            Log.w(TAG, "Could not serialise recents", e);
            return false;
        }
    }

    /** Snapshot, newest first. */
    synchronized List<CallRecord> getAll() {
        return new ArrayList<>(records);
    }

    synchronized void add(CallRecord record) {
        if (record == null) {
            return;
        }
        records.add(0, record);
        while (records.size() > MAX_ENTRIES) {
            records.remove(records.size() - 1);
        }
        save();
    }

    /** Removes exactly this entry (identity comparison). */
    synchronized void remove(CallRecord record) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i) == record) {
                records.remove(i);
                save();
                return;
            }
        }
    }

    synchronized void clear() {
        records.clear();
        save();
    }
}
