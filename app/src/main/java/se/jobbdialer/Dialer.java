package se.jobbdialer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * Builds the number for a call mode and places the call.
 *
 * <p>Calls are placed directly with {@code ACTION_CALL} and {@code Uri.fromParts("tel", n, null)}
 * (which URL-encodes '#', required for "#31#"). Needs the runtime permission CALL_PHONE: it is
 * requested on the first call and the call is placed as soon as it is granted. When the
 * permission is denied or the direct call fails, the system dialer is opened with the number
 * filled in ({@code ACTION_DIAL}).
 */
final class Dialer {

    private static final String TAG = "Jobbdialer";

    /** Request codes used with Activity.requestPermissions(). */
    static final int REQUEST_CALL_PHONE = 100;
    static final int REQUEST_CALL_LOG = 101;

    private static final String STATE_PENDING_NUMBER = "dialer_pending_number";
    private static final String STATE_PENDING_RAW = "dialer_pending_raw";
    private static final String STATE_PENDING_MODE = "dialer_pending_mode";
    private static final String STATE_PENDING_NAME = "dialer_pending_name";
    private static final String STATE_IN_FLIGHT = "dialer_in_flight";

    /** How a number is dialed. */
    enum CallMode {
        /** Internal call: internal prefix + number (prefix not added twice). */
        INTERNAL(R.string.mode_internal, R.color.mode_internal),
        /** Patient call: patient prefix (default #31#, hidden caller id) + number. */
        PATIENT(R.string.mode_patient, R.color.mode_patient),
        /** The switchboard number, no prefix. */
        SWITCHBOARD(R.string.mode_switchboard, R.color.mode_switchboard),
        /** A contact's private number, dialed as-is without any prefix. */
        PRIVATE(R.string.mode_private, R.color.mode_private);

        final int labelResId;
        final int colorResId;

        CallMode(int labelResId, int colorResId) {
            this.labelResId = labelResId;
            this.colorResId = colorResId;
        }

        static CallMode parse(String name) {
            if (name != null) {
                for (CallMode m : values()) {
                    if (m.name().equals(name)) {
                        return m;
                    }
                }
            }
            return INTERNAL;
        }
    }

    /** Notified after a call was handed to the phone (directly or via the system dialer). */
    interface Listener {
        void onCallPlaced(CallRecord record);
    }

    /** A call waiting for the CALL_PHONE permission. */
    private static final class PendingCall {
        final String number;
        final String rawInput;
        final CallMode mode;
        final String contactName;

        PendingCall(String number, String rawInput, CallMode mode, String contactName) {
            this.number = number;
            this.rawInput = rawInput;
            this.mode = mode;
            this.contactName = contactName;
        }
    }

    private final Activity activity;
    private final Prefs prefs;
    private Listener listener;
    private PendingCall pending;
    private boolean permissionRequestInFlight;

    Dialer(Activity activity, Prefs prefs) {
        this.activity = activity;
        this.prefs = prefs;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    // ============================================================================ numbers

    /**
     * The number that is actually dialed for {@code input} in {@code mode}:
     * INTERNAL = prefixInternal + input (unless the input already starts with the prefix or is a full number),
     * PATIENT = prefixPatient + input (likewise not doubled), SWITCHBOARD = switchboard number,
     * PRIVATE = input as-is. Spaces, dashes etc. are removed. Returns "" when there is nothing to dial.
     */
    static String buildNumber(Prefs prefs, CallMode mode, String input) {
        String n = Numbers.clean(input);
        switch (mode) {
            case INTERNAL:
                return withPrefix(prefs.getPrefixInternal(), n, true);
            case PATIENT:
                return withPrefix(prefs.getPrefixPatient(), n, false);
            case SWITCHBOARD:
                return Numbers.clean(prefs.getSwitchboardNumber());
            case PRIVATE:
            default:
                return n;
        }
    }

    private static String withPrefix(String prefix, String cleanedInput, boolean skipFullNumbers) {
        if (cleanedInput.isEmpty()) {
            return "";
        }
        if (skipFullNumbers && Numbers.isFullNumber(cleanedInput)) {
            return cleanedInput;
        }
        String p = Numbers.clean(prefix);
        if (p.isEmpty() || cleanedInput.startsWith(p)) {
            return cleanedInput;
        }
        return p + cleanedInput;
    }

    // ============================================================================ calling

    /** Builds the number for {@code mode} from {@code input} and calls it. */
    void call(CallMode mode, String input, String contactName) {
        String number = buildNumber(prefs, mode, input);
        if (number.isEmpty()) {
            toast(mode == CallMode.SWITCHBOARD ? R.string.toast_no_switchboard : R.string.toast_no_number);
            return;
        }
        String raw = mode == CallMode.SWITCHBOARD ? number : Numbers.clean(input);
        String name = contactName;
        if (name == null) {
            name = ContactStore.get(activity).findNameForCall(mode, raw, number, prefs.getPrefixInternal());
        }
        place(new PendingCall(number, raw, mode, name));
    }

    void callSwitchboard() {
        call(CallMode.SWITCHBOARD, null, null);
    }

    /** Calls the exact number of a history entry again (no re-prefixing). */
    void redial(CallRecord record) {
        if (record == null || Numbers.clean(record.dialedNumber).isEmpty()) {
            toast(R.string.toast_no_number);
            return;
        }
        place(new PendingCall(Numbers.clean(record.dialedNumber), record.rawInput, record.mode, record.contactName));
    }

    private void place(PendingCall call) {
        if (activity.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startCall(call);
            return;
        }
        pending = call;
        if (permissionRequestInFlight) {
            // A permission dialog is already showing; its result will place the latest call.
            return;
        }
        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.CALL_PHONE)) {
            showRationale();
        } else {
            requestCallPermission();
        }
    }

    private void requestCallPermission() {
        permissionRequestInFlight = true;
        activity.requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQUEST_CALL_PHONE);
    }

    private void showRationale() {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.permission_call_title)
                .setMessage(R.string.permission_call_rationale)
                .setPositiveButton(R.string.continue_action, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requestCallPermission();
                    }
                })
                .setNegativeButton(R.string.open_phone_app, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        PendingCall p = pending;
                        pending = null;
                        if (p != null) {
                            fallbackDial(p, 0);
                        }
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        pending = null;
                    }
                })
                .show();
    }

    /** Forward from Activity.onRequestPermissionsResult. Returns true if the code was ours. */
    boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_CALL_PHONE) {
            return false;
        }
        permissionRequestInFlight = false;
        if (grantResults == null || grantResults.length == 0) {
            // Request was interrupted/cancelled; keep nothing pending so nothing dials unexpectedly.
            pending = null;
            return true;
        }
        PendingCall p = pending;
        pending = null;
        if (p == null) {
            return true;
        }
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCall(p);
        } else {
            fallbackDial(p, R.string.toast_call_permission_denied);
        }
        return true;
    }

    private void startCall(PendingCall p) {
        Intent intent = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", p.number, null));
        try {
            activity.startActivity(intent);
            recordCall(p, true);
        } catch (SecurityException | ActivityNotFoundException e) {
            Log.w(TAG, "ACTION_CALL failed, falling back to ACTION_DIAL", e);
            fallbackDial(p, R.string.toast_call_fallback);
        } catch (RuntimeException e) {
            Log.w(TAG, "ACTION_CALL failed unexpectedly, falling back to ACTION_DIAL", e);
            fallbackDial(p, R.string.toast_call_fallback);
        }
    }

    /** Opens the system dialer with the number filled in. {@code toastResId} 0 = no toast. */
    private void fallbackDial(PendingCall p, int toastResId) {
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", p.number, null));
        try {
            activity.startActivity(intent);
            if (toastResId != 0) {
                toast(toastResId);
            }
            recordCall(p, false);
        } catch (RuntimeException e) {
            Log.w(TAG, "ACTION_DIAL failed", e);
            toast(R.string.toast_no_phone_app);
        }
    }

    /**
     * Adds the call to the app's history and, when the call was placed directly and the
     * "hide in call log" setting is on, registers it for removal from the system call log.
     */
    private void recordCall(PendingCall p, boolean placedDirectly) {
        long now = System.currentTimeMillis();
        CallRecord record = new CallRecord(now, p.number, p.rawInput, p.mode, p.contactName);
        try {
            RecentsStore.get(activity).add(record);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not store call history entry", e);
        }
        // Only direct calls are registered: with the ACTION_DIAL fallback the user may never
        // press "call", and a stale entry could otherwise remove a later, unrelated call.
        if (placedDirectly && prefs.isHideInCallLog()) {
            try {
                if (CallLogCleaner.hasPermissions(activity)) {
                    CallLogCleaner.addPending(activity, p.number, now);
                    CallLogCleaner.scheduleJob(activity);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not register call for call log cleanup", e);
            }
        }
        if (listener != null) {
            listener.onCallPlaced(record);
        }
    }

    private void toast(int resId) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show();
    }

    // ============================================================================ state

    void saveState(Bundle out) {
        PendingCall p = pending;
        out.putBoolean(STATE_IN_FLIGHT, permissionRequestInFlight);
        if (p != null) {
            out.putString(STATE_PENDING_NUMBER, p.number);
            out.putString(STATE_PENDING_RAW, p.rawInput);
            out.putString(STATE_PENDING_MODE, p.mode.name());
            out.putString(STATE_PENDING_NAME, p.contactName);
        }
    }

    void restoreState(Bundle in) {
        if (in == null) {
            return;
        }
        permissionRequestInFlight = in.getBoolean(STATE_IN_FLIGHT, false);
        String number = in.getString(STATE_PENDING_NUMBER);
        if (number != null && !number.isEmpty()) {
            pending = new PendingCall(number,
                    in.getString(STATE_PENDING_RAW, ""),
                    CallMode.parse(in.getString(STATE_PENDING_MODE)),
                    in.getString(STATE_PENDING_NAME));
        }
    }
}
