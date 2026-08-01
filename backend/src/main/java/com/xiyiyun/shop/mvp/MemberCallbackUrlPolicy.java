package com.xiyiyun.shop.mvp;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.util.StringUtils;

final class MemberCallbackUrlPolicy {
    private MemberCallbackUrlPolicy() {
    }

    static URI validateSyntax(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("callbackUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("callbackUrl is invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().trim();
        if (!("http".equals(scheme) || "https".equals(scheme))
            || host.isEmpty() || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("callbackUrl must be a valid http or https URL");
        }
        return uri;
    }

    static URI validateConfiguration(String value) {
        URI uri = validateSyntax(value);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || host.endsWith(".localhost")) {
            throw new IllegalArgumentException("callbackUrl cannot target a private address");
        }
        if (isIpLiteral(host)) {
            try {
                if (isPrivate(InetAddress.getByName(host))) {
                    throw new IllegalArgumentException("callbackUrl cannot target a private address");
                }
            } catch (UnknownHostException ex) {
                throw new IllegalArgumentException("callbackUrl is invalid");
            }
        }
        return uri;
    }

    static URI requirePublicUri(String value) {
        return resolvePublicTarget(value).uri();
    }

    static ResolvedTarget resolvePublicTarget(String value) {
        return resolvePublicTarget(value, InetAddress::getAllByName);
    }

    static ResolvedTarget resolvePublicTarget(String value, HostResolver resolver) {
        URI uri = validateSyntax(value);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || host.endsWith(".localhost")) {
            throw new IllegalArgumentException("callbackUrl cannot target a private address");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException ex) {
            throw new IllegalStateException("callbackUrl host cannot be resolved", ex);
        }
        if (addresses.length == 0) {
            throw new IllegalStateException("callbackUrl host cannot be resolved");
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                throw new IllegalArgumentException("callbackUrl cannot target a private address");
            }
        }
        return new ResolvedTarget(uri, host, addresses);
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (!(address instanceof Inet4Address)) {
            byte[] bytes = address.getAddress();
            return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        return first == 0
            || (first == 100 && second >= 64 && second <= 127)
            || (first == 198 && (second == 18 || second == 19))
            || first >= 224;
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.chars().allMatch(value -> Character.isDigit(value) || value == '.');
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    record ResolvedTarget(URI uri, String host, InetAddress[] addresses) {
        ResolvedTarget {
            addresses = Arrays.copyOf(addresses, addresses.length);
        }

        @Override
        public InetAddress[] addresses() {
            return Arrays.copyOf(addresses, addresses.length);
        }
    }
}
