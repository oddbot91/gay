package se.jobbdialer;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.graphics.drawable.Drawable;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "Kontakter" pane: search, sortable list, add/edit/delete and the long-press menu.
 * Tapping the "Jobb ..." / "Privat ..." chip fills that number into the dial pane (nothing is
 * dialed until the user presses the call button); direct calls are in the long-press menu.
 * The list is grouped by category (collapsible) with a "Favoriter" group last.
 *
 * <p>Selection mode (long-press "Välj flera…" or the toolbar menu "Välj kontakter"): check boxes
 * on the rows, tap = toggle, tap on a group header = toggle the whole group, and the bottom bar is
 * replaced by a selection bar (count, "Markera alla", "Ta bort", "Avbryt"). The selection is a set
 * of contact ids kept in memory only.
 */
final class ContactsPane {

    private final MainActivity activity;
    private final EditText searchField;
    private final TextView sortButton;
    private final TextView emptyText;
    private final ContactAdapter adapter = new ContactAdapter();
    /** Collapse state (in memory only): keys of the expanded groups. Empty = all collapsed. */
    private final Set<String> expanded = new HashSet<>();
    /** Key of the "Övriga" group (cannot clash with a user category name). */
    private static final String KEY_OTHER = "\u0000other";

    private final View contactsBar;
    private final View selectionBar;
    private final TextView selectionCount;
    private final View deleteSelectedButton;
    /** Selection mode state: ids of the selected contacts (in memory only). */
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selecting;

    ContactsPane(MainActivity activity, View root) {
        this.activity = activity;
        searchField = root.findViewById(R.id.search_field);
        sortButton = root.findViewById(R.id.sort_button);
        emptyText = root.findViewById(R.id.contacts_empty);
        contactsBar = root.findViewById(R.id.contacts_bar);
        selectionBar = root.findViewById(R.id.selection_bar);
        selectionCount = root.findViewById(R.id.selection_count);
        deleteSelectedButton = root.findViewById(R.id.delete_selected);
        ListView list = root.findViewById(R.id.contacts_list);
        list.setAdapter(adapter);
        list.setEmptyView(emptyText);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // Only headers get here: contact rows handle their own clicks.
                if (selecting) {
                    adapter.toggleGroupSelection(position);
                } else {
                    adapter.toggleGroup(position);
                }
            }
        });

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
        root.findViewById(R.id.select_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAllVisible();
            }
        });
        deleteSelectedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDeleteSelected();
            }
        });
        root.findViewById(R.id.cancel_selection).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitSelection();
            }
        });
        updateSelectionUi();
        refresh();
    }

    /** Reloads the list (search text and sort order applied). */
    void refresh() {
        Prefs prefs = activity.getPrefs();
        ContactStore.SortMode sort = prefs.getContactSort();
        sortButton.setText(activity.getString(R.string.sort_button, activity.getString(sort.labelResId)));
        ContactStore store = ContactStore.get(activity);
        Editable q = searchField.getText();
        String query = q == null ? "" : q.toString();
        if (!selected.isEmpty()) {
            // Drop ids of contacts that no longer exist (deleted, replaced by an import, ...).
            Set<String> existing = new HashSet<>();
            for (Contact c : store.getAll(sort)) {
                existing.add(c.id);
            }
            selected.retainAll(existing);
        }
        adapter.setRows(buildRows(store, query, sort));
        emptyText.setText(store.isEmpty() ? R.string.contacts_empty : R.string.contacts_no_match);
        updateSelectionUi();
    }

    private String currentQuery() {
        Editable q = searchField.getText();
        return q == null ? "" : q.toString();
    }

    // ============================================================================ selection mode

    boolean isSelecting() {
        return selecting;
    }

    /** Starts selection mode with nothing selected (toolbar menu "Välj kontakter"). */
    void enterSelection() {
        enterSelection(null);
    }

    /** Starts selection mode; {@code first} (may be null) is selected right away. */
    private void enterSelection(Contact first) {
        selecting = true;
        if (first != null) {
            selected.add(first.id);
        }
        // The search field is hidden behind the selection bar: close the keyboard.
        activity.hideKeyboard();
        searchField.clearFocus();
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    /** Leaves selection mode and forgets the selection (Avbryt, Back, after a delete). */
    void exitSelection() {
        selecting = false;
        selected.clear();
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    private void toggleSelected(Contact c) {
        if (!selected.remove(c.id)) {
            selected.add(c.id);
        }
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    /** "Markera alla": every contact, or the search hits while a search is active. */
    private void selectAllVisible() {
        ContactStore store = ContactStore.get(activity);
        ContactStore.SortMode sort = activity.getPrefs().getContactSort();
        String query = currentQuery();
        List<Contact> visible = query.trim().isEmpty() ? store.getAll(sort) : store.search(query, sort);
        for (Contact c : visible) {
            selected.add(c.id);
        }
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    /** Shows the normal bottom bar or the selection bar, with the count and delete state. */
    private void updateSelectionUi() {
        contactsBar.setVisibility(selecting ? View.GONE : View.VISIBLE);
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        int n = selected.size();
        selectionCount.setText(activity.getResources().getQuantityString(R.plurals.selection_count, n, n));
        deleteSelectedButton.setEnabled(n > 0);
        deleteSelectedButton.setAlpha(n > 0 ? 1f : 0.4f);
    }

    private void confirmDeleteSelected() {
        if (!canShowDialog() || selected.isEmpty()) {
            return;
        }
        final List<String> ids = new ArrayList<>(selected);
        int n = ids.size();
        new AlertDialog.Builder(activity)
                .setMessage(activity.getResources().getQuantityString(R.plurals.confirm_delete_contacts, n, n))
                .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int removed = ContactStore.get(activity).deleteAll(ids);
                        if (removed < 0) {
                            Toast.makeText(activity, R.string.save_failed, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(activity, activity.getResources()
                                    .getQuantityString(R.plurals.contacts_deleted, removed, removed),
                                    Toast.LENGTH_SHORT).show();
                        }
                        exitSelection();
                        activity.refreshPanes();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Flat rows while searching, otherwise category groups + "Övriga" + "Favoriter". */
    private List<Row> buildRows(ContactStore store, String query, ContactStore.SortMode sort) {
        List<Row> rows = new ArrayList<>();
        if (!query.trim().isEmpty()) {
            for (Contact c : store.search(query, sort)) {
                rows.add(Row.contact(c));
            }
            return rows;
        }
        if (store.isEmpty()) {
            return rows;
        }
        List<Contact> all = store.getAll(sort);
        List<String> categories = store.getCategories();
        for (String category : categories) {
            addGroup(rows, category, category, matching(all, category));
        }
        List<Contact> others = matching(all, "");
        if (!others.isEmpty()) {
            addGroup(rows, KEY_OTHER, activity.getString(R.string.group_other), others);
        }
        List<Contact> favorites = store.getFavorites(sort, ContactStore.MAX_FAVORITES);
        Row favHeader = Row.favoritesHeader(activity.getString(R.string.group_favorites), favorites.size());
        favHeader.members = favorites;
        rows.add(favHeader);
        for (Contact c : favorites) {
            rows.add(Row.contact(c));
        }
        return rows;
    }

    private static List<Contact> matching(List<Contact> all, String category) {
        List<Contact> result = new ArrayList<>();
        for (Contact c : all) {
            if (c.category.equalsIgnoreCase(category)) {
                result.add(c);
            }
        }
        return result;
    }

    private void addGroup(List<Row> rows, String key, String title, List<Contact> members) {
        boolean open = expanded.contains(key);
        Row header = Row.header(key, title, members.size(), open);
        header.members = members;
        rows.add(header);
        if (open) {
            for (Contact c : members) {
                rows.add(Row.contact(c));
            }
        }
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
        if (c.hasWork()) {
            actions.add(R.string.action_call_work_direct);
            labels.add(activity.getString(R.string.action_call_work_direct));
        }
        if (c.hasPrivate()) {
            actions.add(R.string.action_call_private_direct);
            labels.add(activity.getString(R.string.action_call_private_direct));
            String prefix = Numbers.clean(activity.getPrefs().getPrefixPatient());
            actions.add(R.string.action_call_private_hidden);
            labels.add(prefix.isEmpty()
                    ? activity.getString(R.string.action_call_private_hidden_plain)
                    : activity.getString(R.string.action_call_private_hidden, prefix));
            actions.add(R.string.action_send_sms);
            labels.add(activity.getString(R.string.action_send_sms));
        }
        actions.add(R.string.action_edit);
        labels.add(activity.getString(R.string.action_edit));
        int favAction = c.favorite ? R.string.action_remove_favorite : R.string.action_add_favorite;
        actions.add(favAction);
        labels.add(activity.getString(favAction));
        actions.add(R.string.action_select_multiple);
        labels.add(activity.getString(R.string.action_select_multiple));
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
                        if (action == R.string.action_call_work_direct) {
                            callWork(c);
                        } else if (action == R.string.action_call_private_direct) {
                            callPrivate(c);
                        } else if (action == R.string.action_call_private_hidden) {
                            callPrivateHidden(c);
                        } else if (action == R.string.action_send_sms) {
                            MainActivity.sendSms(activity, c.privateNumber);
                        } else if (action == R.string.action_select_multiple) {
                            enterSelection(c);
                        } else if (action == R.string.action_add_favorite
                                || action == R.string.action_remove_favorite) {
                            toggleFavorite(c);
                        } else if (action == R.string.action_edit) {
                            showEditDialog(c);
                        } else if (action == R.string.action_delete) {
                            confirmDelete(c);
                        }
                    }
                })
                .show();
    }

    private void toggleFavorite(Contact c) {
        boolean wanted = !c.favorite;
        if (!ContactStore.get(activity).setFavorite(c.id, wanted) && wanted) {
            Toast.makeText(activity, R.string.max_favorites, Toast.LENGTH_SHORT).show();
        }
        activity.refreshPanes();
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
        final Spinner categorySpinner = body.findViewById(R.id.edit_category);
        final CheckBox favoriteBox = body.findViewById(R.id.edit_favorite);
        final ContactStore store = ContactStore.get(activity);
        final boolean wasFavorite = existing != null && existing.favorite;
        final List<String> cats = new ArrayList<>(store.getCategories());
        final ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, new ArrayList<String>());
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        categorySpinner.setAdapter(categoryAdapter);
        fillCategoryItems(categoryAdapter, cats);
        final int[] previous = {0};
        if (existing != null && !existing.category.isEmpty()) {
            int idx = indexOfIgnoreCase(cats, existing.category);
            if (idx >= 0) {
                previous[0] = idx + 1;
            }
        }
        categorySpinner.setSelection(previous[0]);
        categorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == categoryAdapter.getCount() - 1) {
                    askNewCategory(categorySpinner, categoryAdapter, cats, previous);
                } else {
                    previous[0] = position;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        favoriteBox.setChecked(wasFavorite);
        favoriteBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton box, boolean checked) {
                if (checked && !wasFavorite && store.favoriteCount() >= ContactStore.MAX_FAVORITES) {
                    box.setChecked(false);
                    Toast.makeText(activity, R.string.max_favorites, Toast.LENGTH_SHORT).show();
                }
            }
        });
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
                        int sel = categorySpinner.getSelectedItemPosition();
                        String category = (sel >= 1 && sel <= cats.size()) ? cats.get(sel - 1) : "";
                        boolean favorite = favoriteBox.isChecked();
                        if (favorite && !wasFavorite && store.favoriteCount() >= ContactStore.MAX_FAVORITES) {
                            favorite = false;
                            Toast.makeText(activity, R.string.max_favorites, Toast.LENGTH_SHORT).show();
                        }
                        Contact c = new Contact(existing == null ? null : existing.id,
                                text(first), text(last), text(work), text(priv), category, favorite);
                        if (c.isEmpty()) {
                            first.setError(activity.getString(R.string.contact_empty_error));
                            first.requestFocus();
                            return;
                        }
                        if (!store.put(c)) {
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

    private static void fillCategoryItems(ArrayAdapter<String> adapter, List<String> cats) {
        adapter.clear();
        adapter.add(adapter.getContext().getString(R.string.category_none));
        adapter.addAll(cats);
        adapter.add(adapter.getContext().getString(R.string.category_new_item));
    }

    private static int indexOfIgnoreCase(List<String> list, String value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(value)) {
                return i;
            }
        }
        return -1;
    }

    /** "+ Ny kategori…": asks for a name, adds it and selects it; otherwise restores the selection. */
    private void askNewCategory(final Spinner spinner, final ArrayAdapter<String> spinnerAdapter,
                                final List<String> cats, final int[] previous) {
        if (!canShowDialog()) {
            spinner.setSelection(previous[0]);
            return;
        }
        View body = LayoutInflater.from(activity).inflate(R.layout.dialog_text_input, null, false);
        final EditText input = body.findViewById(R.id.text_input);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_new_category_title)
                .setView(body)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                spinner.setSelection(previous[0]);
            }
        });
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        spinner.setSelection(previous[0]);
                        dialog.dismiss();
                    }
                });
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String name = text(input);
                        if (name.isEmpty()) {
                            input.setError(activity.getString(R.string.category_add_failed));
                            return;
                        }
                        ContactStore store = ContactStore.get(activity);
                        store.addCategory(name); // false when it already exists: just select it
                        cats.clear();
                        cats.addAll(store.getCategories());
                        fillCategoryItems(spinnerAdapter, cats);
                        int idx = indexOfIgnoreCase(cats, name);
                        spinner.setSelection(idx >= 0 ? idx + 1 : previous[0]);
                        dialog.dismiss();
                    }
                });
            }
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }

    // ============================================================================ categories dialog

    /** Menu "Kategorier…": list with a remove button per row and an add field at the bottom. */
    void showCategoriesDialog() {
        if (!canShowDialog()) {
            return;
        }
        View body = LayoutInflater.from(activity).inflate(R.layout.dialog_categories, null, false);
        final LinearLayout listView = body.findViewById(R.id.category_list);
        final EditText nameField = body.findViewById(R.id.category_new_name);
        Button addButton = body.findViewById(R.id.category_add_button);
        final Runnable[] populate = new Runnable[1];
        final Runnable addAction = new Runnable() {
            @Override
            public void run() {
                String name = text(nameField);
                if (!name.isEmpty() && ContactStore.get(activity).addCategory(name)) {
                    nameField.setText("");
                    populate[0].run();
                    activity.refreshPanes();
                } else {
                    Toast.makeText(activity, R.string.category_add_failed, Toast.LENGTH_SHORT).show();
                }
            }
        };
        populate[0] = new Runnable() {
            @Override
            public void run() {
                listView.removeAllViews();
                List<String> cats = ContactStore.get(activity).getCategories();
                if (cats.isEmpty()) {
                    TextView none = new TextView(activity);
                    none.setText(R.string.categories_empty);
                    none.setPadding(0, 0, 0, activity.getResources().getDimensionPixelSize(R.dimen.space_small));
                    listView.addView(none);
                }
                for (final String cat : cats) {
                    View row = LayoutInflater.from(activity).inflate(R.layout.row_category, listView, false);
                    ((TextView) row.findViewById(R.id.category_name)).setText(cat);
                    ImageButton remove = row.findViewById(R.id.category_remove);
                    remove.setContentDescription(activity.getString(R.string.cd_remove_category, cat));
                    remove.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            confirmRemoveCategory(cat, populate[0]);
                        }
                    });
                    listView.addView(row);
                }
            }
        };
        populate[0].run();
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addAction.run();
            }
        });
        nameField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    addAction.run();
                    return true;
                }
                return false;
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_categories_title)
                .setView(body)
                .setPositiveButton(R.string.close, null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                activity.refreshPanes();
            }
        });
        dialog.show();
    }

    private void confirmRemoveCategory(final String category, final Runnable afterChange) {
        if (!canShowDialog()) {
            return;
        }
        int count = ContactStore.get(activity).countInCategory(category);
        String message = count == 0
                ? activity.getString(R.string.confirm_remove_category_empty, category)
                : activity.getResources().getQuantityString(R.plurals.confirm_remove_category, count, category, count);
        new AlertDialog.Builder(activity)
                .setMessage(message)
                .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ContactStore.get(activity).removeCategory(category);
                        expanded.remove(category);
                        afterChange.run();
                        activity.refreshPanes();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static String text(EditText e) {
        Editable t = e.getText();
        return t == null ? "" : t.toString().trim();
    }

    // ============================================================================ adapter

    /** One list row: a group header or a contact. */
    private static final class Row {
        Contact contact;      // null for headers
        String key;           // group key (headers)
        String title;
        int count;
        boolean expanded;
        boolean favorites;    // the always-open "Favoriter" header
        List<Contact> members = new ArrayList<>(); // headers: every contact of the group

        static Row contact(Contact c) {
            Row r = new Row();
            r.contact = c;
            return r;
        }

        static Row header(String key, String title, int count, boolean expanded) {
            Row r = new Row();
            r.key = key;
            r.title = title;
            r.count = count;
            r.expanded = expanded;
            return r;
        }

        static Row favoritesHeader(String title, int count) {
            Row r = header(null, title, count, true);
            r.favorites = true;
            return r;
        }
    }

    private static final class Holder {
        CheckBox selectBox;
        TextView name;
        ImageView star;
        View chipsRow;
        View chipGap;
        View workRow;
        TextView workText;
        View privateRow;
        TextView privateText;
    }

    private static final class HeaderHolder {
        ImageView icon;
        TextView title;
        TextView count;
        Drawable background;
        android.content.res.ColorStateList chevronTint;
    }

    private final class ContactAdapter extends BaseAdapter {

        private static final int TYPE_CONTACT = 0;
        private static final int TYPE_HEADER = 1;

        private List<Row> rows = new ArrayList<>();

        void setRows(List<Row> list) {
            rows = list == null ? new ArrayList<Row>() : list;
            notifyDataSetChanged();
        }

        /** Tap on a header: expand/collapse (the favorites header never collapses). */
        void toggleGroup(int position) {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            Row r = rows.get(position);
            if (r.contact != null || r.favorites || r.key == null) {
                return;
            }
            if (!expanded.remove(r.key)) {
                expanded.add(r.key);
            }
            refresh();
        }

        /**
         * Tap on a header in selection mode: selects every contact of the group, or deselects
         * them all when they already are all selected. A collapsed group is expanded.
         */
        void toggleGroupSelection(int position) {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            Row r = rows.get(position);
            if (r.contact != null || r.members.isEmpty()) {
                return;
            }
            boolean allSelected = true;
            for (Contact c : r.members) {
                if (!selected.contains(c.id)) {
                    allSelected = false;
                    break;
                }
            }
            for (Contact c : r.members) {
                if (allSelected) {
                    selected.remove(c.id);
                } else {
                    selected.add(c.id);
                }
            }
            if (!r.favorites && r.key != null) {
                expanded.add(r.key);
            }
            refresh(); // rebuilds the rows (expanded group) and updates the selection bar
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).contact == null ? TYPE_HEADER : TYPE_CONTACT;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row r = getItem(position);
            if (r.contact == null) {
                return headerView(r, convertView, parent);
            }
            return contactView(r.contact, convertView, parent);
        }

        private View headerView(Row r, View convertView, ViewGroup parent) {
            View row = convertView;
            HeaderHolder h;
            if (row == null) {
                row = LayoutInflater.from(activity).inflate(R.layout.row_contact_header, parent, false);
                h = new HeaderHolder();
                h.icon = row.findViewById(R.id.header_chevron);
                h.title = row.findViewById(R.id.header_title);
                h.count = row.findViewById(R.id.header_count);
                h.background = row.getBackground();
                h.chevronTint = h.icon.getImageTintList();
                row.setTag(h);
            } else {
                h = (HeaderHolder) row.getTag();
            }
            h.title.setText(r.title);
            h.count.setText(String.valueOf(r.count));
            if (r.favorites) {
                h.icon.setImageResource(R.drawable.ic_star);
                h.icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                        activity.getColor(R.color.accent)));
                h.icon.setRotation(0f);
                row.setBackground(null);
            } else {
                h.icon.setImageResource(R.drawable.ic_chevron);
                h.icon.setImageTintList(h.chevronTint);
                h.icon.setRotation(r.expanded ? 90f : 0f);
                row.setBackground(h.background);
            }
            return row;
        }

        private View contactView(final Contact c, View convertView, ViewGroup parent) {
            View row = convertView;
            Holder h;
            if (row == null) {
                row = LayoutInflater.from(activity).inflate(R.layout.row_contact, parent, false);
                h = new Holder();
                h.selectBox = row.findViewById(R.id.select_box);
                h.name = row.findViewById(R.id.contact_name);
                h.star = row.findViewById(R.id.favorite_star);
                h.chipsRow = row.findViewById(R.id.chips_row);
                h.chipGap = row.findViewById(R.id.chip_gap);
                h.workRow = row.findViewById(R.id.work_row);
                h.workText = row.findViewById(R.id.work_text);
                h.privateRow = row.findViewById(R.id.private_row);
                h.privateText = row.findViewById(R.id.private_text);
                row.setTag(h);
            } else {
                h = (Holder) row.getTag();
            }
            h.name.setText(nameForDisplay(c));
            h.star.setVisibility(c.favorite ? View.VISIBLE : View.GONE);

            // Selection mode: check box on the left, the whole row toggles the selection.
            h.selectBox.setVisibility(selecting ? View.VISIBLE : View.GONE);
            h.selectBox.setChecked(selecting && selected.contains(c.id));
            if (selecting) {
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleSelected(c);
                    }
                });
            } else {
                row.setOnClickListener(null);
                row.setClickable(false);
            }

            View.OnLongClickListener menu = new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (selecting) {
                        toggleSelected(c);
                    } else {
                        showContactMenu(c);
                    }
                    return true;
                }
            };
            row.setOnLongClickListener(menu);

            boolean work = c.hasWork();
            boolean priv = c.hasPrivate();
            if (work) {
                h.workText.setText(activity.getString(R.string.contact_work_line,
                        Numbers.formatForDisplay(c.workNumber)));
                h.workRow.setVisibility(View.VISIBLE);
                h.workRow.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        activity.openDialerWith(c.workNumber);
                    }
                });
                h.workRow.setOnLongClickListener(menu);
            } else {
                h.workRow.setVisibility(View.GONE);
                h.workRow.setOnClickListener(null);
                h.workRow.setOnLongClickListener(null);
            }

            if (priv) {
                h.privateText.setText(activity.getString(R.string.contact_private_line,
                        Numbers.formatForDisplay(c.privateNumber)));
                h.privateRow.setVisibility(View.VISIBLE);
                h.privateRow.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        activity.openDialerWith(c.privateNumber);
                    }
                });
                h.privateRow.setOnLongClickListener(menu);
            } else {
                h.privateRow.setVisibility(View.GONE);
                h.privateRow.setOnClickListener(null);
                h.privateRow.setOnLongClickListener(null);
            }
            h.chipGap.setVisibility(work && priv ? View.VISIBLE : View.GONE);
            h.chipsRow.setVisibility(work || priv ? View.VISIBLE : View.GONE);

            // In selection mode the chips must not take the touch: it goes to the row (toggle).
            // Set after the listeners above, since setOn(Long)ClickListener makes a view clickable.
            setChipActive(h.workRow, !selecting && work);
            setChipActive(h.privateRow, !selecting && priv);
            return row;
        }

        private void setChipActive(View chip, boolean active) {
            chip.setEnabled(active);
            chip.setClickable(active);
            chip.setLongClickable(active);
            chip.setFocusable(active);
        }
    }
}
