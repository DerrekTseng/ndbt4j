package net.derrek.bt4j.tracker;

import java.net.URI;

/**
 * HTTP(S) tracker（BEP 3），要求 compact 回應（BEP 23）。
 * 以 java.net.http.HttpClient（JDK 內建）實作。
 */
public final class HttpTracker implements Tracker {

    public HttpTracker(URI uri) {
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
