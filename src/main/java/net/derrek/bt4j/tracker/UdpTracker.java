package net.derrek.bt4j.tracker;

import java.net.URI;

/**
 * UDP tracker（BEP 15）：connect → announce 二段式，含 connection id 快取與重送退避。
 * 以 DatagramSocket 實作。
 */
public final class UdpTracker implements Tracker {

    public UdpTracker(URI uri) {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public URI uri() {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public AnnounceResponse announce(AnnounceRequest request) throws TrackerException {
        throw new UnsupportedOperationException("尚未實作");
    }
}
