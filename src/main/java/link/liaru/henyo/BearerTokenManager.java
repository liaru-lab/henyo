package link.liaru.henyo;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

final class BearerTokenManager {
    static final String SCOPE_CONTROL = "control";
    static final String SCOPE_TOKEN_MANAGEMENT = "token-management";
    static final String SCOPE_TERMUX_COMMAND = "termux-command";
    private static final String PREFS = "bearer_tokens";
    private static final String KEY_RECORDS = "records";
    private static final String HASH_PREFIX = "sha256:";
    private static final int TOKEN_BYTES = 32;

    private final SharedPreferences prefs;
    private final SecureRandom random;

    BearerTokenManager(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE), new SecureRandom());
    }

    BearerTokenManager(SharedPreferences prefs, SecureRandom random) {
        this.prefs = prefs;
        this.random = random;
    }

    synchronized CreatedToken create(String name, List<String> scopes) {
        long now = System.currentTimeMillis();
        String rawToken = generateToken();
        TokenRecord record = new TokenRecord(
                UUID.randomUUID().toString(),
                cleanName(name),
                normalizeScopes(scopes),
                hashToken(rawToken),
                now,
                0,
                "",
                false,
                0);
        List<TokenRecord> records = loadRecords();
        records.add(record);
        saveRecords(records);
        return new CreatedToken(rawToken, record);
    }

    synchronized TokenRecord importToken(String rawToken, String name, List<String> scopes) {
        if (rawToken == null || rawToken.isEmpty() || rawToken.length() > 4096) {
            throw new IllegalArgumentException("token is required");
        }
        String tokenHash = hashToken(rawToken);
        List<TokenRecord> records = loadRecords();
        for (int i = 0; i < records.size(); i++) {
            TokenRecord record = records.get(i);
            if (constantTimeEquals(tokenHash, record.tokenHash)) {
                TokenRecord updated = record.withScopes(normalizeScopes(scopes));
                records.set(i, updated);
                saveRecords(records);
                return updated;
            }
        }
        TokenRecord record = new TokenRecord(
                UUID.randomUUID().toString(),
                cleanName(name),
                normalizeScopes(scopes),
                tokenHash,
                System.currentTimeMillis(),
                0,
                "",
                false,
                0);
        records.add(record);
        saveRecords(records);
        return record;
    }

    synchronized Verification verify(String token, String sourceAddress) {
        if (token == null || token.isEmpty()) return Verification.invalid();
        List<TokenRecord> records = loadRecords();
        String candidateHash = hashToken(token);
        long now = System.currentTimeMillis();
        for (int i = 0; i < records.size(); i++) {
            TokenRecord record = records.get(i);
            if (constantTimeEquals(candidateHash, record.tokenHash)) {
                if (record.revoked) return Verification.revoked(record);
                TokenRecord updated = record.withLastUsed(now, sourceAddress == null ? "" : sourceAddress);
                records.set(i, updated);
                saveRecords(records);
                return Verification.valid(updated);
            }
        }
        return Verification.invalid();
    }

    synchronized List<TokenRecord> list() {
        return Collections.unmodifiableList(loadRecords());
    }

    synchronized boolean revoke(String tokenId) {
        List<TokenRecord> records = loadRecords();
        long now = System.currentTimeMillis();
        for (int i = 0; i < records.size(); i++) {
            TokenRecord record = records.get(i);
            if (record.id.equals(tokenId)) {
                if (!record.revoked) {
                    records.set(i, record.withRevoked(now));
                    saveRecords(records);
                }
                return true;
            }
        }
        return false;
    }

    synchronized boolean setScope(String tokenId, String scope, boolean enabled) {
        if (!isKnownScope(scope)) return false;
        List<TokenRecord> records = loadRecords();
        for (int i = 0; i < records.size(); i++) {
            TokenRecord record = records.get(i);
            if (!record.id.equals(tokenId) || record.revoked) continue;
            List<String> scopes = new ArrayList<>(record.scopes);
            if (enabled && !scopes.contains(scope)) scopes.add(scope);
            if (!enabled) scopes.remove(scope);
            records.set(i, record.withScopes(normalizeScopes(scopes)));
            saveRecords(records);
            return true;
        }
        return false;
    }

    synchronized TokenRecord find(String tokenId) {
        if (tokenId == null || tokenId.isEmpty()) return null;
        for (TokenRecord record : loadRecords()) {
            if (record.id.equals(tokenId)) return record;
        }
        return null;
    }

    synchronized int count() {
        return loadRecords().size();
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HASH_PREFIX + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static boolean constantTimeEquals(String a, String b) {
        byte[] left = a == null ? new byte[0] : a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b == null ? new byte[0] : b.getBytes(StandardCharsets.UTF_8);
        int diff = left.length ^ right.length;
        int max = Math.max(left.length, right.length);
        for (int i = 0; i < max; i++) {
            byte l = i < left.length ? left[i] : 0;
            byte r = i < right.length ? right[i] : 0;
            diff |= l ^ r;
        }
        return diff == 0;
    }

    private List<TokenRecord> loadRecords() {
        String encoded = prefs.getString(KEY_RECORDS, "");
        List<TokenRecord> records = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return records;
        for (String line : encoded.split("\n")) {
            TokenRecord record = TokenRecord.decode(line);
            if (record != null) records.add(record);
        }
        return records;
    }

    private void saveRecords(List<TokenRecord> records) {
        StringBuilder out = new StringBuilder();
        for (TokenRecord record : records) {
            if (out.length() > 0) out.append("\n");
            out.append(record.encode());
        }
        prefs.edit().putString(KEY_RECORDS, out.toString()).apply();
    }

    private static String cleanName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) return "local-management";
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static List<String> normalizeScopes(List<String> scopes) {
        List<String> out = new ArrayList<>();
        if (scopes != null) {
            for (String scope : scopes) {
                String clean = scope == null ? "" : scope.trim();
                if (isKnownScope(clean) && !out.contains(clean)) {
                    out.add(clean);
                }
            }
        }
        if (out.isEmpty()) {
            out.add(SCOPE_CONTROL);
            out.add(SCOPE_TOKEN_MANAGEMENT);
        }
        return out;
    }

    private static boolean isKnownScope(String scope) {
        return SCOPE_CONTROL.equals(scope)
                || SCOPE_TOKEN_MANAGEMENT.equals(scope)
                || SCOPE_TERMUX_COMMAND.equals(scope);
    }

    static String instant(long millis) {
        if (millis <= 0) return "";
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    static final class CreatedToken {
        final String token;
        final TokenRecord record;

        CreatedToken(String token, TokenRecord record) {
            this.token = token;
            this.record = record;
        }
    }

    static final class Verification {
        final boolean ok;
        final boolean revoked;
        final TokenRecord record;

        private Verification(boolean ok, boolean revoked, TokenRecord record) {
            this.ok = ok;
            this.revoked = revoked;
            this.record = record;
        }

        static Verification valid(TokenRecord record) {
            return new Verification(true, false, record);
        }

        static Verification invalid() {
            return new Verification(false, false, null);
        }

        static Verification revoked(TokenRecord record) {
            return new Verification(false, true, record);
        }
    }

    static final class TokenRecord {
        final String id;
        final String name;
        final List<String> scopes;
        final String tokenHash;
        final long createdAt;
        final long lastUsedAt;
        final String lastSourceAddress;
        final boolean revoked;
        final long revokedAt;

        TokenRecord(String id, String name, List<String> scopes, String tokenHash, long createdAt,
                    long lastUsedAt, String lastSourceAddress, boolean revoked, long revokedAt) {
            this.id = id;
            this.name = name;
            this.scopes = Collections.unmodifiableList(new ArrayList<>(scopes));
            this.tokenHash = tokenHash;
            this.createdAt = createdAt;
            this.lastUsedAt = lastUsedAt;
            this.lastSourceAddress = lastSourceAddress == null ? "" : lastSourceAddress;
            this.revoked = revoked;
            this.revokedAt = revokedAt;
        }

        TokenRecord withLastUsed(long when, String sourceAddress) {
            return new TokenRecord(id, name, scopes, tokenHash, createdAt, when, sourceAddress, revoked, revokedAt);
        }

        TokenRecord withRevoked(long when) {
            return new TokenRecord(id, name, scopes, tokenHash, createdAt, lastUsedAt, lastSourceAddress, true, when);
        }

        TokenRecord withScopes(List<String> updatedScopes) {
            return new TokenRecord(id, name, updatedScopes, tokenHash, createdAt, lastUsedAt,
                    lastSourceAddress, revoked, revokedAt);
        }

        boolean hasScope(String scope) {
            return scopes.contains(scope);
        }

        String encode() {
            return encodePart(id) + "\t" +
                    encodePart(name) + "\t" +
                    encodePart(joinScopes(scopes)) + "\t" +
                    encodePart(tokenHash) + "\t" +
                    createdAt + "\t" +
                    lastUsedAt + "\t" +
                    encodePart(lastSourceAddress) + "\t" +
                    revoked + "\t" +
                    revokedAt;
        }

        static TokenRecord decode(String line) {
            if (line == null || line.isEmpty()) return null;
            String[] parts = line.split("\t", -1);
            if (parts.length != 9) return null;
            try {
                return new TokenRecord(
                        decodePart(parts[0]),
                        decodePart(parts[1]),
                        parseScopes(decodePart(parts[2])),
                        decodePart(parts[3]),
                        Long.parseLong(parts[4]),
                        Long.parseLong(parts[5]),
                        decodePart(parts[6]),
                        Boolean.parseBoolean(parts[7]),
                        Long.parseLong(parts[8]));
            } catch (Exception ignored) {
                return null;
            }
        }

        String metadataJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            jsonField(sb, "id", id);
            sb.append(",");
            jsonField(sb, "name", name);
            sb.append(",\"scopes\":[");
            for (int i = 0; i < scopes.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(jsonEscape(scopes.get(i))).append("\"");
            }
            sb.append("]");
            sb.append(",");
            jsonField(sb, "createdAt", instant(createdAt));
            if (lastUsedAt > 0) {
                sb.append(",");
                jsonField(sb, "lastUsedAt", instant(lastUsedAt));
            }
            if (!lastSourceAddress.isEmpty()) {
                sb.append(",");
                jsonField(sb, "lastSourceAddress", lastSourceAddress);
            }
            sb.append(",\"revoked\":").append(revoked);
            if (revokedAt > 0) {
                sb.append(",");
                jsonField(sb, "revokedAt", instant(revokedAt));
            }
            sb.append("}");
            return sb.toString();
        }

        private static List<String> parseScopes(String value) {
            List<String> scopes = new ArrayList<>();
            if (value == null || value.isEmpty()) return scopes;
            for (String scope : value.split(",", -1)) {
                if (!scope.isEmpty()) scopes.add(scope);
            }
            return scopes;
        }

        private static String joinScopes(List<String> scopes) {
            StringBuilder out = new StringBuilder();
            for (String scope : scopes) {
                if (out.length() > 0) out.append(",");
                out.append(scope);
            }
            return out.toString();
        }

        private static String encodePart(String value) {
            return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        }

        private static String decodePart(String value) {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        }
    }

    private static void jsonField(StringBuilder sb, String name, String value) {
        sb.append("\"").append(name).append("\":\"").append(jsonEscape(value)).append("\"");
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
}
