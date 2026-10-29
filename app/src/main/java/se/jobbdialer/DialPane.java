package se.jobbdialer;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Ring" pane: number field with the app's own keypad (with T9 letters), up to four matching
 * contacts (number or T9 name match), a mode selector (Internt / Patient / Växeln) and a single
 * call button. Placing the call (and recording it in the history / call log cleanup) is done
 * by {@link Dialer}.
 */
final class DialPane {

    private static final int MAX_MATCHES = 4;

    private static final int[] DIGIT_KEY_IDS = {
            R.id.key_0, R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4,
            R.id.key_5, R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9};

    private final MainActivity activity;
    private final EditText numberField;
    private final TextView[] matchRows;
    private final TextView[] segments;
    private final Dialer.CallMode[] segmentModes = {
            Dialer.CallMode.INTERNAL, Dialer.CallMode.PATIENT, Dialer.CallMode.SWITCHBOARD};
    private final View callButton;
    private final TextView callTitle;
    private final TextView callSub;
    private Dialer.CallMode mode;

    DialPane(MainActivity activity, View root) {
        this.activity = activity;
        numberField = root.findViewById(R.id.number_field);
        matchRows = new TextView[]{
                root.findViewById(R.id.match_1),
                root.findViewById(R.id.match_2),
                root.findViewById(R.id.match_3),
                root.findViewById(R.id.match_4)};
        segments = new TextView[]{
                root.findViewById(R.id.seg_internal),
                root.findViewById(R.id.seg_patient),
                root.findViewById(R.id.seg_switchboard)};
        callButton = root.findViewById(R.id.call_button);
        callTitle = root.findViewById(R.id.call_title);
        callSub = root.findViewById(R.id.call_sub);
        mode = activity.getPrefs().getDialMode();

        for (int i = 0; i < segments.length; i++) {
            final Dialer.CallMode m = segmentModes[i];
            segments[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mode = m;
                    activity.getPrefs().setDialMode(m);
                    updateForInput();
                }
            });
        }

        // Own keypad: never pop up the soft keyboard, but keep cursor / paste working.
        numberField.setShowSoftInputOnFocus(false);
        numberField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateForInput();
            }
        });

        for (int i = 0; i < DIGIT_KEY_IDS.length; i++) {
            bindKey(root.findViewById(DIGIT_KEY_IDS[i]), String.valueOf(i));
        }
        bindKey(root.findViewById(R.id.key_star), "*");
        bindKey(root.findViewById(R.id.key_hash), "#");
        root.findViewById(R.id.key_0).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                insert("+");
                return true;
            }
        });

        View backspace = root.findViewById(R.id.backspace);
        backspace.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                backspace();
            }
        });
        backspace.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                numberField.setText("");
                return true;
            }
        });

        callButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mode == Dialer.CallMode.SWITCHBOARD) {
                    activity.getDialer().callSwitchboard();
                } else {
                    activity.getDialer().call(mode, input(), null);
                }
            }
        });

        refresh();
    }

    /** Re-reads settings (prefixes, switchboard, mode) and contacts. */
    void refresh() {
        mode = activity.getPrefs().getDialMode();
        updateForInput();
    }

    private String input() {
        Editable e = numberField.getText();
        return e == null ? "" : e.toString();
    }

    private void bindKey(View key, final String text) {
        key.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                insert(text);
            }
        });
    }

    /** Inserts at the cursor (replacing a selection); appends when there is no cursor. */
    private void insert(String text) {
        Editable e = numberField.getText();
        if (e == null) {
            numberField.setText(text);
            numberField.setSelection(numberField.length());
            return;
        }
        int start = numberField.getSelectionStart();
        int end = numberField.getSelectionEnd();
        if (start < 0 || end < 0) {
            start = e.length();
            end = e.length();
        }
        e.replace(Math.min(start, end), Math.max(start, end), text);
    }

    private void backspace() {
        Editable e = numberField.getText();
        if (e == null || e.length() == 0) {
            return;
        }
        int start = numberField.getSelectionStart();
        int end = numberField.getSelectionEnd();
        if (start < 0 || end < 0) {
            start = e.length();
            end = e.length();
        }
        int from = Math.min(start, end);
        int to = Math.max(start, end);
        if (from != to) {
            e.delete(from, to);
        } else if (from > 0) {
            e.delete(from - 1, from);
        }
    }

    private void updateForInput() {
        String raw = Numbers.clean(input());
        updateModeAndCallButton(raw);
        updateMatches(raw);
    }

    /** Tints the mode selector and the call button and sets the call button texts. */
    private void updateModeAndCallButton(String raw) {
        for (int i = 0; i < segments.length; i++) {
            int color = activity.getColor(segmentModes[i].colorResId);
            boolean selected = segmentModes[i] == mode;
            segments[i].setBackgroundTintList(ColorStateList.valueOf(selected ? color : Color.TRANSPARENT));
            segments[i].setTextColor(selected ? Color.WHITE : color);
        }

        Prefs prefs = activity.getPrefs();
        boolean enabled;
        String sub;
        int titleRes;
        if (mode == Dialer.CallMode.SWITCHBOARD) {
            titleRes = R.string.call_switchboard;
            String sb = Dialer.buildNumber(prefs, mode, null);
            sub = sb.isEmpty() ? activity.getString(R.string.switchboard_not_set) : Numbers.formatForDisplay(sb);
            enabled = true;
        } else {
            titleRes = mode == Dialer.CallMode.PATIENT ? R.string.call_patient : R.string.call_internal;
            enabled = !raw.isEmpty();
            sub = enabled
                    ? Numbers.formatForDisplay(Dialer.buildNumber(prefs, mode, raw))
                    : activity.getString(R.string.call_hint_enter_number);
        }
        callTitle.setText(titleRes);
        callSub.setText(sub);
        callButton.setEnabled(enabled);
        callButton.setBackgroundTintList(ColorStateList.valueOf(
                enabled ? activity.getColor(mode.colorResId) : activity.getColor(R.color.btn_disabled)));
    }

    private void updateMatches(String raw) {
        String digits = Numbers.digitsOnly(raw);
        List<ContactStore.NumberMatch> matches = new ArrayList<>();
        if (!digits.isEmpty()) {
            ContactStore store = ContactStore.get(activity);
            Set<String> seen = new HashSet<>();
            // (a) number matches first: work then private numbers containing the digits.
            for (ContactStore.NumberMatch m : store.matchNumber(raw, MAX_MATCHES)) {
                if (matches.size() < MAX_MATCHES && seen.add(m.contact.id + (m.work ? "w" : "p"))) {
                    matches.add(m);
                }
            }
            for (ContactStore.NumberMatch m : matches) {
                seen.add(m.contact.id);
            }
            // (b) T9 name matches, one row per contact not already listed.
            if (digits.length() >= 2) {
                for (Contact c : store.getAll(ContactStore.SortMode.LAST_NAME)) {
                    if (matches.size() >= MAX_MATCHES) {
                        break;
                    }
                    if (seen.contains(c.id) || !T9.matchesName(c.firstName, c.lastName, digits)) {
                        continue;
                    }
                    boolean work = !Numbers.isEmpty(c.workNumber);
                    String number = work ? c.workNumber : c.privateNumber;
                    if (Numbers.isEmpty(number)) {
                        continue;
                    }
                    seen.add(c.id);
                    matches.add(new ContactStore.NumberMatch(c, work, number));
                }
            }
        }
        for (int i = 0; i < matchRows.length; i++) {
            TextView row = matchRows[i];
            if (i >= matches.size()) {
                row.setVisibility(View.GONE);
                row.setOnClickListener(null);
                row.setClickable(false);
                continue;
            }
            final ContactStore.NumberMatch m = matches.get(i);
            String name = m.contact.displayName();
            if (name.isEmpty()) {
                name = activity.getString(R.string.contact_unnamed);
            }
            row.setText(activity.getString(m.work ? R.string.match_work : R.string.match_private,
                    name, Numbers.formatForDisplay(m.number)));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    numberField.setText(Numbers.clean(m.number));
                    numberField.setSelection(numberField.length());
                }
            });
            row.setVisibility(View.VISIBLE);
        }
    }
}
