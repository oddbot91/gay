package se.jobbdialer;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * "Kontakter" pane: search, sortable list, add/edit/delete and the long-press menu.
 * Tapping "Jobb ..." calls internally (with prefix), tapping "Privat ..." calls the private
 * number as-is.
 */
final class ContactsPane {

    private final MainActivity activity;
    private final EditText searchField;
    private final TextView sortButton;
    private final TextView emptyText;
    private final ContactAdapter adapter = new ContactAdapter();

    ContactsPane(MainActivity activity, View root) {
        this.activity = activity;
        searchField = root.findViewById(R.id.search_field);
        sortButton = root.findViewById(R.id.sort_button);
        emptyText = root.findViewById(R.id.contacts_empty);
        ListView list = root.findViewById(R.id.contacts_list);
        list.setAdapter(adapter);
        list.setEmptyView(emptyText);

        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refresh();
            }
        });
        sortButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortDialog();
            }
        });
        root.findViewById(R.id.fab_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditDialog(null);
            }
        });
        refresh();
    }

    /** Reloads the list (search text and sort order applied). */
    void refresh() {
        Prefs prefs = activity.getPrefs();
        ContactStore.SortMode sort = prefs.getContactSort();
        sortButton.setText(activity.getString(R.string.sort_button, activity.getString(sort.labelResId)));
        ContactStore store = ContactStore.get(activity);
        Editable q = searchField.getText();
        adapter.setItems(store.search(q == null ? "" : q.toString(), sort));
        emptyText.setText(store.isEmpty() ? R.string.contacts_empty : R.string.contacts_no_match);
    }

    private boolean canShowDialog() {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private static String nameOrNull(Contact c) {
        String n = c.displayName();
        return n.isEmpty() ? null : n;
    }

    private String nameForDisplay(Contact c) {
        String n = c.displayName();
        return n.isEmpty() ? activity.getString(R.string.contact_unnamed) : n;
    }

    // ============================================================================ calls

    private void callWork(Contact c) {
        activity.getDialer().call(Dialer.CallMode.INTERNAL, c.workNumber, nameOrNull(c));
    }

    private void callPrivate(Contact c) {
        activity.getDialer().call(Dialer.CallMode.PRIVATE, c.privateNumber, nameOrNull(c));
    }

    private void callPrivateHidden(Contact c) {
        activity.getDialer().call(Dialer.CallMode.PATIENT, c.privateNumber, nameOrNull(c));
    }

    // ============================================================================ dialogs

    private void showSortDialog() {
        if (!canShowDialog()) {
            return;
        }
        final ContactStore.SortMode[] modes = ContactStore.SortMode.values();
        String[] labels = new String[modes.length];
        int checked = 0;
        ContactStore.SortMode current = activity.getPrefs().getContactSort();
        for (int i = 0; i < modes.length; i++) {
            labels[i] = activity.getString(modes[i].labelResId);
            if (modes[i] == current) {
                checked = i;
            }
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.sort_title)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which >= 0 && which < modes.length) {
                            activity.getPrefs().setContactSort(modes[which]);
                            refresh();
                        }
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showContactMenu(final Contact c) {
        if (!canShowDialog()) {
            return;
        }
        final List<Integer> actions = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        if (c.hasPrivate()) {
            String prefix = Numbers.clean(activity.getPrefs().getPrefixPatient());
            actions.add(R.string.action_call_private_hidden);
            labels.add(prefix.isEmpty()
                    ? activity.getString(R.string.action_call_private_hidden_plain)
                    : activity.getString(R.string.action_call_private_hidden, prefix));
        }
        actions.add(R.string.action_edit);
        labels.add(activity.getString(R.string.action_edit));
        actions.add(R.string.action_delete);
        labels.add(activity.getString(R.string.action_delete));

        new AlertDialog.Builder(activity)
                .setTitle(nameForDisplay(c))
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which < 0 || which >= actions.size()) {
                            return;
                        }
                        int action = actions.get(which);
                        if (action == R.string.action_call_private_hidden) {
                            callPrivateHidden(c);
                        } else if (action == R.string.action_edit) {
                            showEditDialog(c);
                        } else if (action == R.string.action_delete) {
                            confirmDelete(c);
                        }
                    }
                })
                .show();
    }

    private void confirmDelete(final Contact c) {
        if (!canShowDialog()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.confirm_delete_contact, nameForDisplay(c)))
                .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (ContactStore.get(activity).delete(c.id)) {
                            Toast.makeText(activity, R.string.contact_deleted, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(activity, R.string.save_failed, Toast.LENGTH_SHORT).show();
                        }
                        activity.refreshPanes();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Add (existing == null) or edit dialog. Requires at least one name or number. */
    private void showEditDialog(final Contact existing) {
        if (!canShowDialog()) {
            return;
        }
        View body = LayoutInflater.from(activity).inflate(R.layout.dialog_contact_edit, null, false);
        final EditText first = body.findViewById(R.id.edit_first_name);
        final EditText last = body.findViewById(R.id.edit_last_name);
        final EditText work = body.findViewById(R.id.edit_work_number);
        final EditText priv = body.findViewById(R.id.edit_private_number);
        if (existing != null) {
            first.setText(existing.firstName);
            last.setText(existing.lastName);
            work.setText(existing.workNumber);
            priv.setText(existing.privateNumber);
        }

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(existing == null ? R.string.dialog_add_contact_title : R.string.dialog_edit_contact_title)
                .setView(body)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                // Own click handler so the dialog stays open when validation fails.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Contact c = new Contact(existing == null ? null : existing.id,
                                text(first), text(last), text(work), text(priv));
                        if (c.isEmpty()) {
                            first.setError(activity.getString(R.string.contact_empty_error));
                            first.requestFocus();
                            return;
                        }
                        if (!ContactStore.get(activity).put(c)) {
                            Toast.makeText(activity, R.string.save_failed, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        dialog.dismiss();
                        activity.refreshPanes();
                    }
                });
            }
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        first.requestFocus();
    }

    private static String text(EditText e) {
        Editable t = e.getText();
        return t == null ? "" : t.toString().trim();
    }

    // ============================================================================ adapter

    private static final class Holder {
        TextView name;
        View workRow;
        TextView workText;
        View privateRow;
        TextView privateText;
    }

    private final class ContactAdapter extends BaseAdapter {

        private List<Contact> items = new ArrayList<>();

        void setItems(List<Contact> list) {
            items = list == null ? new ArrayList<Contact>() : list;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Contact getItem(int position) {
            return items.get(position);
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
                row = LayoutInflater.from(activity).inflate(R.layout.row_contact, parent, false);
                h = new Holder();
                h.name = row.findViewById(R.id.contact_name);
                h.workRow = row.findViewById(R.id.work_row);
                h.workText = row.findViewById(R.id.work_text);
                h.privateRow = row.findViewById(R.id.private_row);
                h.privateText = row.findViewById(R.id.private_text);
                row.setTag(h);
            } else {
                h = (Holder) row.getTag();
            }
            final Contact c = getItem(position);
            h.name.setText(nameForDisplay(c));

            View.OnLongClickListener menu = new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showContactMenu(c);
                    return true;
                }
            };
            row.setOnLongClickListener(menu);

            if (c.hasWork()) {
                h.workText.setText(activity.getString(R.string.contact_work_line,
                        Numbers.formatForDisplay(c.workNumber)));
                h.workRow.setVisibility(View.VISIBLE);
                h.workRow.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        callWork(c);
                    }
                });
                h.workRow.setOnLongClickListener(menu);
            } else {
                h.workRow.setVisibility(View.GONE);
                h.workRow.setOnClickListener(null);
            }

            if (c.hasPrivate()) {
                h.privateText.setText(activity.getString(R.string.contact_private_line,
                        Numbers.formatForDisplay(c.privateNumber)));
                h.privateRow.setVisibility(View.VISIBLE);
                h.privateRow.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        callPrivate(c);
                    }
                });
                h.privateRow.setOnLongClickListener(menu);
            } else {
                h.privateRow.setVisibility(View.GONE);
                h.privateRow.setOnClickListener(null);
            }
            return row;
        }
    }
}
