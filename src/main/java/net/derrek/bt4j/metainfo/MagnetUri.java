package net.derrek.bt4j.metainfo;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Magnet link (the subset of the magnet URI defined by BEP 9).
 *
 * @param infoHash    xt=urn:btih:{hex40 | base32} (required)
 * @param displayName dn= (optional, only for display before metadata is obtained)
 * @param trackers    tr= (may be multiple)
 * @param peers       x.pe= (may be multiple, direct peer addresses)
 */
public record MagnetUri(InfoHash infoHash,
                        Optional<String> displayName,
                        List<URI> trackers,
                        List<InetSocketAddress> peers) {

    private static final String SCHEME_PREFIX = "magnet:?";
    private static final String BTIH_PREFIX = "urn:btih:";

    /**
     * Parse a magnet link string. btih supports both 40-character hex and 32-character Base32 forms.
     * Unparseable individual tr=/x.pe= entries are leniently ignored; BitTorrent v2 btmh is not supported.
     *
     * @throws IllegalArgumentException on non-magnet scheme, missing xt, or malformed info-hash
     */
    public static MagnetUri parse(String magnetLink) {
        if (!magnetLink.startsWith(SCHEME_PREFIX)) {
            throw new IllegalArgumentException("not a magnet link: " + magnetLink);
        }
        InfoHash infoHash = null;
        String displayName = null;
        List<URI> trackers = new ArrayList<>();
        List<InetSocketAddress> peers = new ArrayList<>();

        for (String param : magnetLink.substring(SCHEME_PREFIX.length()).split("&")) {
            int eq = param.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = param.substring(0, eq);
            String value = URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
            switch (key) {
                case "xt" -> {
                    if (value.startsWith(BTIH_PREFIX)) {
                        infoHash = parseBtih(value.substring(BTIH_PREFIX.length()));
                    }
                    // urn:btmh: (v2) is not supported yet, skip it; if the whole link has only btmh it will throw at the end for missing btih
                }
                case "dn" -> displayName = value;
                case "tr" -> {
                    try {
                        trackers.add(new URI(value));
                    } catch (URISyntaxException ignored) {
                        // an individual broken tracker URL should not fail the whole link
                    }
                }
                case "x.pe" -> parsePeer(value).ifPresent(peers::add);
                default -> {
                    // other parameters (xl, ws, so...) are not used yet
                }
            }
        }
        if (infoHash == null) {
            throw new IllegalArgumentException("magnet link is missing the xt=urn:btih: parameter: " + magnetLink);
        }
        return new MagnetUri(infoHash, Optional.ofNullable(displayName), List.copyOf(trackers), List.copyOf(peers));
    }

    private static InfoHash parseBtih(String hash) {
        return switch (hash.length()) {
            case 40 -> InfoHash.fromHex(hash);
            case 32 -> InfoHash.fromBase32(hash);
            default -> throw new IllegalArgumentException(
                    "btih must be 40-character hex or 32-character Base32, got " + hash.length() + " characters: " + hash);
        };
    }

    /** host:port (IPv6 is [addr]:port). Returns empty on malformed input (lenient). */
    private static Optional<InetSocketAddress> parsePeer(String value) {
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return Optional.empty();
        }
        String host = value.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        try {
            int port = Integer.parseInt(value.substring(colon + 1));
            if (port < 1 || port > 65535) {
                return Optional.empty();
            }
            return Optional.of(InetSocketAddress.createUnresolved(host, port));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
