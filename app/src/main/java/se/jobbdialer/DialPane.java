package se.jobbdialer;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.util.List;

/**
 * "Ring" pane: number field with the app's own keypad, live preview of the dialed numbers,
 * up to three matching contacts and the three call buttons. Placing the call (and recording
 * it in the history / call log cleanup) is done by {@link Dialer}.
 */
final class DialPane {

    private static final int MAX_MATCHES = 3;

    private static final int[] DIGIT_KEY_IDS = {
            R.id.key_0, R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4,
            R.id.key_5, R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9};

    private final MainActivity activity;
    private final EditText numberField;
    private final TextView preview;
    private final TextView[] matchRows;
    private final View callInternal;
    private final View callPatient;
    private final TextView switchboardSub;

    DialPane(MainActivity activity, View root) {
        this.activity = activity;
        numberField = root.findViewById(R.id.number_field);
        preview = root.findViewById(R.id.preview);
        matchRows = new TextView[]{
                root.findViewById(R.id.match_1),
                root.findViewById(R.id.match_2),
                root.findViewById(R.id.match_3)};
        callInternal = root.findViewById(R.id.call_internal);
        callPatient = root.findViewById(R.id.call_patient);
        switchboardSub = root.findViewById(R.id.switchboard_sub);

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

        callInternal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.getDialer().call(Dialer.CallMode.INTERNAL, input(), null);
            }
        });
        callPatient.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.getDialer().call(Dialer.CallMode.PATIENT, input(), null);
            }
        });
        root.findViewById(R.id.call_switchboard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.getDialer().callSwitchboard();
            }
        });

        refresh();
    }

    /** Re-reads settings (prefixes, switchboard) and contacts. */
    void refresh() {
        String sb = Numbers.clean(activity.getPrefs().getSwitchboardNumber());
        switchboardSub.setText(sb.isEmpty()
                ? activity.getString(R.string.switchboard_not_set)
                : Numbers.formatForDisplay(sb));
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
        boolean empty = raw.isEmpty();
        callInternal.setEnabled(!empty);
        callPatient.setEnabled(!empty);

        Prefs prefs = activity.getPrefs();
        if (empty) {
            preview.setVisibility(View.GONE);
        } else {
            preview.setText(activity.getString(R.string.preview_format,
                    Numbers.previewWithPrefix(prefs.getPrefixInternal(), raw, true),
                    Numbers.previewWithPrefix(prefs.getPrefixPatient(), raw)));
            preview.setVisibility(View.VISIBLE);
        }
        updateMatches(raw);
    }

    private void updateMatches(String raw) {
        List<ContactStore.NumberMatch> matches = ContactStore.get(activity).matchNumber(raw, MAX_MATCHES);
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
