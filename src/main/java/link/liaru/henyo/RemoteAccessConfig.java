package link.liaru.henyo;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class RemoteAccessConfig {
    public static final boolean DEFAULT_ENABLED = false;
    public static final String DEFAULT_BIND_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8765;
    public static final boolean DEFAULT_REQUIRE_AUTH = true;
    public static final List<String> DEFAULT_ALLOWED_CIDRS;

    private static final String PREFS = "remote_access";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BIND_HOST = "bind_host";
    private static final String KEY_PORT = "port";
    private static final String KEY_ALLOWED_CIDRS = "allowed_cidrs";
    private static final String KEY_REQUIRE_AUTH = "require_auth";

    static {
        List<String> cidrs = new ArrayList<>();
        cidrs.add("100.64.0.0/10");
        cidrs.add("fd7a:115c:a1e0::/48");
        DEFAULT_ALLOWED_CIDRS = Collections.unmodifiableList(cidrs);
    }

    public final boolean enabled;
    public final String bindHost;
    public final int port;
    public final List<String> allowedCidrs;
    public final boolean requireAuth;

    private RemoteAccessConfig(boolean enabled, String bindHost, int port, List<String> allowedCidrs, boolean requireAuth) {
        this.enabled = enabled;
        this.bindHost = bindHost;
        this.port = port;
        this.allowedCidrs = Collections.unmodifiableList(new ArrayList<>(allowedCidrs));
        this.requireAuth = requireAuth;
    }

    public static RemoteAccessConfig defaults() {
        return new RemoteAccessConfig(DEFAULT_ENABLED, DEFAULT_BIND_HOST, DEFAULT_PORT, DEFAULT_ALLOWED_CIDRS, DEFAULT_REQUIRE_AUTH);
    }

    public static RemoteAccessConfig load(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return create(
                prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
                prefs.getString(KEY_BIND_HOST, DEFAULT_BIND_HOST),
                prefs.getInt(KEY_PORT, DEFAULT_PORT),
                decodeCidrs(prefs.getString(KEY_ALLOWED_CIDRS, encodeCidrs(DEFAULT_ALLOWED_CIDRS))),
                prefs.getBoolean(KEY_REQUIRE_AUTH, DEFAULT_REQUIRE_AUTH));
    }

    public static RemoteAccessConfig save(Context context, Update update) throws ValidationException {
        RemoteAccessConfig current = load(context);
        RemoteAccessConfig next = current.with(update);
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(KEY_ENABLED, next.enabled)
                .putString(KEY_BIND_HOST, next.bindHost)
                .putInt(KEY_PORT, next.port)
                .putString(KEY_ALLOWED_CIDRS, encodeCidrs(next.allowedCidrs))
                .putBoolean(KEY_REQUIRE_AUTH, next.requireAuth)
                .apply();
        return next;
    }

    public RemoteAccessConfig with(Update update) throws ValidationException {
        return validated(new RemoteAccessConfig(
                update.enabled == null ? enabled : update.enabled,
                update.bindHost == null ? bindHost : normalizeHost(update.bindHost),
                update.port == null ? port : update.port,
                update.allowedCidrs == null ? allowedCidrs : normalizeCidrs(update.allowedCidrs),
                update.requireAuth == null ? requireAuth : update.requireAuth));
    }

    public String effectiveBindHost() {
        return enabled ? bindHost : DEFAULT_BIND_HOST;
    }

    public boolean effectiveRemoteEnabled() {
        return enabled && !isLoopbackHost(bindHost);
    }

    public String summaryJson() {
        return summaryJson(DEFAULT_BIND_HOST, false);
    }

    public String summaryJson(String actualBindHost, boolean remoteServing) {
        return "{" +
                "\"enabled\":" + enabled + "," +
                "\"bindHost\":\"" + jsonEscape(actualBindHost) + "\"," +
                "\"bindPort\":" + port + "," +
                "\"allowedCidrs\":" + cidrsJson(allowedCidrs) + "," +
                "\"requireAuth\":" + requireAuth + "," +
                "\"remoteServing\":" + remoteServing +
                "}";
    }

    public String statusJson() {
        return statusJson(DEFAULT_BIND_HOST, false);
    }

    public String statusJson(String actualBindHost, boolean remoteServing) {
        return "{" +
                "\"enabled\":" + enabled + "," +
                "\"bindHost\":\"" + jsonEscape(actualBindHost) + "\"," +
                "\"bindPort\":" + port + "," +
                "\"allowedCidrs\":" + cidrsJson(allowedCidrs) + "," +
                "\"requireAuth\":" + requireAuth + "," +
                "\"configuredBindHost\":\"" + jsonEscape(bindHost) + "\"," +
                "\"desiredBindHost\":\"" + jsonEscape(effectiveBindHost()) + "\"," +
                "\"effectiveBindHost\":\"" + jsonEscape(actualBindHost) + "\"," +
                "\"effectiveRemoteEnabled\":" + (remoteServing && effectiveRemoteEnabled()) + "," +
                "\"remoteServing\":" + remoteServing + "," +
                "\"localOnlyManagement\":true" +
                "}";
    }

    public String displayStatus() {
        return "Remote access: " + (enabled ? "enabled" : "disabled") +
                "\nConfigured: " + bindHost + ":" + port +
                "\nEffective listener: " + effectiveBindHost() + ":" + port +
                "\nAllowed CIDRs: " + joinCidrs(allowedCidrs) +
                "\nBearer auth required: " + (requireAuth ? "yes" : "no");
    }

    private static RemoteAccessConfig create(boolean enabled, String bindHost, int port, List<String> allowedCidrs, boolean requireAuth) {
        try {
            return validated(new RemoteAccessConfig(enabled, normalizeHost(bindHost), port, normalizeCidrs(allowedCidrs), requireAuth));
        } catch (ValidationException ignored) {
            return defaults();
        }
    }

    private static RemoteAccessConfig validated(RemoteAccessConfig config) throws ValidationException {
        validatePort(config.port);
        validateBindHost(config.bindHost);
        if (config.allowedCidrs.isEmpty()) {
            throw new ValidationException("allowedCidrs must not be empty");
        }
        for (String cidr : config.allowedCidrs) {
            validateCidr(cidr);
        }
        if (!config.requireAuth) {
            throw new ValidationException("requireAuth must remain true");
        }
        return config;
    }

    private static String normalizeHost(String host) {
        return host == null ? null : host.trim();
    }

    private static List<String> normalizeCidrs(List<String> cidrs) {
        List<String> out = new ArrayList<>();
        for (String cidr : cidrs) {
            out.add(cidr == null ? null : cidr.trim());
        }
        return out;
    }

    private static void validatePort(int port) throws ValidationException {
        if (port < 1 || port > 65535) {
            throw new ValidationException("bindPort must be between 1 and 65535");
        }
    }

    private static void validateBindHost(String host) throws ValidationException {
        if (host == null || host.trim().isEmpty()) {
            throw new ValidationException("bindHost must not be empty");
        }
        parseIpLiteral(host.trim(), "bindHost");
    }

    private static boolean isLoopbackHost(String host) {
        try {
            return parseIpLiteral(host.trim(), "bindHost").isLoopbackAddress();
        } catch (ValidationException ignored) {
            return false;
        }
    }

    private static void validateCidr(String cidr) throws ValidationException {
        if (cidr == null || cidr.trim().isEmpty()) {
            throw new ValidationException("CIDR must not be empty");
        }
        String value = cidr.trim();
        int slash = value.indexOf('/');
        if (slash <= 0 || slash != value.lastIndexOf('/') || slash == value.length() - 1) {
            throw new ValidationException("CIDR must include address/prefix: " + value);
        }
        InetAddress address = parseIpLiteral(value.substring(0, slash), "CIDR address");
        int prefix;
        try {
            prefix = Integer.parseInt(value.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new ValidationException("CIDR prefix must be numeric: " + value);
        }
        int max = address instanceof Inet4Address ? 32 : 128;
        if (prefix < 0 || prefix > max) {
            throw new ValidationException("CIDR prefix out of range: " + value);
        }
    }

    private static InetAddress parseIpLiteral(String value, String field) throws ValidationException {
        if (value == null || value.isEmpty() || value.contains("/") || value.contains(" ") || value.contains("%")) {
            throw new ValidationException(field + " must be an IP address");
        }
        if (!looksLikeIpLiteral(value)) {
            throw new ValidationException(field + " must be an IP address");
        }
        if (value.indexOf(':') < 0) {
            validateIpv4Text(value, field);
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            if (!(address instanceof Inet4Address) && !(address instanceof Inet6Address)) {
                throw new ValidationException(field + " must be IPv4 or IPv6");
            }
            return address;
        } catch (Exception e) {
            throw new ValidationException(field + " is invalid");
        }
    }

    private static boolean looksLikeIpLiteral(String value) {
        return value.indexOf(':') >= 0 || value.matches("[0-9.]+");
    }

    private static void validateIpv4Text(String value, String field) throws ValidationException {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw new ValidationException(field + " must be dotted IPv4 or IPv6");
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                throw new ValidationException(field + " has invalid IPv4 octet");
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    throw new ValidationException(field + " has invalid IPv4 octet");
                }
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new ValidationException(field + " has invalid IPv4 octet");
            }
            if (octet < 0 || octet > 255) {
                throw new ValidationException(field + " has invalid IPv4 octet");
            }
        }
    }

    private static String encodeCidrs(List<String> cidrs) {
        return joinCidrs(cidrs);
    }

    private static List<String> decodeCidrs(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_ALLOWED_CIDRS;
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out.isEmpty() ? DEFAULT_ALLOWED_CIDRS : out;
    }

    private static String cidrsJson(List<String> cidrs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cidrs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(jsonEscape(cidrs.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String joinCidrs(List<String> cidrs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cidrs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(cidrs.get(i));
        }
        return sb.toString();
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

    public static final class Update {
        public Boolean enabled;
        public String bindHost;
        public Integer port;
        public List<String> allowedCidrs;
        public Boolean requireAuth;
    }

    public static final class ValidationException extends Exception {
        ValidationException(String message) {
            super(message);
        }
    }
}
