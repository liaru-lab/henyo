package link.liaru.henyo;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class TailscaleWatchdog {
    static final String TAILSCALE_PACKAGE = "com.tailscale.ipn";
    static final String PREFS = "connectivity_watchdog";
    static final String KEY_ENABLED = "tailscale_enabled";
    static final long CHECK_INTERVAL_MS = 60_000L;
    static final long RECOVERY_COOLDOWN_MS = 120_000L;
    static final long RESTORE_DELAY_MS = 8_000L;

    private final HenyoAccessibilityService service;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "henyo-tailscale-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean stopped;
    private volatile boolean vpnConnected;
    private volatile long lastCheckTime;
    private volatile long lastRecoveryTime;
    private volatile long lastRecoveryElapsed;
    private volatile int recoveryAttempts;
    private volatile String lastError = "";

    TailscaleWatchdog(HenyoAccessibilityService service) {
        this.service = service;
    }

    void start() {
        executor.scheduleWithFixedDelay(this::safeCheck, 5_000L, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    void stop() {
        stopped = true;
        executor.shutdownNow();
    }

    void checkSoon() {
        if (!stopped) executor.execute(this::safeCheck);
    }

    private void safeCheck() {
        try {
            check();
        } catch (RuntimeException error) {
            lastCheckTime = System.currentTimeMillis();
            lastError = safeMessage(error);
        }
    }

    private void check() {
        if (stopped) return;
        lastCheckTime = System.currentTimeMillis();
        vpnConnected = hasVpnTransport(service);
        if (!isEnabled(service) || !RemoteAccessConfig.load(service).enabled || vpnConnected) {
            if (vpnConnected) lastError = "";
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastRecoveryElapsed < RECOVERY_COOLDOWN_MS) return;
        lastRecoveryElapsed = now;
        lastRecoveryTime = System.currentTimeMillis();
        recoveryAttempts++;
        String previousPackage = foregroundPackage();
        Intent launch = service.getPackageManager().getLaunchIntentForPackage(TAILSCALE_PACKAGE);
        if (launch == null) {
            lastError = "tailscale_not_installed";
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            service.startActivity(launch);
            lastError = "";
            executor.schedule(() -> verifyAndRestore(previousPackage), RESTORE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException error) {
            lastError = safeMessage(error);
        }
    }

    private void verifyAndRestore(String previousPackage) {
        vpnConnected = hasVpnTransport(service);
        lastCheckTime = System.currentTimeMillis();
        if (!vpnConnected) {
            lastError = "vpn_not_restored";
            return;
        }
        lastError = "";
        if (!TAILSCALE_PACKAGE.equals(previousPackage)
                && TAILSCALE_PACKAGE.equals(foregroundPackage())) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        }
    }

    private String foregroundPackage() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return "";
        return root.getPackageName().toString();
    }

    static boolean hasVpnTransport(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
    }

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String statusJson() {
        return "{\"enabled\":" + isEnabled(service) +
                ",\"vpnConnected\":" + vpnConnected +
                ",\"lastCheckTime\":" + lastCheckTime +
                ",\"lastRecoveryTime\":" + lastRecoveryTime +
                ",\"recoveryAttempts\":" + recoveryAttempts +
                ",\"lastError\":\"" + escape(lastError) + "\"}";
    }

    static String disabledStatusJson(Context context) {
        return "{\"enabled\":" + isEnabled(context) +
                ",\"vpnConnected\":false,\"lastCheckTime\":0,\"lastRecoveryTime\":0," +
                "\"recoveryAttempts\":0,\"lastError\":\"service_unavailable\"}";
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName().toLowerCase(Locale.ROOT) : message;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
