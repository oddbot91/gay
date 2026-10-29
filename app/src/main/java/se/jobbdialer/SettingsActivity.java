package se.jobbdialer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

/**
 * Settings: prefixes and switchboard number (saved in onPause), the opt-in "hide the app's
 * calls in the phone's call log" switch with its permission flow, contacts export/import and
 * the version line.
 */
public class SettingsActivity extends Activity {

    private static final String TAG = "Jobbdialer";

    private Prefs prefs;
    private EditText prefixInternal;
    private EditText prefixPatient;
    private EditText switchboard;
    private Switch hideSwitch;
    /** True while the switch is changed from code (suppresses the change listener). */
    private boolean updatingSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        prefixInternal = findViewById(R.id.prefix_internal);
        prefixPatient = findViewById(R.id.prefix_patient);
        switchboard = findViewById(R.id.switchboard_number);
        if (savedInstanceState == null) {
            loadNumberFields();
        }

        findViewById(R.id.reset_defaults).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });

        hideSwitch = findViewById(R.id.hide_call_log);
        hideSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!updatingSwitch) {
                    onHideSwitchChanged(isChecked);
                }
            }
        });

        findViewById(R.id.export_contacts).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.startExport(SettingsActivity.this);
            }
        });
        findViewById(R.id.import_contacts).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.startImport(SettingsActivity.this);
            }
        });

        TextView version = findViewById(R.id.version);
        version.setText(getString(R.string.settings_version, versionName()));

        // Runtime self-test of the T9 table: "Bögen" must map to 26436 (b=2, ö=6, g=4, e=3, n=6).
        TextView selfTest = findViewById(R.id.t9_selftest);
        String digits = T9.toDigits("Bögen");
        boolean ok = "26436".equals(digits) && T9.matchesName("Bögen", "", "26436");
        selfTest.setText(getString(R.string.t9_selftest, digits, ok ? "✓" : "✗",
                ContactStore.get(this).size()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Permissions can be revoked in the system settings while the app is in the background.
        if (prefs.isHideInCallLog() && !CallLogCleaner.hasPermissions(this)) {
            disableHide();
        }
        setSwitchChecked(prefs.isHideInCallLog());
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveNumberFields();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!MainActivity.handleDocumentResult(this, requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // ============================================================================ numbers

    private void loadNumberFields() {
        prefixInternal.setText(prefs.getPrefixInternal());
        prefixPatient.setText(prefs.getPrefixPatient());
        switchboard.setText(prefs.getSwitchboardNumber());
    }

    private void saveNumberFields() {
        prefs.setPrefixInternal(text(prefixInternal));
        prefs.setPrefixPatient(text(prefixPatient));
        prefs.setSwitchboardNumber(text(switchboard));
    }

    private static String text(EditText e) {
        Editable t = e.getText();
        return t == null ? "" : t.toString().trim();
    }

    private void confirmReset() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_reset_title)
                .setMessage(getString(R.string.confirm_reset_message,
                        Prefs.DEFAULT_PREFIX_INTERNAL, Prefs.DEFAULT_PREFIX_PATIENT, Prefs.DEFAULT_SWITCHBOARD))
                .setPositiveButton(R.string.action_reset, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.resetNumbers();
                        loadNumberFields();
                        Toast.makeText(SettingsActivity.this, R.string.reset_done, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ============================================================================ call log switch

    private void setSwitchChecked(boolean checked) {
        updatingSwitch = true;
        hideSwitch.setChecked(checked);
        updatingSwitch = false;
    }

    private void onHideSwitchChanged(boolean checked) {
        if (!checked) {
            disableHide();
            return;
        }
        if (CallLogCleaner.hasPermissions(this)) {
            enableHide();
            return;
        }
        if (isFinishing() || isDestroyed()) {
            setSwitchChecked(false);
            return;
        }
        // Short explanation first, then the system permission dialog.
        new AlertDialog.Builder(this)
                .setTitle(R.string.hide_call_log_dialog_title)
                .setMessage(R.string.hide_call_log_dialog_message)
                .setPositiveButton(R.string.continue_action, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requestPermissions(new String[]{
                                Manifest.permission.READ_CALL_LOG,
                                Manifest.permission.WRITE_CALL_LOG}, Dialer.REQUEST_CALL_LOG);
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setSwitchChecked(false);
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        setSwitchChecked(false);
                    }
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != Dialer.REQUEST_CALL_LOG) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0;
        if (granted) {
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
        }
        if (granted && CallLogCleaner.hasPermissions(this)) {
            enableHide();
            setSwitchChecked(true);
            return;
        }
        disableHide();
        setSwitchChecked(false);
        if (grantResults == null || grantResults.length == 0) {
            return; // request was interrupted; nothing to tell the user
        }
        boolean permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALL_LOG)
                && !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_CALL_LOG);
        if (permanentlyDenied && !isFinishing() && !isDestroyed()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permission_denied_title)
                    .setMessage(R.string.permission_denied_call_log_message)
                    .setPositiveButton(R.string.open_app_info, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            openAppInfo();
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            Toast.makeText(this, R.string.hide_call_log_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void enableHide() {
        prefs.setHideInCallLog(true);
        Toast.makeText(this, R.string.hide_call_log_enabled, Toast.LENGTH_SHORT).show();
    }

    private void disableHide() {
        prefs.setHideInCallLog(false);
        CallLogCleaner.clearPending(this);
        CallLogCleaner.cancelJob(this);
    }

    private void openAppInfo() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open the app info screen", e);
        }
    }

    // ============================================================================ version

    private String versionName() {
        try {
            PackageInfo info = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? getPackageManager().getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0))
                    : legacyPackageInfo();
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w(TAG, "Could not read the version name", e);
            return "";
        }
    }

    // getPackageInfo(String, int) is deprecated on API 33+, but the only option on API 26-32.
    @SuppressWarnings("deprecation")
    private PackageInfo legacyPackageInfo() throws PackageManager.NameNotFoundException {
        return getPackageManager().getPackageInfo(getPackageName(), 0);
    }
}
