package link.liaru.henyo;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class PairingSessionManager {
    static final int PIN_DIGITS = 6;
    static final int STEP_SECONDS = 30;
    static final int PAST_PIN_STEPS = 6;
    static final int FUTURE_PIN_STEPS = 1;
    static final int DEFAULT_TTL_SECONDS = 300;
    static final int MIN_TTL_SECONDS = 30;
    static final int MAX_TTL_SECONDS = 900;
    static final int MAX_FAILURES = 5;

    private static final int SECRET_BYTES = 20;
    private static final PairingSessionManager INSTANCE = new PairingSessionManager(new SecureRandom());

    private final SecureRandom random;
    private Session session;

    PairingSessionManager(SecureRandom random) {
        this.random = random;
    }

    static PairingSessionManager get() {
        return INSTANCE;
    }

    synchronized StartResult start(Integer ttlSeconds, String clientHint) {
        return startAt(ttlSeconds, clientHint, System.currentTimeMillis());
    }

    synchronized StartResult startAt(Integer ttlSeconds, String clientHint, long now) {
        if (isActiveLocked(now)) {
            return StartResult.fail("pairing_already_active", statusLocked(now));
        }
        int ttl = clampTtl(ttlSeconds == null ? DEFAULT_TTL_SECONDS : ttlSeconds);
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        session = new Session(
                UUID.randomUUID().toString(),
                secret,
                now,
                now + ttl * 1000L,
                cleanHint(clientHint),
                0,
                STATE_ACTIVE);
        return StartResult.ok(statusLocked(now));
    }

    synchronized Status cancel() {
        return cancelAt(System.currentTimeMillis());
    }

    synchronized Status cancelAt(long now) {
        if (session != null && STATE_ACTIVE.equals(session.state) && now < session.expiresAt) {
            session = session.withState(STATE_CANCELLED);
        }
        return statusLocked(now);
    }

    synchronized Status status() {
        return statusAt(System.currentTimeMillis());
    }

    synchronized Status statusAt(long now) {
        return statusLocked(now);
    }

    synchronized String currentPinForDisplay() {
        return currentPinForDisplayAt(System.currentTimeMillis());
    }

    synchronized String currentPinForDisplayAt(long now) {
        if (!isActiveLocked(now)) return "";
        return pinAt(session.secret, now / 1000L / STEP_SECONDS);
    }

    synchronized Display displayStatus() {
        return displayStatusAt(System.currentTimeMillis());
    }

    synchronized Display displayStatusAt(long now) {
        Status current = statusLocked(now);
        if (!current.active || session == null) {
            return new Display(current, "", "", 0, STEP_SECONDS, 0f);
        }
        long nowSeconds = now / 1000L;
        long step = nowSeconds / STEP_SECONDS;
        int elapsed = (int) (nowSeconds % STEP_SECONDS);
        int remaining = Math.max(1, STEP_SECONDS - elapsed);
        float progress = Math.max(0f, Math.min(1f, remaining / (float) STEP_SECONDS));
        return new Display(
                current,
                pinAt(session.secret, step),
                pinAt(session.secret, step + 1),
                remaining,
                STEP_SECONDS,
                progress);
    }

    synchronized RegisterResult register(String pairingId, String pin) {
        return registerAt(pairingId, pin, System.currentTimeMillis());
    }

    synchronized RegisterResult registerAt(String pairingId, String pin, long now) {
        Status current = statusLocked(now);
        if (!current.active || session == null) {
            return RegisterResult.fail(errorForState(current.state));
        }
        if (!session.id.equals(pairingId == null ? "" : pairingId.trim())) {
            return failedAttemptLocked(now);
        }
        if (pin == null || !pin.matches("^[0-9]{6}$")) {
            return failedAttemptLocked(now);
        }
        long step = now / 1000L / STEP_SECONDS;
        boolean ok = false;
        for (long offset = -PAST_PIN_STEPS; offset <= FUTURE_PIN_STEPS; offset++) {
            if (BearerTokenManager.constantTimeEquals(pin, pinAt(session.secret, step + offset))) {
                ok = true;
                break;
            }
        }
        if (!ok) return failedAttemptLocked(now);
        session = session.withState(STATE_USED);
        return RegisterResult.ok();
    }

    private RegisterResult failedAttemptLocked(long now) {
        if (session == null) return RegisterResult.fail("pairing_required");
        int failures = session.failedAttempts + 1;
        String nextState = failures >= MAX_FAILURES ? STATE_LOCKED : session.state;
        session = session.withFailures(failures, nextState);
        return RegisterResult.fail(STATE_LOCKED.equals(nextState) ? "pairing_rate_limited" : "pairing_pin_invalid");
    }

    private Status statusLocked(long now) {
        if (session == null) return Status.inactive(STATE_INACTIVE);
        if (STATE_ACTIVE.equals(session.state) && now >= session.expiresAt) {
            session = session.withState(STATE_EXPIRED);
        }
        boolean active = isActiveLocked(now);
        return new Status(
                active,
                session.id,
                session.createdAt,
                session.expiresAt,
                Math.max(0, MAX_FAILURES - session.failedAttempts),
                session.state,
                session.clientHint);
    }

    private boolean isActiveLocked(long now) {
        return session != null && STATE_ACTIVE.equals(session.state) && now < session.expiresAt;
    }

    private static int clampTtl(int ttlSeconds) {
        return Math.max(MIN_TTL_SECONDS, Math.min(MAX_TTL_SECONDS, ttlSeconds));
    }

    private static String cleanHint(String clientHint) {
        String value = clientHint == null ? "" : clientHint.trim();
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    static String pinAt(byte[] secret, long timeStep) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(timeStep).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1000000);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP unavailable", e);
        }
    }

    static String instant(long millis) {
        if (millis <= 0) return "";
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static String errorForState(String state) {
        if (STATE_EXPIRED.equals(state)) return "pairing_expired";
        if (STATE_CANCELLED.equals(state)) return "pairing_cancelled";
        if (STATE_USED.equals(state)) return "pairing_used";
        if (STATE_LOCKED.equals(state)) return "pairing_rate_limited";
        if (STATE_ACTIVE.equals(state)) return "pairing_pin_invalid";
        return "pairing_required";
    }

    static final String STATE_INACTIVE = "inactive";
    static final String STATE_ACTIVE = "active";
    static final String STATE_EXPIRED = "expired";
    static final String STATE_CANCELLED = "cancelled";
    static final String STATE_USED = "used";
    static final String STATE_LOCKED = "locked";

    static final class Status {
        final boolean active;
        final String pairingId;
        final long createdAt;
        final long expiresAt;
        final int attemptsRemaining;
        final String state;
        final String clientHint;

        Status(boolean active, String pairingId, long createdAt, long expiresAt,
               int attemptsRemaining, String state, String clientHint) {
            this.active = active;
            this.pairingId = pairingId;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.attemptsRemaining = attemptsRemaining;
            this.state = state;
            this.clientHint = clientHint == null ? "" : clientHint;
        }

        static Status inactive(String state) {
            return new Status(false, "", 0, 0, MAX_FAILURES, state, "");
        }
    }

    static final class Display {
        final Status status;
        final String currentPin;
        final String nextPin;
        final int pinSecondsRemaining;
        final int pinStepSeconds;
        final float pinProgress;

        Display(Status status, String currentPin, String nextPin,
                int pinSecondsRemaining, int pinStepSeconds, float pinProgress) {
            this.status = status;
            this.currentPin = currentPin == null ? "" : currentPin;
            this.nextPin = nextPin == null ? "" : nextPin;
            this.pinSecondsRemaining = pinSecondsRemaining;
            this.pinStepSeconds = pinStepSeconds;
            this.pinProgress = pinProgress;
        }
    }

    static final class RegisterResult {
        final boolean ok;
        final String error;

        private RegisterResult(boolean ok, String error) {
            this.ok = ok;
            this.error = error;
        }

        static RegisterResult ok() {
            return new RegisterResult(true, "");
        }

        static RegisterResult fail(String error) {
            return new RegisterResult(false, error);
        }
    }

    static final class StartResult {
        final boolean ok;
        final String error;
        final Status status;

        private StartResult(boolean ok, String error, Status status) {
            this.ok = ok;
            this.error = error;
            this.status = status;
        }

        static StartResult ok(Status status) {
            return new StartResult(true, "", status);
        }

        static StartResult fail(String error, Status status) {
            return new StartResult(false, error, status);
        }
    }

    private static final class Session {
        final String id;
        final byte[] secret;
        final long createdAt;
        final long expiresAt;
        final String clientHint;
        final int failedAttempts;
        final String state;

        Session(String id, byte[] secret, long createdAt, long expiresAt,
                String clientHint, int failedAttempts, String state) {
            this.id = id;
            this.secret = secret.clone();
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.clientHint = clientHint;
            this.failedAttempts = failedAttempts;
            this.state = state;
        }

        Session withFailures(int failures, String nextState) {
            return new Session(id, secretForState(nextState), createdAt, expiresAt, clientHint, failures, nextState);
        }

        Session withState(String nextState) {
            return new Session(id, secretForState(nextState), createdAt, expiresAt, clientHint, failedAttempts, nextState);
        }

        private byte[] secretForState(String nextState) {
            return STATE_ACTIVE.equals(nextState) ? secret : new byte[0];
        }
    }
}
