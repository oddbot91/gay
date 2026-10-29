package se.jobbdialer;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;

/**
 * "Ring" pane: number field with the app's own keypad (with T9 letters), up to five matching
 * contacts (T9 name matches first, then number matches; see {@link ContactMatcher}), a mode
 * selector (Internt / Patient) and a bottom row with the single call button and backspace.
 * With an empty number field the call button calls the switchboard; otherwise it calls the
 * typed number in the selected mode. Placing the call (and recording it in the history / call
 * log cleanup) is done by {@link Dialer}.
 */
final class DialPane {

    private static final int MAX_MATCHES = 5;

    private static final int[] DIGIT_KEY_IDS = {
            R.id.key_0, R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4,
            R.id.key_5, R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9};

    private final MainActivity activity;
    private final EditText numberField;
    /** Holds the suggestion rows (row_match.xml), created in code; GONE when there are none. */
    private final LinearLayout matchContainer;
    private final TextView[] segments;
    private final Dialer.CallMode[] segmentModes = {
            Dialer.CallMode.INTERNAL, Dialer.CallMode.PATIENT};
    private final View callButton;
    private final TextView callTitle;
    private final TextView callSub;
    private Dialer.CallMode mode;

    DialPane(MainActivity activity, View root) {
        this.activity = activity;
        numberField = root.findViewById(R.id.number_field);
        matchContainer = root.findViewById(R.id.match_container);
        segments = new TextView[]{
                root.findViewById(R.id.seg_internal),
                root.findViewById(R.id.seg_patient)};
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
                // The framework already gives LONG_PRESS haptic feedback for a handled long click.
                numberField.setText("");
                return true;
            }
        });

        callButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Numbers.clean(input()).isEmpty()) {
                    // Empty field: the call button is the switchboard button.
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
        int colorRes;
        if (raw.isEmpty()) {
            // Empty field: calls the switchboard (enabled only when a number is configured).
            titleRes = R.string.call_switchboard;
            String sb = Dialer.buildNumber(prefs, Dialer.CallMode.SWITCHBOARD, null);
            enabled = !sb.isEmpty();
            sub = enabled ? Numbers.formatForDisplay(sb) : activity.getString(R.string.switchboard_not_set);
            colorRes = Dialer.CallMode.SWITCHBOARD.colorResId;
        } else {
            titleRes = mode == Dialer.CallMode.PATIENT ? R.string.call_patient : R.string.call_internal;
            String number = Dialer.buildNumber(prefs, mode, raw);
            enabled = !number.isEmpty();
            sub = Numbers.formatForDisplay(number);
            colorRes = mode.colorResId;
        }
        callTitle.setText(titleRes);
        callSub.setText(sub);
        callButton.setEnabled(enabled);
        callButton.setBackgroundTintList(ColorStateList.valueOf(
                activity.getColor(enabled ? colorRes : R.color.btn_disabled)));
    }

    /**
     * Shows up to {@link #MAX_MATCHES} keypad matches (all matching logic is in ContactMatcher).
     * The rows are inflated from row_match.xml and (re)created on every change, so nothing depends
     * on pre-existing views or style inheritance.
     */
    private void updateMatches(String raw) {
        List<ContactMatcher.Match> matches = raw.isEmpty()
                ? Collections.<ContactMatcher.Match>emptyList()
                : ContactMatcher.match(
                        ContactStore.get(activity).getAll(ContactStore.SortMode.LAST_NAME), raw, MAX_MATCHES);
        if (matchContainer == null) {
            return;
        }
        matchContainer.removeAllViews();
        if (matches.isEmpty()) {
            matchContainer.setVisibility(View.GONE);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (final ContactMatcher.Match m : matches) {
            TextView row = (TextView) inflater.inflate(R.layout.row_match, matchContainer, false);
            String displayName = m.contact.displayName();
            final String name = displayName.isEmpty() ? activity.getString(R.string.contact_unnamed) : displayName;
            if (m.hasNumber()) {
                row.setText(activity.getString(m.work ? R.string.match_work : R.string.match_private,
                        name, Numbers.formatForDisplay(m.number)));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        numberField.setText(Numbers.clean(m.number));
                        numberField.setSelection(numberField.length());
                    }
                });
            } else {
                // Name found but the contact has no number: show it, explain on tap.
                row.setText(activity.getString(R.string.match_no_number, name));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Toast.makeText(activity, activity.getString(R.string.toast_contact_no_number, name),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
            // The container sits above the keypad and grows upwards: insert at the top so the
            // best match (first in the list) ends up as the bottom row, closest to the thumb.
            matchContainer.addView(row, 0);
        }
        matchContainer.setVisibility(View.VISIBLE);
    }
}
