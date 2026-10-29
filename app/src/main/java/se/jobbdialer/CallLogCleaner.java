package se.jobbdialer;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional, best-effort removal of the app's own calls from the system call log
 * (SPEC P1 item 6). Android always logs calls; the only way to hide them is to delete the
 * entries afterwards.
 *
 * <p>Every call placed by the app (while the setting is on) is stored as a "pending" entry
 * {number, timestamp} in SharedPreferences. {@link #sweep(Context)} looks for matching outgoing
 * calls in CallLog.Calls and deletes them; it runs from {@link CallLogCleanupJobService}
 * (triggered by call log changes) and from MainActivity.onResume(). Never throws.
 */
final class CallLogCleaner {

    private static final String TAG = "Jobbdialer";

    static final int JOB_ID = 3101;

    /** Pending entries older than this are dropped. */
    static final long PENDING_MAX_AGE_MS = 6L * 60L * 60L * 1000L;
    /** Tolerance between the time the app placed the call and the call log DATE column. */
    private static final long DATE_TOLERANCE_MS = 120_000L;
    /** Upper bound for pending entries (protects SharedPreferences from growing unbounded). */
    private static final int MAX_PENDING = 100;

    private static final String J_NUMBER = "number";
    private static final String J_TS = "ts";

    private static final Object LOCK = new Object();

    private CallLogCleaner() {
    }

    /** One call waiting to be removed from the call log. */
    private static final class Pending {
        final String number;
        final long ts;

        Pending(String number, long ts) {
            this.number = number;
            this.ts = ts;
        }
    }

    /** One outgoing call log row. */
    private static final class Row {
        final long id;
        final String number;
        final long date;

        Row(long id, String number, long date) {
            this.id = id;
            this.number = number;
            this.date = date;
        }
    }

    // ============================================================================ API

    /** True when both READ_CALL_LOG and WRITE_CALL_LOG are granted. */
    static boolean hasPermissions(Context ctx) {
        try {
            return ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
                    && ctx.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            Log.w(TAG, "Permission check failed", e);
            return false;
        }
    }

    /** Registers a call that was just placed (timestamp = now). */
    static void addPending(Context ctx, String number) {
        addPending(ctx, number, System.currentTimeMillis());
    }

    /** Registers a call placed at {@code timestamp} (ms since epoch). */
    static void addPending(Context ctx, String number, long timestamp) {
        String n = Numbers.clean(number);
        if (n.isEmpty()) {
            return;
        }
        try {
            synchronized (LOCK) {
                Prefs prefs = new Prefs(ctx);
                List<Pending> list = load(prefs);
                list.add(new Pending(n, timestamp));
                while (list.size() > MAX_PENDING) {
                    list.remove(0);
                }
                store(prefs, list);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "addPending failed", e);
        }
    }

    /** Forgets all pending entries (used when the setting is switched off). */
    static void clearPending(Context ctx) {
        try {
            synchronized (LOCK) {
                store(new Prefs(ctx), new ArrayList<Pending>());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "clearPending failed", e);
        }
    }

    /** True when there are pending entries that have not expired yet. */
    static boolean hasPending(Context ctx) {
        try {
            synchronized (LOCK) {
                long now = System.currentTimeMillis();
                for (Pending p : load(new Prefs(ctx))) {
                    if (!isExpired(p, now)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "hasPending failed", e);
        }
        return false;
    }

    /**
     * Deletes matching outgoing calls from the call log and updates the pending list.
     *
     * @return true when entries are still pending (the cleanup job should stay scheduled).
     */
    static boolean sweep(Context ctx) {
        try {
            synchronized (LOCK) {
                return sweepLocked(ctx.getApplicationContext());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Call log sweep failed", e);
            return false;
        }
    }

    /** Schedules (or replaces) the content-triggered cleanup job. */
    static void scheduleJob(Context ctx) {
        try {
            JobScheduler js = ctx.getSystemService(JobScheduler.class);
            if (js == null) {
                return;
            }
            JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, CallLogCleanupJobService.class))
                    .addTriggerContentUri(new JobInfo.TriggerContentUri(
                            CallLog.Calls.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    .setTriggerContentUpdateDelay(2000)
                    .setTriggerContentMaxDelay(10000)
                    .setOverrideDeadline(10 * 60 * 1000)
                    .build();
            int result = js.schedule(info);
            if (result != JobScheduler.RESULT_SUCCESS) {
                Log.w(TAG, "Scheduling the call log cleanup job failed: " + result);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not schedule the call log cleanup job", e);
        }
    }

    /** Cancels the cleanup job. */
    static void cancelJob(Context ctx) {
        try {
            JobScheduler js = ctx.getSystemService(JobScheduler.class);
            if (js != null) {
                js.cancel(JOB_ID);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not cancel the call log cleanup job", e);
        }
    }

    // ============================================================================ sweep

    private static boolean sweepLocked(Context ctx) {
        Prefs prefs = new Prefs(ctx);
        List<Pending> all = load(prefs);
        if (all.isEmpty()) {
            return false;
        }
        if (!prefs.isHideInCallLog()) {
            store(prefs, new ArrayList<Pending>());
            return false;
        }
        long now = System.currentTimeMillis();
        List<Pending> pending = new ArrayList<>();
        for (Pending p : all) {
            if (!isExpired(p, now)) {
                pending.add(p);
            }
        }
        if (pending.isEmpty()) {
            store(prefs, pending);
            return false;
        }
        if (!hasPermissions(ctx)) {
            // Nothing can be done without the permissions; keep the entries until they expire,
            // but do not keep the job alive for them.
            if (pending.size() != all.size()) {
                store(prefs, pending);
            }
            return false;
        }

        Collections.sort(pending, new Comparator<Pending>() {
            @Override
            public int compare(Pending a, Pending b) {
                return Long.compare(a.ts, b.ts);
            }
        });
        long minDate = pending.get(0).ts - DATE_TOLERANCE_MS;
        List<Row> rows = queryOutgoing(ctx.getContentResolver(), minDate);
        if (rows == null) {
            // Query failed: keep everything, retry later.
            store(prefs, pending);
            return true;
        }

        // Greedy one-to-one matching: each pending call claims the oldest matching row that was
        // logged after it was placed (minus the tolerance).
        Set<Long> claimed = new HashSet<>();
        List<Pending> remaining = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        for (Pending p : pending) {
            Row match = null;
            for (Row r : rows) {
                if (claimed.contains(r.id) || r.date < p.ts - DATE_TOLERANCE_MS) {
                    continue;
                }
                if (Numbers.numbersMatch(r.number, p.number)) {
                    match = r;
                    break;
                }
            }
            if (match == null) {
                remaining.add(p);
                continue;
            }
            claimed.add(match.id);
            if (!deleteRow(cr, match.id)) {
                remaining.add(p);
            }
        }
        store(prefs, remaining);
        return !remaining.isEmpty();
    }

    /** Outgoing calls with DATE >= minDate, oldest first. Null when the query failed. */
    private static List<Row> queryOutgoing(ContentResolver cr, long minDate) {
        String[] projection = {CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE};
        String selection = CallLog.Calls.TYPE + " = ? AND " + CallLog.Calls.DATE + " >= ?";
        String[] args = {String.valueOf(CallLog.Calls.OUTGOING_TYPE), String.valueOf(minDate)};
        List<Row> rows = new ArrayList<>();
        try (Cursor c = cr.query(CallLog.Calls.CONTENT_URI, projection, selection, args,
                CallLog.Calls.DATE + " ASC")) {
            if (c == null) {
                return null;
            }
            int iId = c.getColumnIndex(CallLog.Calls._ID);
            int iNumber = c.getColumnIndex(CallLog.Calls.NUMBER);
            int iDate = c.getColumnIndex(CallLog.Calls.DATE);
            if (iId < 0 || iNumber < 0 || iDate < 0) {
                return null;
            }
            while (c.moveToNext()) {
                rows.add(new Row(c.getLong(iId), c.getString(iNumber), c.getLong(iDate)));
            }
            return rows;
        } catch (RuntimeException e) {
            Log.w(TAG, "Call log query failed", e);
            return null;
        }
    }

    /**
     * Deletes one call log row by _ID. The CallLogProvider only supports deletes on the
     * collection URI (a "calls/<id>" URI throws UnsupportedOperationException on AOSP), so the
     * id is passed as a selection instead.
     */
    private static boolean deleteRow(ContentResolver cr, long id) {
        try {
            int n = cr.delete(CallLog.Calls.CONTENT_URI, CallLog.Calls._ID + " = ?",
                    new String[]{String.valueOf(id)});
            return n > 0;
        } catch (RuntimeException e) {
            Log.w(TAG, "Call log delete failed for id " + id, e);
            return false;
        }
    }

    // ============================================================================ storage

    private static boolean isExpired(Pending p, long now) {
        // Entries "from the future" (clock changed) are treated like fresh ones until they age.
        return now - p.ts > PENDING_MAX_AGE_MS;
    }

    private static List<Pending> load(Prefs prefs) {
        List<Pending> list = new ArrayList<>();
        String json = prefs.getPendingCleanupJson();
        if (json == null || json.trim().isEmpty()) {
            return list;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String n = o.optString(J_NUMBER, "");
                long ts = o.optLong(J_TS, 0L);
                if (!n.isEmpty() && ts > 0) {
                    list.add(new Pending(n, ts));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Pending cleanup list is corrupt; discarding it", e);
        }
        return list;
    }

    private static void store(Prefs prefs, List<Pending> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Pending p : list) {
                JSONObject o = new JSONObject();
                o.put(J_NUMBER, p.number);
                o.put(J_TS, p.ts);
                arr.put(o);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Could not serialise pending cleanup list", e);
        }
        prefs.setPendingCleanupJson(arr.toString());
    }
}
