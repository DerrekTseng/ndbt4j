package net.derrek.bt4j.tracker;

import java.net.URI;

/**
 * A single tracker. Implementations: HttpTracker (http/https), UdpTracker (udp).
 * Implementations must be thread-safe (announce may be called from different virtual threads).
 */
public interface Tracker {

    URI uri();

    /**
     * Synchronous announce (blocking call, intended for use with virtual threads).
     *
     * @throws TrackerException on connection failure, timeout, or when the tracker returns a failure reason
     */
    AnnounceResponse announce(AnnounceRequest request) throws TrackerException;

    /** Creates the matching implementation based on the URI scheme. */
    static Tracker of(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        return switch (scheme) {
            case "http", "https" -> new HttpTracker(uri);
            case "udp" -> new UdpTracker(uri); // M5 implementation
            default -> throw new IllegalArgumentException("unsupported tracker scheme: " + uri);
        };
    }
}
