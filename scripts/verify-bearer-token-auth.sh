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

tmp="${TMPDIR:-/tmp}/henyo-bearer-token-$$"
mkdir -p "$tmp/link/liaru/henyo"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/link/liaru/henyo/BearerTokenManagerVerifier.java" <<'JAVA'
package link.liaru.henyo;

import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class BearerTokenManagerVerifier {
    public static void main(String[] args) {
        MemoryPrefs prefs = new MemoryPrefs();
        BearerTokenManager manager = new BearerTokenManager(prefs, new SecureRandom());

        BearerTokenManager.CreatedToken created = manager.create("Verifier", null);
        expect(created.token.length() >= 32, true, "raw token generated");
        expect(manager.list().size(), 1, "token listed");
        expect(prefs.getString("records", "").contains(created.token), false, "raw token not stored");
        expect(manager.list().get(0).tokenHash.startsWith("sha256:"), true, "hash stored");
        expect(manager.list().get(0).hasScope(BearerTokenManager.SCOPE_TERMUX_COMMAND), false,
                "Termux commands default disabled");
        expect(manager.list().get(0).hasScope(BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL), false,
                "sensitive UI control default disabled");
        expect(manager.hasActiveScope(created.token, BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL), false,
                "sensitive UI access denied by default");
        expect(manager.setScope(created.record.id, BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL, true), true,
                "sensitive UI scope enabled locally");
        expect(manager.hasActiveScope(created.token, BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL), true,
                "sensitive UI scope authorizes active token");
        expect(manager.setScope(created.record.id, BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL, false), true,
                "sensitive UI scope disabled locally");
        expect(manager.hasActiveScope(created.token, BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL), false,
                "disabled sensitive UI scope takes effect immediately");
        expect(manager.setScope(created.record.id, BearerTokenManager.SCOPE_TERMUX_COMMAND, true), true,
                "Termux command scope enabled locally");
        expect(manager.find(created.record.id).hasScope(BearerTokenManager.SCOPE_TERMUX_COMMAND), true,
                "Termux command scope persisted");
        expect(manager.setScope(created.record.id, BearerTokenManager.SCOPE_TERMUX_COMMAND, false), true,
                "Termux command scope disabled locally");

        BearerTokenManager.Verification verified = manager.verify(created.token, "100.64.0.9");
        expect(verified.ok, true, "valid token accepted");
        BearerTokenManager.TokenRecord used = manager.list().get(0);
        expect(used.lastUsedAt > 0, true, "last used set");
        expect(used.lastSourceAddress, "100.64.0.9", "last source set");

        BearerTokenManager.Verification invalid = manager.verify(created.token + "x", "100.64.0.9");
        expect(invalid.ok, false, "invalid token rejected");
        expect(invalid.revoked, false, "invalid token not reported revoked");

        expect(manager.revoke(created.record.id), true, "token revoked");
        expect(manager.list().get(0).revoked, true, "revocation listed");
        BearerTokenManager.Verification revoked = manager.verify(created.token, "100.64.0.9");
        expect(revoked.ok, false, "revoked token rejected");
        expect(revoked.revoked, true, "revoked token identified");
        expect(manager.hasActiveScope(created.token, BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL), false,
                "revoked token cannot use sensitive UI scope");

        expect(BearerTokenManager.constantTimeEquals("abc", "abc"), true, "constant-time equal");
        expect(BearerTokenManager.constantTimeEquals("abc", "abcd"), false, "constant-time unequal length");
        expect(BearerAuthPolicy.parse(null).status, BearerAuthPolicy.AUTH_MISSING, "missing auth");
        expect(BearerAuthPolicy.parse("Basic nope").status, BearerAuthPolicy.AUTH_MALFORMED, "malformed auth");
        expect(BearerAuthPolicy.parse("Bearer abc.def").token, "abc.def", "bearer token parsed");
        expect(BearerAuthPolicy.parse("Bearer abc def").status, BearerAuthPolicy.AUTH_MALFORMED, "bearer whitespace rejected");
        expect(BearerAuthPolicy.requiresRemoteBearer("GET", "/v1/app/current"), true, "remote app auth required");
        expect(BearerAuthPolicy.requiresRemoteBearer("GET", "/v1/auth/tokens"), true, "remote token list auth required");
        expect(BearerAuthPolicy.requiresRemoteBearer("DELETE", "/v1/auth/tokens/id"), true, "remote token revoke auth required");
        expect(BearerAuthPolicy.requiresRemoteBearer("POST", "/v1/termux/exec"), true, "remote Termux auth required");
        expect(BearerAuthPolicy.requiresRemoteBearer("GET", "/v1/health"), false, "remote health auth not required");
        expect(BearerAuthPolicy.requiresRemoteBearer("POST", "/v1/auth/tokens/local"), false, "local bootstrap not remote auth endpoint");

        expect(SensitiveUiAccessPolicy.protectsOperation("ui.click"), true, "UI WS operation protected");
        expect(SensitiveUiAccessPolicy.protectsOperation("screen.screenshot"), true, "screenshot WS operation protected");
        expect(SensitiveUiAccessPolicy.protectsOperation("app.current"), false, "ordinary WS operation unchanged");
        expect(SensitiveUiAccessPolicy.protectsHttpPath("/v1/ui/tree"), true, "v1 UI endpoint protected");
        expect(SensitiveUiAccessPolicy.protectsHttpPath("/v1/screen/screenshot"), true, "v1 screenshot endpoint protected");
        expect(SensitiveUiAccessPolicy.protectsHttpPath("/ui/tree"), true, "legacy UI endpoint protected");
        expect(SensitiveUiAccessPolicy.protectsHttpPath("/screen/screenshot"), true, "legacy screenshot endpoint protected");
        expect(SensitiveUiAccessPolicy.protectsHttpPath("/v1/app/current"), false, "ordinary HTTP endpoint unchanged");
        expect(SensitiveUiAccessPolicy.allows(false, true, false), true, "ordinary UI remains available");
        expect(SensitiveUiAccessPolicy.allows(true, false, false), true, "trusted local access remains available");
        expect(SensitiveUiAccessPolicy.allows(true, true, false), false, "paired client denied by default");
        expect(SensitiveUiAccessPolicy.allows(true, true, true), true, "locally authorized paired client allowed");

        System.out.println("bearer token manager verifier passed");
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

    private static final class MemoryPrefs implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemoryEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                pending.put(key, new HashSet<>(values));
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removals.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                values.putAll(pending);
            }
        }
    }
}
JAVA

javac -encoding UTF-8 -source 11 -target 11 \
  -classpath "$android_jar" \
  -d "$tmp" \
  src/main/java/link/liaru/henyo/BearerAuthPolicy.java \
  src/main/java/link/liaru/henyo/BearerTokenManager.java \
  src/main/java/link/liaru/henyo/WsOperation.java \
  src/main/java/link/liaru/henyo/SensitiveUiAccessPolicy.java \
  "$tmp/link/liaru/henyo/BearerTokenManagerVerifier.java"

java -cp "$tmp:$android_jar" link.liaru.henyo.BearerTokenManagerVerifier
