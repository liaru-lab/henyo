#!/usr/bin/env python3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SERVICE = (ROOT / "src/main/java/link/liaru/henyo/HenyoAccessibilityService.java").read_text()
WATCHDOG = (ROOT / "src/main/java/link/liaru/henyo/TailscaleWatchdog.java").read_text()
ACTIVITY = (ROOT / "src/main/java/link/liaru/henyo/MainActivity.java").read_text()
MANIFEST = (ROOT / "src/main/AndroidManifest.xml").read_text()


def require(source: str, marker: str, message: str) -> None:
    if marker not in source:
        raise AssertionError(message)


require(SERVICE, "clientExecutor.execute(() -> handleClient(client))",
        "accepted clients must run independently")
require(SERVICE, "new SynchronousQueue<>()", "long-lived clients must not block health requests in a queue")
require(SERVICE, "RejectedExecutionException", "overloaded listeners must reject safely")
require(SERVICE, "clientSockets", "live clients must be closed during listener restart")
require(SERVICE, "synchronized (controlExecutionLock)", "concurrent control calls must be serialized")
require(WATCHDOG, "NetworkCapabilities.TRANSPORT_VPN", "watchdog must inspect Android VPN state")
require(WATCHDOG, "CHECK_INTERVAL_MS = 60_000L", "watchdog must check periodically")
require(WATCHDOG, "scheduleWithFixedDelay(this::safeCheck, 5_000L", "watchdog failures must not cancel future checks")
require(WATCHDOG, "RECOVERY_COOLDOWN_MS = 120_000L", "recovery attempts must be rate limited")
require(WATCHDOG, "getLaunchIntentForPackage(TAILSCALE_PACKAGE)", "watchdog must launch Tailscale")
require(WATCHDOG, "GLOBAL_ACTION_BACK", "watchdog must restore the previous foreground app")
require(WATCHDOG, "RemoteAccessConfig.load(service).enabled", "watchdog must respect remote access")
require(ACTIVITY, "Auto-recover Tailscale VPN", "watchdog must have a local UI switch")
require(SERVICE, '\\"tailscaleWatchdog\\":', "health must expose watchdog state")
require(MANIFEST, "android.permission.ACCESS_NETWORK_STATE", "VPN inspection requires network state access")

print("connectivity watchdog and concurrent listener verifier passed")
