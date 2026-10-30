package se.jobbdialer;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * "Senaste" pane: the app's own call history. Tap = put the number that was actually dialed
 * (incl. a possible #31#) into the dial pane, nothing is called yet. Long-press = call the exact
 * same full number again right away (no re-prefixing) / remove the entry / clear everything.
 */
final class RecentsPane {

    private static final long JUST_NOW_MS = DateUtils.MINUTE_IN_MILLIS;

    private final MainActivity activity;
    private final RecentsAdapter adapter = new RecentsAdapter();

    RecentsPane(MainActivity activity, View root) {
        this.activity = activity;
        ListView list = root.findViewById(R.id.recents_list);
        list.setAdapter(adapter);
        list.setEmptyView(root.findViewById(R.id.recents_empty));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                CallRecord r = adapter.getItem(position);
                if (r != null) {
                    RecentsPane.this.activity.openDialerWith(r.dialedNumber);
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                CallRecord r = adapter.getItem(position);
                if (r != null) {
                    showMenu(r);
                }
                return true;
            }
        });
        refresh();
    }

    void refresh() {
        adapter.setItems(RecentsStore.get(activity).getAll());
    }

    private void showMenu(final CallRecord r) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        String[] items = {
                activity.getString(R.string.action_redial_direct),
                activity.getString(R.string.action_delete),
                activity.getString(R.string.action_clear_all)};
        new AlertDialog.Builder(activity)
                .setTitle(title(r))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            // Dialer.redial adds a new history entry itself (via recordCall).
                            activity.getDialer().redial(r);
                        } else if (which == 1) {
                            RecentsStore.get(activity).remove(r);
                            refresh();
                        } else if (which == 2) {
                            confirmClear();
                        }
                    }
                })
                .show();
    }

    private void confirmClear() {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setMessage(R.string.confirm_clear_recents)
                .setPositiveButton(R.string.action_clear, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        RecentsStore.get(activity).clear();
                        refresh();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Contact name when known, otherwise the formatted number. */
    private static String title(CallRecord r) {
        return r.contactName != null ? r.contactName : Numbers.formatForDisplay(r.dialedNumber);
    }

    private CharSequence relativeTime(long ts) {
        long now = System.currentTimeMillis();
        if (ts <= 0) {
            return "";
        }
        if (Math.abs(now - ts) < JUST_NOW_MS) {
            return activity.getString(R.string.time_just_now);
        }
        return DateUtils.getRelativeTimeSpanString(ts, now, DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE);
    }

    // ============================================================================ adapter

    private static final class Holder {
        ImageView icon;
        TextView line1;
        TextView line2;
    }

    private final class RecentsAdapter extends BaseAdapter {

        private List<CallRecord> items = new ArrayList<>();

        void setItems(List<CallRecord> list) {
            items = list == null ? new ArrayList<CallRecord>() : list;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public CallRecord getItem(int position) {
            return position >= 0 && position < items.size() ? items.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            Holder h;
            if (row == null) {
                row = LayoutInflater.from(activity).inflate(R.layout.row_recent, parent, false);
                h = new Holder();
                h.icon = row.findViewById(R.id.recent_icon);
                h.line1 = row.findViewById(R.id.recent_line1);
                h.line2 = row.findViewById(R.id.recent_line2);
                row.setTag(h);
            } else {
                h = (Holder) row.getTag();
            }
            CallRecord r = items.get(position);
            h.icon.setImageTintList(activity.getColorStateList(r.mode.colorResId));
            h.line1.setText(title(r));
            String mode = activity.getString(r.mode.labelResId);
            CharSequence when = relativeTime(r.timestamp);
            if (r.contactName != null) {
                h.line2.setText(activity.getString(R.string.recent_detail_with_number,
                        mode, Numbers.formatForDisplay(r.dialedNumber), when));
            } else {
                h.line2.setText(activity.getString(R.string.recent_detail, mode, when));
            }
            return row;
        }
    }
}
