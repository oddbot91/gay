package se.jobbdialer;

import org.json.JSONException;
import org.json.JSONObject;

/** One entry of the app's own call history ("Senaste"). Immutable. */
final class CallRecord {

    private static final String J_TS = "timestamp";
    private static final String J_DIALED = "dialedNumber";
    private static final String J_RAW = "rawInput";
    private static final String J_MODE = "mode";
    private static final String J_NAME = "contactName";

    /** Wall clock time (ms) when the call was placed. */
    final long timestamp;
    /** The full number that was dialed, including any prefix. Redial uses it as-is. */
    final String dialedNumber;
    /** What the user typed or tapped (without prefix). */
    final String rawInput;
    final Dialer.CallMode mode;
    /** Contact name looked up at call time; null when unknown. */
    final String contactName;

    CallRecord(long timestamp, String dialedNumber, String rawInput, Dialer.CallMode mode, String contactName) {
        this.timestamp = timestamp;
        this.dialedNumber = dialedNumber == null ? "" : dialedNumber;
        this.rawInput = rawInput == null ? "" : rawInput;
        this.mode = mode == null ? Dialer.CallMode.INTERNAL : mode;
        this.contactName = (contactName == null || contactName.trim().isEmpty()) ? null : contactName.trim();
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put(J_TS, timestamp);
        o.put(J_DIALED, dialedNumber);
        o.put(J_RAW, rawInput);
        o.put(J_MODE, mode.name());
        if (contactName != null) {
            o.put(J_NAME, contactName);
        }
        return o;
    }

    /** Lenient parser; returns null for entries without a dialed number. */
    static CallRecord fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        String dialed = o.optString(J_DIALED, "");
        if (dialed.isEmpty()) {
            return null;
        }
        String name = o.isNull(J_NAME) ? null : o.optString(J_NAME, null);
        return new CallRecord(
                o.optLong(J_TS, 0L),
                dialed,
                o.optString(J_RAW, ""),
                Dialer.CallMode.parse(o.optString(J_MODE, null)),
                name);
    }
}
