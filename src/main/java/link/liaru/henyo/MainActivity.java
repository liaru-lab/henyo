package link.liaru.henyo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int ID_BIND_LOCALHOST = 1001;
    private static final int ID_BIND_ALL_INTERFACES = 1002;
    private static final int REQUEST_TERMUX_RUN_COMMAND = 2001;
    private static final String TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::updateStatus;

    private TextView status;
    private TextView excludedAppsSummary;
    private TextView pairingStatus;
    private TextView pairingCode;
    private TextView pairingNextCode;
    private TextView pairingDetails;
    private PairingRingView pairingRing;
    private TextView message;
    private LinearLayout tokenList;
    private Switch remoteEnabled;
    private Switch tailscaleWatchdogEnabled;
    private RadioGroup bindHostGroup;
    private EditText cidrs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        root.addView(sectionTitle("Henyo Accessibility Bridge"));
        status = bodyText();
        root.addView(status);

        root.addView(sectionTitle("Targeting"));
        TextView targetingHelp = bodyText();
        targetingHelp.setText("Henyo will not choose excluded apps as automation targets. This changes target selection only; it does not hide those windows from Android.");
        root.addView(targetingHelp);
        excludedAppsSummary = bodyText();
        root.addView(excludedAppsSummary);
        Button chooseExcludedApps = new Button(this);
        chooseExcludedApps.setText("Choose Excluded Apps");
        chooseExcludedApps.setOnClickListener((View v) -> showExcludedAppPicker());
        root.addView(chooseExcludedApps);

        root.addView(sectionTitle("Remote Access"));
        remoteEnabled = new Switch(this);
        remoteEnabled.setText("Enable remote access");
        root.addView(remoteEnabled);

        bindHostGroup = new RadioGroup(this);
        bindHostGroup.setOrientation(RadioGroup.VERTICAL);
        bindHostGroup.addView(radio(ID_BIND_LOCALHOST, "Listen on 127.0.0.1 only"));
        bindHostGroup.addView(radio(ID_BIND_ALL_INTERFACES, "Listen on 0.0.0.0"));
        root.addView(bindHostGroup);

        cidrs = new EditText(this);
        cidrs.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        cidrs.setMinLines(3);
        cidrs.setSingleLine(false);
        cidrs.setHint("100.64.0.0/10\nfd7a:115c:a1e0::/48");
        root.addView(label("Allowed CIDRs, one per line"));
        root.addView(cidrs);

        Button saveRemoteAccess = new Button(this);
        saveRemoteAccess.setText("Save Remote Access");
        saveRemoteAccess.setOnClickListener((View v) -> saveRemoteAccess());
        root.addView(saveRemoteAccess);

        root.addView(sectionTitle("Connectivity Watchdog"));
        tailscaleWatchdogEnabled = new Switch(this);
        tailscaleWatchdogEnabled.setText("Auto-recover Tailscale VPN");
        tailscaleWatchdogEnabled.setChecked(TailscaleWatchdog.isEnabled(this));
        tailscaleWatchdogEnabled.setOnCheckedChangeListener((button, enabled) -> {
            HenyoAccessibilityService.setTailscaleWatchdogEnabled(this, enabled);
            showMessage("Tailscale watchdog " + (enabled ? "enabled." : "disabled."));
        });
        root.addView(tailscaleWatchdogEnabled);
        TextView watchdogHelp = bodyText();
        watchdogHelp.setText("While remote access is enabled, Henyo checks for an Android VPN and opens Tailscale when the VPN disappears. Recovery attempts are rate limited.");
        root.addView(watchdogHelp);

        root.addView(sectionTitle("Pairing"));
        pairingStatus = bodyText();
        root.addView(pairingStatus);
        pairingCode = new TextView(this);
        pairingCode.setGravity(Gravity.CENTER);
        pairingCode.setTextSize(42);
        pairingCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pairingCode.setPadding(0, dp(8), 0, dp(2));
        root.addView(pairingCode);

        pairingRing = new PairingRingView(this);
        LinearLayout.LayoutParams ringParams = new LinearLayout.LayoutParams(dp(120), dp(120));
        ringParams.gravity = Gravity.CENTER_HORIZONTAL;
        ringParams.setMargins(0, dp(4), 0, dp(8));
        root.addView(pairingRing, ringParams);

        pairingNextCode = bodyText();
        pairingNextCode.setGravity(Gravity.CENTER);
        pairingNextCode.setTypeface(Typeface.MONOSPACE);
        root.addView(pairingNextCode);

        pairingDetails = bodyText();
        pairingDetails.setGravity(Gravity.CENTER);
        root.addView(pairingDetails);

        Button startPairing = new Button(this);
        startPairing.setText("Start Remote Pairing");
        startPairing.setOnClickListener((View v) -> {
            PairingSessionManager.StartResult result = PairingSessionManager.get().start(null, "MainActivity");
            if (!result.ok) showMessage("Pairing is already active.");
            updateStatus();
        });
        root.addView(startPairing);

        Button cancelPairing = new Button(this);
        cancelPairing.setText("Cancel Remote Pairing");
        cancelPairing.setOnClickListener((View v) -> {
            PairingSessionManager.get().cancel();
            updateStatus();
        });
        root.addView(cancelPairing);

        root.addView(sectionTitle("Registered Clients"));
        TextView commandWarning = bodyText();
        commandWarning.setText("Termux command execution is disabled by default. Enabling it for a client grants that paired device the ability to run arbitrary commands with your full Termux user access.");
        root.addView(commandWarning);
        tokenList = new LinearLayout(this);
        tokenList.setOrientation(LinearLayout.VERTICAL);
        root.addView(tokenList);

        Button refreshTokens = new Button(this);
        refreshTokens.setText("Refresh Clients");
        refreshTokens.setOnClickListener((View v) -> updateStatus());
        root.addView(refreshTokens);

        Button openSettings = new Button(this);
        openSettings.setText("Open Accessibility Settings");
        openSettings.setOnClickListener((View v) ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(openSettings);

        message = bodyText();
        root.addView(message);

        setContentView(scroll);
        loadRemoteAccessFields();
        updateExcludedAppsSummary();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void loadRemoteAccessFields() {
        RemoteAccessConfig config = RemoteAccessConfig.load(this);
        remoteEnabled.setChecked(config.enabled);
        bindHostGroup.check("0.0.0.0".equals(config.bindHost) ? ID_BIND_ALL_INTERFACES : ID_BIND_LOCALHOST);
        cidrs.setText(joinLines(config.allowedCidrs));
    }

    private void saveRemoteAccess() {
        RemoteAccessConfig.Update update = new RemoteAccessConfig.Update();
        update.enabled = remoteEnabled.isChecked();
        update.bindHost = bindHostGroup.getCheckedRadioButtonId() == ID_BIND_ALL_INTERFACES ? "0.0.0.0" : "127.0.0.1";
        update.port = RemoteAccessConfig.DEFAULT_PORT;
        update.allowedCidrs = parseCidrs(cidrs.getText().toString());
        update.requireAuth = true;
        try {
            HenyoAccessibilityService.applyRemoteAccessConfig(this, update);
            showMessage("Remote access settings saved.");
            loadRemoteAccessFields();
        } catch (RemoteAccessConfig.ValidationException e) {
            showMessage("Invalid remote access settings: " + e.getMessage());
        } catch (IOException e) {
            showMessage("Could not bind listener: " + e.getMessage());
        }
        updateStatus();
    }

    private void showExcludedAppPicker() {
        PackageManager packageManager = getPackageManager();
        List<TargetingApp> apps = new ArrayList<>();
        for (android.content.pm.ApplicationInfo info : packageManager.getInstalledApplications(0)) {
            if (info == null || info.packageName == null || info.packageName.equals(getPackageName())) continue;
            String label = String.valueOf(info.loadLabel(packageManager));
            if (label.trim().isEmpty()) label = info.packageName;
            apps.add(new TargetingApp(label, info.packageName));
        }
        Collections.sort(apps, Comparator
                .comparing((TargetingApp app) -> app.label.toLowerCase(java.util.Locale.ROOT))
                .thenComparing(app -> app.packageName));

        Set<String> selected = new HashSet<>(ExcludedAppStore.load(this));
        CharSequence[] labels = new CharSequence[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            TargetingApp app = apps.get(i);
            labels[i] = app.label + "\n" + app.packageName;
            checked[i] = selected.contains(app.packageName);
        }

        new AlertDialog.Builder(this)
                .setTitle("Excluded Apps")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    String packageName = apps.get(which).packageName;
                    if (isChecked) selected.add(packageName);
                    else selected.remove(packageName);
                })
                .setPositiveButton("Save", (dialog, which) -> {
                    ExcludedAppStore.save(this, selected);
                    updateExcludedAppsSummary();
                    showMessage("Targeting exclusions saved.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateExcludedAppsSummary() {
        int count = ExcludedAppStore.load(this).size();
        excludedAppsSummary.setText(count == 0
                ? "No apps are excluded."
                : count + (count == 1 ? " app is excluded." : " apps are excluded."));
    }

    private void updateStatus() {
        RemoteAccessConfig config = RemoteAccessConfig.load(this);
        status.setText(config.displayStatus());

        PairingSessionManager manager = PairingSessionManager.get();
        long now = System.currentTimeMillis();
        PairingSessionManager.Display display = manager.displayStatusAt(now);
        PairingSessionManager.Status current = display.status;
        if (current.active && !display.currentPin.isEmpty()) {
            long remaining = Math.max(0, (current.expiresAt - now + 999) / 1000);
            boolean expiringPin = display.pinSecondsRemaining <= 10;
            pairingStatus.setText(expiringPin ? "Pairing code changes soon" : "Pairing code");
            pairingCode.setText(formatPin(display.currentPin));
            pairingRing.setCountdown(display.pinProgress, display.pinSecondsRemaining, expiringPin);
            pairingNextCode.setText((expiringPin ? "Use next code if telling an agent now: " : "Next code: ")
                    + formatPin(display.nextPin));
            pairingDetails.setText("Pairing expires in " + formatDuration(remaining) +
                    "\nAttempts remaining: " + current.attemptsRemaining +
                    "\nPairing ID: " + current.pairingId);
            setPairingDisplayVisible(true);
        } else {
            pairingStatus.setText("Remote pairing: " + current.state);
            pairingCode.setText("");
            pairingRing.setCountdown(0f, 0, false);
            pairingNextCode.setText("");
            pairingDetails.setText("");
            setPairingDisplayVisible(false);
        }

        renderTokens();
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 1000);
    }

    private void renderTokens() {
        tokenList.removeAllViews();
        List<BearerTokenManager.TokenRecord> records = new BearerTokenManager(this).list();
        if (records.isEmpty()) {
            tokenList.addView(label("No registered clients."));
            return;
        }
        for (BearerTokenManager.TokenRecord record : records) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(6), 0, dp(10));

            TextView details = bodyText();
            details.setText(tokenSummary(record));
            row.addView(details);

            if (!record.revoked) {
                Switch termuxCommands = new Switch(this);
                termuxCommands.setText("Allow arbitrary commands in Termux");
                termuxCommands.setChecked(record.hasScope(BearerTokenManager.SCOPE_TERMUX_COMMAND));
                termuxCommands.setOnCheckedChangeListener((button, enabled) -> {
                    BearerTokenManager manager = new BearerTokenManager(this);
                    if (!manager.setScope(record.id, BearerTokenManager.SCOPE_TERMUX_COMMAND, enabled)) {
                        showMessage("Could not update Termux permission for " + record.name);
                        updateStatus();
                        return;
                    }
                    if (enabled && checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{TERMUX_RUN_COMMAND_PERMISSION}, REQUEST_TERMUX_RUN_COMMAND);
                    }
                    showMessage((enabled ? "Enabled" : "Disabled") + " Termux commands for " + record.name);
                    updateStatus();
                });
                row.addView(termuxCommands);

                Switch sensitiveUiControl = new Switch(this);
                sensitiveUiControl.setText("Allow protected Android controls");
                sensitiveUiControl.setChecked(record.hasScope(BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL));
                sensitiveUiControl.setOnCheckedChangeListener((button, enabled) -> {
                    if (enabled && !record.hasScope(BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL)) {
                        confirmSensitiveUiControl(record);
                        return;
                    }
                    BearerTokenManager manager = new BearerTokenManager(this);
                    if (!manager.setScope(record.id, BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL, false)) {
                        showMessage("Could not update protected control permission for " + record.name);
                    } else {
                        showMessage("Disabled protected Android controls for " + record.name);
                    }
                    updateStatus();
                });
                row.addView(sensitiveUiControl);

                Button revoke = new Button(this);
                revoke.setText("Revoke " + record.name);
                revoke.setOnClickListener((View v) -> {
                    new BearerTokenManager(this).revoke(record.id);
                    showMessage("Client revoked: " + record.name);
                    updateStatus();
                });
                row.addView(revoke);
            }
            tokenList.addView(row);
        }
    }

    private String tokenSummary(BearerTokenManager.TokenRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append(record.name).append(record.revoked ? " (revoked)" : " (active)");
        sb.append("\nScopes: ").append(joinComma(record.scopes));
        sb.append("\nTermux commands: ")
                .append(record.hasScope(BearerTokenManager.SCOPE_TERMUX_COMMAND) ? "allowed" : "not allowed");
        sb.append("\nProtected Android controls: ")
                .append(record.hasScope(BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL) ? "allowed" : "not allowed");
        sb.append("\nCreated: ").append(BearerTokenManager.instant(record.createdAt));
        if (record.lastUsedAt > 0) sb.append("\nLast used: ").append(BearerTokenManager.instant(record.lastUsedAt));
        if (!record.lastSourceAddress.isEmpty()) sb.append("\nLast source: ").append(record.lastSourceAddress);
        if (record.revokedAt > 0) sb.append("\nRevoked: ").append(BearerTokenManager.instant(record.revokedAt));
        return sb.toString();
    }

    private void confirmSensitiveUiControl(BearerTokenManager.TokenRecord record) {
        new AlertDialog.Builder(this)
                .setTitle("Allow protected Android controls?")
                .setMessage("Allow " + record.name + " to interact with Android controls that the platform considers sensitive or protected. Only enable this for a client you trust.")
                .setPositiveButton("Allow", (dialog, which) -> {
                    BearerTokenManager manager = new BearerTokenManager(this);
                    if (!manager.setScope(record.id, BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL, true)) {
                        showMessage("Could not update protected control permission for " + record.name);
                    } else {
                        showMessage("Enabled protected Android controls for " + record.name);
                    }
                    updateStatus();
                })
                .setNegativeButton("Cancel", (dialog, which) -> updateStatus())
                .setOnCancelListener(dialog -> updateStatus())
                .show();
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(22);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(12), 0, dp(8));
        return view;
    }

    private TextView bodyText() {
        TextView view = new TextView(this);
        view.setTextSize(15);
        view.setPadding(0, dp(4), 0, dp(8));
        return view;
    }

    private TextView label(String text) {
        TextView view = bodyText();
        view.setText(text);
        return view;
    }

    private RadioButton radio(int id, String text) {
        RadioButton button = new RadioButton(this);
        button.setId(id);
        button.setText(text);
        return button;
    }

    private void setPairingDisplayVisible(boolean visible) {
        int state = visible ? View.VISIBLE : View.GONE;
        pairingCode.setVisibility(state);
        pairingRing.setVisibility(state);
        pairingNextCode.setVisibility(state);
        pairingDetails.setVisibility(state);
    }

    private static String formatPin(String pin) {
        if (pin == null) return "";
        String clean = pin.trim();
        if (clean.length() == 6) return clean.substring(0, 3) + " " + clean.substring(3);
        return clean;
    }

    private static String formatDuration(long seconds) {
        long safe = Math.max(0, seconds);
        long minutes = safe / 60;
        long rest = safe % 60;
        if (minutes <= 0) return rest + "s";
        return minutes + "m " + String.format(java.util.Locale.ROOT, "%02d", rest) + "s";
    }

    private static final class TargetingApp {
        final String label;
        final String packageName;

        TargetingApp(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }

    private void showMessage(String text) {
        if (message != null) message.setText(text);
    }

    private static List<String> parseCidrs(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("\\n", -1)) {
            String value = line.trim();
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }

    private static String joinLines(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(value);
        }
        return sb.toString();
    }

    private static String joinComma(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(",");
            sb.append(value);
        }
        return sb.toString();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
