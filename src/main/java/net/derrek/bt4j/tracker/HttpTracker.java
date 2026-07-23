package net.derrek.bt4j.tracker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;
import net.derrek.bt4j.bencode.BencodeException;
import net.derrek.bt4j.peer.PeerAddress;

/**
 * HTTP(S) tracker (BEP 3), requesting a compact response (BEP 23).
 * Implemented with java.net.http.HttpClient (built into the JDK); blocking calls paired with virtual threads.
 */
public final class HttpTracker implements Tracker {

    private static final System.Logger LOG = System.getLogger(HttpTracker.class.getName());

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(30);

    private final URI uri;
    private final HttpClient client;

    public HttpTracker(URI uri) {
        this.uri = uri;
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public AnnounceResponse announce(AnnounceRequest request) throws TrackerException {
        URI target = URI.create(uri.toString() + (uri.getRawQuery() == null ? "?" : "&") + buildQuery(request));
        HttpRequest httpRequest = HttpRequest.newBuilder(target)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "bt4j/1.0")
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new TrackerException("failed to connect to tracker: " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrackerException("announce interrupted: " + uri, e);
        }
        if (response.statusCode() != 200) {
            throw new TrackerException("tracker responded HTTP " + response.statusCode() + ": " + uri);
        }
        AnnounceResponse result = parseResponse(response.body());
        LOG.log(System.Logger.Level.DEBUG, () -> "HTTP tracker " + uri + " returned "
                + result.peers().size() + " peers (interval=" + result.interval().toSeconds() + "s)");
        return result;
    }

    /** Builds the announce query string. info_hash / peer_id are the raw 20 bytes, percent-encoded byte by byte. */
    static String buildQuery(AnnounceRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("info_hash=").append(percentEncode(request.infoHash().bytes()));
        sb.append("&peer_id=").append(percentEncode(request.peerId().bytes()));
        sb.append("&port=").append(request.port());
        sb.append("&uploaded=").append(request.uploaded());
        sb.append("&downloaded=").append(request.downloaded());
        sb.append("&left=").append(request.left());
        sb.append("&compact=1");
        if (request.event() != AnnounceEvent.NONE) {
            sb.append("&event=").append(request.event().name().toLowerCase(Locale.ROOT));
        }
        sb.append("&numwant=").append(request.numWant());
        return sb.toString();
    }

    /** RFC 3986: unreserved characters as-is, everything else as %XX. Works for arbitrary binary data. */
    static String percentEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            if (b >= 'A' && b <= 'Z' || b >= 'a' && b <= 'z' || b >= '0' && b <= '9'
                    || b == '-' || b == '_' || b == '.' || b == '~') {
                sb.append((char) b);
            } else {
                sb.append('%').append(String.format("%02X", b));
            }
        }
        return sb.toString();
    }

    /** Parses the bencoded response. peers supports both the compact form (BEP 23) and the dict list form (BEP 3). */
    static AnnounceResponse parseResponse(byte[] body) throws TrackerException {
        BValue decoded;
        try {
            decoded = Bencode.decode(body);
        } catch (BencodeException e) {
            throw new TrackerException("tracker response is not valid bencoding", e);
        }
        if (!(decoded instanceof BValue.BDictionary dict)) {
            throw new TrackerException("the top level of the tracker response must be a dictionary");
        }
        if (dict.get("failure reason").orElse(null) instanceof BValue.BString reason) {
            throw new TrackerException("tracker rejected: " + reason.utf8());
        }

        Duration interval = dict.get("interval").orElse(null) instanceof BValue.BInteger(long seconds) && seconds > 0
                ? Duration.ofSeconds(seconds)
                : DEFAULT_INTERVAL;
        OptionalInt seeders = optionalInt(dict, "complete");
        OptionalInt leechers = optionalInt(dict, "incomplete");

        List<PeerAddress> peers = new ArrayList<>();
        switch (dict.get("peers").orElse(null)) {
            case BValue.BString(byte[] compact) -> peers.addAll(PeerAddress.fromCompact(compact));
            case BValue.BList list -> {
                // non-compact form: list of {ip, port}
                for (BValue entry : list.values()) {
                    if (entry instanceof BValue.BDictionary peerDict
                            && peerDict.get("ip").orElse(null) instanceof BValue.BString ip
                            && peerDict.get("port").orElse(null) instanceof BValue.BInteger(long port)
                            && port >= 1 && port <= 65535) {
                        peers.add(new PeerAddress(java.net.InetSocketAddress.createUnresolved(ip.utf8(), (int) port)));
                    }
                }
            }
            case null, default -> {
                // no peers field: treat as an empty list (e.g. the response to a stopped event)
            }
        }
        if (dict.get("peers6").orElse(null) instanceof BValue.BString(byte[] compact6)) {
            peers.addAll(PeerAddress.fromCompact6(compact6));
        }

        return new AnnounceResponse(interval, seeders, leechers, List.copyOf(peers));
    }

    private static OptionalInt optionalInt(BValue.BDictionary dict, String key) {
        return dict.get(key).orElse(null) instanceof BValue.BInteger(long v)
                ? OptionalInt.of((int) v)
                : OptionalInt.empty();
    }
}
