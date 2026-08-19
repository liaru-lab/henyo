#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

android_jar=${HENYO_ANDROID_JAR:-}
if [ -z "$android_jar" ] && [ -n "${ANDROID_HOME:-}" ]; then
    android_jar="$ANDROID_HOME/platforms/android-30/android.jar"
fi
if [ -z "$android_jar" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    android_jar="$ANDROID_SDK_ROOT/platforms/android-30/android.jar"
fi
android_jar=${android_jar:-sdk/android-11/android.jar}

tmp="${TMPDIR:-/tmp}/henyo-pairing-totp-$$"
mkdir -p "$tmp/link/liaru/henyo"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/link/liaru/henyo/PairingSessionManagerVerifier.java" <<'JAVA'
package link.liaru.henyo;

import java.security.SecureRandom;
import java.util.Arrays;

public final class PairingSessionManagerVerifier {
    public static void main(String[] args) {
        assertTotpVectors();
        assertRegisterLifecycle();
        assertAcceptanceWindow();
        assertDisplayStatus();
        assertCancelAndExpiry();
        assertRateLimit();
        assertSourceInvariants();
        System.out.println("pairing TOTP and lifecycle verifier passed");
    }

    private static void assertTotpVectors() {
        byte[] secret = "12345678901234567890".getBytes();
        expect(PairingSessionManager.pinAt(secret, 59L / 30L), "287082", "RFC vector 59");
        expect(PairingSessionManager.pinAt(secret, 1111111109L / 30L), "081804", "RFC vector 1111111109");
        expect(PairingSessionManager.pinAt(secret, 1111111111L / 30L), "050471", "RFC vector 1111111111");
        expect(PairingSessionManager.pinAt(secret, 1234567890L / 30L), "005924", "RFC vector 1234567890");
        expect(PairingSessionManager.pinAt(secret, 2000000000L / 30L), "279037", "RFC vector 2000000000");
        expect(PairingSessionManager.pinAt(secret, 20000000000L / 30L), "353130", "RFC vector 20000000000");
    }

    private static void assertRegisterLifecycle() {
        PairingSessionManager manager = new PairingSessionManager(new FixedRandom());
        long now = 1_800_000_000_000L;
        PairingSessionManager.StartResult start = manager.startAt(300, "verifier", now);
        expect(start.ok, true, "start succeeds");
        PairingSessionManager.Status started = start.status;
        expect(started.active, true, "pairing active");
        expect(manager.statusAt(now).pairingId, started.pairingId, "status exposes pairing id");
        String pin = manager.currentPinForDisplayAt(now);
        expect(pin.matches("^[0-9]{6}$"), true, "pin shape");

        PairingSessionManager.StartResult duplicate = manager.startAt(300, "duplicate", now + 1000L);
        expect(duplicate.ok, false, "duplicate active start fails");
        expect(duplicate.error, "pairing_already_active", "duplicate active start error");
        expect(duplicate.status.pairingId, started.pairingId, "duplicate start keeps active pairing");

        PairingSessionManager.RegisterResult ok = manager.registerAt(started.pairingId, pin, now);
        expect(ok.ok, true, "register succeeds");
        expect(manager.statusAt(now).state, PairingSessionManager.STATE_USED, "used after success");
        expect(manager.currentPinForDisplayAt(now), "", "pin hidden after success");

        PairingSessionManager.RegisterResult reused = manager.registerAt(started.pairingId, pin, now);
        expect(reused.ok, false, "reuse fails");
        expect(reused.error, "pairing_used", "reuse error");
    }

    private static void assertCancelAndExpiry() {
        PairingSessionManager cancelled = new PairingSessionManager(new FixedRandom());
        long now = 1_800_000_000_000L;
        PairingSessionManager.Status started = cancelled.startAt(300, "cancel", now).status;
        String pin = cancelled.currentPinForDisplayAt(now);
        cancelled.cancelAt(now + 1000L);
        expect(cancelled.statusAt(now + 1000L).state, PairingSessionManager.STATE_CANCELLED, "cancelled state");
        expect(cancelled.currentPinForDisplayAt(now + 1000L), "", "pin hidden after cancel");
        expect(cancelled.registerAt(started.pairingId, pin, now + 1000L).error, "pairing_cancelled", "cancelled register error");

        PairingSessionManager expired = new PairingSessionManager(new FixedRandom());
        PairingSessionManager.Status shortPairing = expired.startAt(-1, "expire", now).status;
        long expiredAt = now + PairingSessionManager.MIN_TTL_SECONDS * 1000L;
        expect(expired.statusAt(expiredAt).state, PairingSessionManager.STATE_EXPIRED, "expired state");
        expect(expired.currentPinForDisplayAt(expiredAt), "", "pin hidden after expiry");
        expect(expired.registerAt(shortPairing.pairingId, "000000", expiredAt).error, "pairing_expired", "expired register error");
    }

    private static void assertAcceptanceWindow() {
        long now = 1_800_000_000_000L;

        PairingSessionManager oldestAccepted = new PairingSessionManager(new FixedRandom());
        PairingSessionManager.Status acceptedStatus = oldestAccepted.startAt(300, "oldest-accepted", now).status;
        String acceptedPin = oldestAccepted.currentPinForDisplayAt(now);
        PairingSessionManager.RegisterResult accepted = oldestAccepted.registerAt(
                acceptedStatus.pairingId,
                acceptedPin,
                now + PairingSessionManager.PAST_PIN_STEPS * PairingSessionManager.STEP_SECONDS * 1000L);
        expect(accepted.ok, true, "six-step-old pin accepted");

        PairingSessionManager tooOld = new PairingSessionManager(new FixedRandom());
        PairingSessionManager.Status rejectedStatus = tooOld.startAt(300, "too-old", now).status;
        String rejectedPin = tooOld.currentPinForDisplayAt(now);
        PairingSessionManager.RegisterResult rejected = tooOld.registerAt(
                rejectedStatus.pairingId,
                rejectedPin,
                now + (PairingSessionManager.PAST_PIN_STEPS + 1L) * PairingSessionManager.STEP_SECONDS * 1000L);
        expect(rejected.ok, false, "seven-step-old pin rejected");
        expect(rejected.error, "pairing_pin_invalid", "too-old pin error");

        PairingSessionManager nextAccepted = new PairingSessionManager(new FixedRandom());
        PairingSessionManager.Status nextStatus = nextAccepted.startAt(300, "next", now).status;
        String nextPin = nextAccepted.displayStatusAt(now).nextPin;
        expect(nextAccepted.registerAt(nextStatus.pairingId, nextPin, now).ok, true, "next pin remains accepted");
    }

    private static void assertDisplayStatus() {
        PairingSessionManager manager = new PairingSessionManager(new FixedRandom());
        long now = 1_800_000_000_000L;
        PairingSessionManager.Status started = manager.startAt(300, "display", now).status;
        PairingSessionManager.Display display = manager.displayStatusAt(now + 24_000L);
        expect(display.status.pairingId, started.pairingId, "display status pairing id");
        expect(display.currentPin.matches("^[0-9]{6}$"), true, "display current pin shape");
        expect(display.nextPin.matches("^[0-9]{6}$"), true, "display next pin shape");
        expect(display.currentPin.equals(display.nextPin), false, "display next pin differs");
        expect(display.pinStepSeconds, PairingSessionManager.STEP_SECONDS, "display step seconds");
        expect(display.pinSecondsRemaining, 6, "display seconds remaining");
        expect(display.pinProgress > 0f && display.pinProgress <= 1f, true, "display progress range");
    }

    private static void assertRateLimit() {
        PairingSessionManager manager = new PairingSessionManager(new FixedRandom());
        long now = 1_800_000_000_000L;
        PairingSessionManager.Status started = manager.startAt(300, "failures", now).status;
        PairingSessionManager.RegisterResult last = null;
        for (int i = 0; i < PairingSessionManager.MAX_FAILURES; i++) {
            last = manager.registerAt(started.pairingId, "000000", now);
        }
        expect(last.ok, false, "last failure rejected");
        expect(last.error, "pairing_rate_limited", "rate limited error");
        expect(manager.statusAt(now).state, PairingSessionManager.STATE_LOCKED, "locked state");
        expect(manager.currentPinForDisplayAt(now), "", "pin hidden after lock");
    }

    private static void assertSourceInvariants() {
        expect(PairingSessionManager.PIN_DIGITS, 6, "pin digits");
        expect(PairingSessionManager.STEP_SECONDS, 30, "step seconds");
        expect(PairingSessionManager.PAST_PIN_STEPS, 6, "past pin steps");
        expect(PairingSessionManager.FUTURE_PIN_STEPS, 1, "future pin steps");
        expect(PairingSessionManager.MAX_FAILURES, 5, "max failures");
    }

    private static void expect(Object actual, Object expected, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static void expect(boolean actual, boolean expected, String label) {
        if (actual != expected) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static final class FixedRandom extends SecureRandom {
        private int next = 1;

        @Override
        public void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, (byte) next++);
        }
    }
}
JAVA

javac -encoding UTF-8 -source 11 -target 11 \
  -classpath "$android_jar" \
  -d "$tmp" \
  src/main/java/link/liaru/henyo/BearerTokenManager.java \
  src/main/java/link/liaru/henyo/PairingSessionManager.java \
  "$tmp/link/liaru/henyo/PairingSessionManagerVerifier.java"

java -cp "$tmp" link.liaru.henyo.PairingSessionManagerVerifier
