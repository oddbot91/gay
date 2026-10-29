package se.jobbdialer;

import android.content.Context;
import android.content.SharedPreferences;

/** Thin wrapper around the app's SharedPreferences ("jobbdialer_prefs"). */
final class Prefs {

    static final String FILE_NAME = "jobbdialer_prefs";

    static final String KEY_PREFIX_INTERNAL = "prefix_internal";
    static final String KEY_PREFIX_PATIENT = "prefix_patient";
    static final String KEY_SWITCHBOARD = "switchboard_number";
    static final String KEY_CONTACT_SORT = "contact_sort";
    static final String KEY_HIDE_IN_CALL_LOG = "hide_in_call_log";
    static final String KEY_PENDING_CLEANUP = "pending_cleanup_json";

    static final String DEFAULT_PREFIX_INTERNAL = "03134";
    static final String DEFAULT_PREFIX_PATIENT = "#31#";
    static final String DEFAULT_SWITCHBOARD = "0313427000";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ numbers

    String getPrefixInternal() {
        return getString(KEY_PREFIX_INTERNAL, DEFAULT_PREFIX_INTERNAL);
    }

    void setPrefixInternal(String value) {
        putString(KEY_PREFIX_INTERNAL, value);
    }

    String getPrefixPatient() {
        return getString(KEY_PREFIX_PATIENT, DEFAULT_PREFIX_PATIENT);
    }

    void setPrefixPatient(String value) {
        putString(KEY_PREFIX_PATIENT, value);
    }

    String getSwitchboardNumber() {
        return getString(KEY_SWITCHBOARD, DEFAULT_SWITCHBOARD);
    }

    void setSwitchboardNumber(String value) {
        putString(KEY_SWITCHBOARD, value);
    }

    /** Restores prefixes and switchboard number to the defaults from the spec. */
    void resetNumbers() {
        sp.edit()
                .putString(KEY_PREFIX_INTERNAL, DEFAULT_PREFIX_INTERNAL)
                .putString(KEY_PREFIX_PATIENT, DEFAULT_PREFIX_PATIENT)
                .putString(KEY_SWITCHBOARD, DEFAULT_SWITCHBOARD)
                .apply();
    }

    // ------------------------------------------------------------------ contacts

    ContactStore.SortMode getContactSort() {
        return ContactStore.SortMode.parse(sp.getString(KEY_CONTACT_SORT, null));
    }

    void setContactSort(ContactStore.SortMode mode) {
        if (mode != null) {
            sp.edit().putString(KEY_CONTACT_SORT, mode.name()).apply();
        }
    }

    // ------------------------------------------------------------------ call log cleanup

    boolean isHideInCallLog() {
        return sp.getBoolean(KEY_HIDE_IN_CALL_LOG, false);
    }

    void setHideInCallLog(boolean value) {
        sp.edit().putBoolean(KEY_HIDE_IN_CALL_LOG, value).apply();
    }

    String getPendingCleanupJson() {
        return sp.getString(KEY_PENDING_CLEANUP, "[]");
    }

    /** Written synchronously: also used from the JobService thread right before jobFinished(). */
    void setPendingCleanupJson(String json) {
        sp.edit().putString(KEY_PENDING_CLEANUP, json == null ? "[]" : json).commit();
    }

    // ------------------------------------------------------------------ helpers

    private String getString(String key, String def) {
        try {
            String v = sp.getString(key, null);
            return v == null ? def : v;
        } catch (ClassCastException e) {
            return def;
        }
    }

    private void putString(String key, String value) {
        sp.edit().putString(key, value == null ? "" : value.trim()).apply();
    }
}
