package link.liaru.henyo;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Android-free validation for the opaque app.openUri operation inputs. */
final class OpenUriContract {
    static final int MAX_URI_CODE_POINTS = 2048;

    private static final Set<String> DISALLOWED_SCHEMES = new HashSet<>(Arrays.asList(
            "file", "content", "javascript", "data", "intent"));
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    private OpenUriContract() {
    }

    static String validateUri(String value) {
        if (value == null || value.isEmpty()) return "missing_uri";
        if (value.codePointCount(0, value.length()) > MAX_URI_CODE_POINTS) {
            return "uri_too_long";
        }
        try {
            URI parsed = new URI(value);
            String scheme = parsed.getScheme();
            if (!parsed.isAbsolute() || scheme == null || scheme.isEmpty()) {
                return "invalid_uri";
            }
            if (DISALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
                return "disallowed_uri_scheme";
            }
            return "";
        } catch (URISyntaxException | IllegalArgumentException e) {
            return "invalid_uri";
        }
    }

    static String validatePackage(String value, boolean present) {
        if (!present) return "";
        if (value == null || !PACKAGE_NAME.matcher(value).matches()) {
            return "invalid_package";
        }
        return "";
    }
}
