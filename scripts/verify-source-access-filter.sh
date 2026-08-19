#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

tmp="${TMPDIR:-/tmp}/henyo-source-filter-$$"
mkdir -p "$tmp/link/liaru/henyo"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/link/liaru/henyo/SourceAccessFilterVerifier.java" <<'JAVA'
package link.liaru.henyo;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

public final class SourceAccessFilterVerifier {
    public static void main(String[] args) throws Exception {
        List<String> cidrs = Arrays.asList("100.64.0.0/10", "fd7a:115c:a1e0::/48");
        SourceAccessFilter enabled = new SourceAccessFilter(true, cidrs);
        SourceAccessFilter disabled = new SourceAccessFilter(false, cidrs);

        expect(enabled.classify(InetAddress.getByName("127.0.0.1")), SourceAccessFilter.SOURCE_LOCALHOST, "IPv4 localhost");
        expect(enabled.classify(InetAddress.getByName("::1")), SourceAccessFilter.SOURCE_LOCALHOST, "IPv6 localhost");
        expect(enabled.classify(InetAddress.getByName("100.64.0.1")), SourceAccessFilter.SOURCE_ALLOWED_REMOTE, "Tailnet IPv4 low edge");
        expect(enabled.classify(InetAddress.getByName("100.127.255.255")), SourceAccessFilter.SOURCE_ALLOWED_REMOTE, "Tailnet IPv4 high edge");
        expect(enabled.classify(InetAddress.getByName("100.128.0.1")), SourceAccessFilter.SOURCE_DENIED, "IPv4 outside CIDR");
        expect(enabled.classify(InetAddress.getByName("fd7a:115c:a1e0::1")), SourceAccessFilter.SOURCE_ALLOWED_REMOTE, "Tailnet IPv6");
        expect(enabled.classify(InetAddress.getByName("fd7a:115c:a1e1::1")), SourceAccessFilter.SOURCE_DENIED, "IPv6 outside CIDR");
        expect(disabled.classify(InetAddress.getByName("100.64.0.1")), SourceAccessFilter.SOURCE_DENIED, "remote disabled");
        expect(SourceAccessFilter.contains("100.64.0.0/10", "::ffff:100.64.0.9"), true, "IPv4-mapped IPv6 allowed");
        expect(SourceAccessFilter.contains("fd7a:115c:a1e0::/48", "fd7a:115c:a1e0:ffff::1"), true, "IPv6 /48 high segment");
        expectInvalidCidr("100.64/10", "IPv4 shorthand CIDR rejected");
        expectInvalidCidr("2130706433/32", "numeric IPv4 CIDR rejected");

        System.out.println("source access filter verifier passed");
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

    private static void expectInvalidCidr(String cidr, String label) {
        try {
            SourceAccessFilter.contains(cidr, "127.0.0.1");
            throw new AssertionError(label + ": expected invalid CIDR");
        } catch (IllegalArgumentException expected) {
        } catch (Exception e) {
            throw new AssertionError(label + ": expected IllegalArgumentException but got " + e);
        }
    }
}
JAVA

javac -encoding UTF-8 -source 11 -target 11 \
  -d "$tmp" \
  src/main/java/link/liaru/henyo/SourceAccessFilter.java \
  "$tmp/link/liaru/henyo/SourceAccessFilterVerifier.java"

java -cp "$tmp" link.liaru.henyo.SourceAccessFilterVerifier
