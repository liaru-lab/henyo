package link.liaru.henyo;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

final class SourceAccessFilter {
    static final int SOURCE_LOCALHOST = 1;
    static final int SOURCE_ALLOWED_REMOTE = 2;
    static final int SOURCE_DENIED = 3;

    private final boolean remoteEnabled;
    private final List<CidrBlock> allowedCidrs;

    SourceAccessFilter(boolean remoteEnabled, List<String> allowedCidrs) {
        this.remoteEnabled = remoteEnabled;
        this.allowedCidrs = new ArrayList<>();
        for (String cidr : allowedCidrs) {
            this.allowedCidrs.add(CidrBlock.parse(cidr));
        }
    }

    int classify(InetAddress address) {
        InetAddress normalized = normalize(address);
        if (normalized.isLoopbackAddress()) return SOURCE_LOCALHOST;
        if (!remoteEnabled) return SOURCE_DENIED;
        for (CidrBlock block : allowedCidrs) {
            if (block.contains(normalized)) return SOURCE_ALLOWED_REMOTE;
        }
        return SOURCE_DENIED;
    }

    static boolean contains(String cidr, String address) throws UnknownHostException {
        return CidrBlock.parse(cidr).contains(parseIpLiteral(address));
    }

    private static InetAddress normalize(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && isIpv4Mapped(bytes)) {
            byte[] v4 = new byte[4];
            System.arraycopy(bytes, 12, v4, 0, 4);
            try {
                return InetAddress.getByAddress(v4);
            } catch (UnknownHostException ignored) {
            }
        }
        return address;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return false;
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static final class CidrBlock {
        final byte[] networkBytes;
        final int prefixBits;

        private CidrBlock(byte[] networkBytes, int prefixBits) {
            this.networkBytes = networkBytes;
            this.prefixBits = prefixBits;
        }

        static CidrBlock parse(String cidr) {
            int slash = cidr.indexOf('/');
            if (slash <= 0 || slash == cidr.length() - 1) {
                throw new IllegalArgumentException("CIDR must include address/prefix: " + cidr);
            }
            try {
                InetAddress address = parseIpLiteral(cidr.substring(0, slash));
                int prefixBits = Integer.parseInt(cidr.substring(slash + 1));
                byte[] bytes = normalize(address).getAddress();
                int maxBits = bytes.length * 8;
                if (prefixBits < 0 || prefixBits > maxBits) {
                    throw new IllegalArgumentException("CIDR prefix out of range: " + cidr);
                }
                return new CidrBlock(mask(bytes, prefixBits), prefixBits);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid CIDR: " + cidr, e);
            }
        }

        boolean contains(InetAddress address) {
            byte[] candidate = normalize(address).getAddress();
            return candidate.length == networkBytes.length
                    && toInteger(mask(candidate, prefixBits)).equals(toInteger(networkBytes));
        }

        private static byte[] mask(byte[] bytes, int prefixBits) {
            BigInteger value = toInteger(bytes);
            int totalBits = bytes.length * 8;
            BigInteger mask = prefixBits == 0
                    ? BigInteger.ZERO
                    : BigInteger.ONE.shiftLeft(prefixBits).subtract(BigInteger.ONE).shiftLeft(totalBits - prefixBits);
            return toBytes(value.and(mask), bytes.length);
        }

        private static BigInteger toInteger(byte[] bytes) {
            return new BigInteger(1, bytes);
        }

        private static byte[] toBytes(BigInteger value, int length) {
            byte[] raw = value.toByteArray();
            byte[] out = new byte[length];
            int copy = Math.min(raw.length, length);
            System.arraycopy(raw, raw.length - copy, out, length - copy, copy);
            return out;
        }
    }

    private static InetAddress parseIpLiteral(String value) throws UnknownHostException {
        if (value == null || value.isEmpty() || value.contains("/") || value.contains(" ") || value.contains("%")) {
            throw new UnknownHostException("not an IP literal");
        }
        if (value.indexOf(':') < 0) {
            validateDottedIpv4(value);
        }
        return InetAddress.getByName(value);
    }

    private static void validateDottedIpv4(String value) throws UnknownHostException {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new UnknownHostException("not dotted IPv4");
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) throw new UnknownHostException("invalid IPv4 octet");
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) throw new UnknownHostException("invalid IPv4 octet");
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new UnknownHostException("invalid IPv4 octet");
            }
            if (octet < 0 || octet > 255) throw new UnknownHostException("invalid IPv4 octet");
        }
    }
}
