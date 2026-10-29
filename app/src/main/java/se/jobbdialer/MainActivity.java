package se.jobbdialer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;
import android.widget.ViewFlipper;

/**
 * The single main screen: toolbar with overflow menu, a ViewFlipper with the three panes
 * (Ring / Kontakter / Senaste) and a bottom tab bar. Also handles the launcher shortcut
 * ("Ring växeln"), the CALL_PHONE permission result, the call log sweep in onResume() and the
 * Storage Access Framework results for contact export/import (shared with SettingsActivity).
 */
public class MainActivity extends Activity {

    private static final String TAG = "Jobbdialer";

    /** Action of the static launcher shortcut (res/xml/shortcuts.xml). */
    static final String ACTION_CALL_SWITCHBOARD = "se.jobbdialer.action.CALL_SWITCHBOARD";

    /** Request codes for the SAF document pickers (also used by SettingsActivity). */
    static final int REQUEST_EXPORT = 200;
    static final int REQUEST_IMPORT = 201;

    private static final String MIME_JSON = "application/json";
    private static final String STATE_TAB = "main_tab";

    static final int TAB_DIAL = 0;
    static final int TAB_CONTACTS = 1;
    static final int TAB_RECENTS = 2;

    private Prefs prefs;
    private Dialer dialer;
    private ViewFlipper flipper;
    private TextView[] tabs;
    private DialPane dialPane;
    private ContactsPane contactsPane;
    private RecentsPane recentsPane;
    private int currentTab = TAB_DIAL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        dialer = new Dialer(this, prefs);
        dialer.restoreState(savedInstanceState);
        dialer.setListener(new Dialer.Listener() {
            @Override
            public void onCallPlaced(CallRecord record) {
                if (recentsPane != null) {
                    recentsPane.refresh();
                }
            }
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                return onMenuItem(item.getItemId());
            }
        });

        flipper = findViewById(R.id.flipper);
        dialPane = new DialPane(this, findViewById(R.id.pane_dial));
        contactsPane = new ContactsPane(this, findViewById(R.id.pane_contacts));
        recentsPane = new RecentsPane(this, findViewById(R.id.pane_recents));

        tabs = new TextView[]{
                findViewById(R.id.tab_dial),
                findViewById(R.id.tab_contacts),
                findViewById(R.id.tab_recents)};
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(index);
                }
            });
        }

        int tab = savedInstanceState == null ? TAB_DIAL : savedInstanceState.getInt(STATE_TAB, TAB_DIAL);
        showTab(tab);

        if (savedInstanceState == null) {
            handleShortcutIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShortcutIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPanes();
        if (prefs.isHideInCallLog()) {
            sweepCallLogInBackground(getApplicationContext());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
        dialer.saveState(outState);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (!dialer.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    // Activity.onActivityResult is the framework API for startActivityForResult (no AndroidX).
    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (handleDocumentResult(this, requestCode, resultCode, data)) {
            contactsPane.refresh();
            dialPane.refresh();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ============================================================================ accessors

    Dialer getDialer() {
        return dialer;
    }

    Prefs getPrefs() {
        return prefs;
    }

    /** Called by the panes after they changed data another pane shows. */
    void refreshPanes() {
        dialPane.refresh();
        contactsPane.refresh();
        recentsPane.refresh();
    }

    // ============================================================================ tabs / menu

    void showTab(int index) {
        int tab = (index < TAB_DIAL || index > TAB_RECENTS) ? TAB_DIAL : index;
        if (tab != TAB_CONTACTS) {
            hideKeyboard();
        }
        currentTab = tab;
        if (flipper.getDisplayedChild() != tab) {
            flipper.setDisplayedChild(tab);
        }
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setSelected(i == tab);
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            View focus = getCurrentFocus();
            if (imm != null && focus != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not hide the keyboard", e);
        }
    }

    private boolean onMenuItem(int id) {
        if (id == R.id.action_settings) {
            try {
                startActivity(new Intent(this, SettingsActivity.class));
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not open settings", e);
            }
            return true;
        }
        if (id == R.id.action_export) {
            startExport(this);
            return true;
        }
        if (id == R.id.action_import) {
            startImport(this);
            return true;
        }
        return false;
    }

    // ============================================================================ shortcut

    private void handleShortcutIntent(Intent intent) {
        if (intent == null || !ACTION_CALL_SWITCHBOARD.equals(intent.getAction())) {
            return;
        }
        // Relaunching the task from "recent apps" re-delivers the original shortcut intent;
        // that must not place another call.
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return;
        }
        // Consume the action so a later configuration change cannot call again.
        intent.setAction(Intent.ACTION_MAIN);
        showTab(TAB_DIAL);
        dialer.callSwitchboard();
    }

    // ============================================================================ call log

    private static void sweepCallLogInBackground(final Context appContext) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (CallLogCleaner.sweep(appContext)) {
                        CallLogCleaner.scheduleJob(appContext);
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "Call log sweep failed", e);
                }
            }
        }, "jobbdialer-calllog-sweep");
        t.start();
    }

    // ============================================================================ export / import

    /** Opens the SAF "create document" picker for the contacts export. */
    @SuppressWarnings("deprecation")
    static void startExport(Activity activity) {
        if (ContactStore.get(activity).isEmpty()) {
            Toast.makeText(activity, R.string.export_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(MIME_JSON);
        intent.putExtra(Intent.EXTRA_TITLE, ContactStore.exportFileName(activity));
        try {
            activity.startActivityForResult(intent, REQUEST_EXPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.no_file_app, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open the document picker", e);
            Toast.makeText(activity, R.string.no_file_app, Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens the SAF "open document" picker for a contacts import. */
    @SuppressWarnings("deprecation")
    static void startImport(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(MIME_JSON);
        // Some providers report .json files as text/plain or octet-stream; allow those too.
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{MIME_JSON, "text/plain", "application/octet-stream"});
        try {
            activity.startActivityForResult(intent, REQUEST_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.no_file_app, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open the document picker", e);
            Toast.makeText(activity, R.string.no_file_app, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Handles the result of {@link #startExport} / {@link #startImport}.
     *
     * @return true when the request code was one of ours (the result was consumed).
     */
    static boolean handleDocumentResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_EXPORT && requestCode != REQUEST_IMPORT) {
            return false;
        }
        if (resultCode != RESULT_OK || data == null) {
            return true;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return true;
        }
        ContactStore store = ContactStore.get(activity);
        if (requestCode == REQUEST_EXPORT) {
            int count = store.exportTo(activity, uri);
            if (count < 0) {
                Toast.makeText(activity, R.string.export_failed, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(activity, activity.getResources()
                        .getQuantityString(R.plurals.export_done, count, count), Toast.LENGTH_LONG).show();
            }
            return true;
        }
        ContactStore.ImportResult result = store.importFrom(activity, uri);
        if (result.failed()) {
            Toast.makeText(activity, R.string.import_failed, Toast.LENGTH_LONG).show();
            return true;
        }
        String msg = activity.getResources().getQuantityString(R.plurals.import_added, result.added, result.added);
        if (result.skipped > 0) {
            msg = msg + " " + activity.getResources()
                    .getQuantityString(R.plurals.import_skipped, result.skipped, result.skipped);
        }
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        return true;
    }
}
