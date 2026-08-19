package link.liaru.henyo;

final class BearerAuthPolicy {
    static final int AUTH_MISSING = 1;
    static final int AUTH_MALFORMED = 2;
    static final int AUTH_PRESENT = 3;

    private BearerAuthPolicy() {
    }

    static boolean requiresRemoteBearer(String method, String target) {
        String path = target;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        if ("POST".equals(method) && "/v1/auth/tokens/local".equals(path)) return false;
        if (path.equals("/v1/auth/tokens") || path.startsWith("/v1/auth/tokens/")) return true;
        if (path.equals("/v1/ws/control")) return true;
        return path.startsWith("/v1/app/") || path.startsWith("/v1/ui/")
                || path.startsWith("/v1/screen/") || path.startsWith("/v1/global/")
                || path.startsWith("/v1/termux/");
    }

    static ParsedAuthorization parse(String authorization) {
        if (authorization == null || authorization.trim().isEmpty()) {
            return new ParsedAuthorization(AUTH_MISSING, "");
        }
        String trimmed = authorization.trim();
        String prefix = "Bearer ";
        if (trimmed.length() <= prefix.length() || !trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return new ParsedAuthorization(AUTH_MALFORMED, "");
        }
        String token = trimmed.substring(prefix.length()).trim();
        if (token.isEmpty()) return new ParsedAuthorization(AUTH_MALFORMED, "");
        for (int i = 0; i < token.length(); i++) {
            if (Character.isWhitespace(token.charAt(i))) {
                return new ParsedAuthorization(AUTH_MALFORMED, "");
            }
        }
        return new ParsedAuthorization(AUTH_PRESENT, token);
    }

    static final class ParsedAuthorization {
        final int status;
        final String token;

        ParsedAuthorization(int status, String token) {
            this.status = status;
            this.token = token;
        }
    }
}
