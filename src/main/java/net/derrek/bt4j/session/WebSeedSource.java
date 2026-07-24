package net.derrek.bt4j.session;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.metainfo.Metainfo;

/**
 * A single BEP 19 web seed: an HTTP(S) mirror that serves the torrent's file bytes over range requests. Fetches a
 * whole piece at a time, mapping the piece's byte span to one or more file URLs. Pure of any session state — the
 * caller drives it piece by piece — so it is straightforward to unit-test against a local HTTP server.
 *
 * <p>URL construction follows BEP 19: for a multi-file torrent the url-list entry is a directory to which the
 * file's path (torrent name included) is appended; for a single-file torrent a url-list entry ending in {@code /}
 * has the name appended, otherwise it is the file URL directly.
 */
final class WebSeedSource {

    private static final System.Logger LOG = System.getLogger(WebSeedSource.class.getName());

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Metainfo metainfo;
    private final URI baseUrl;
    private final HttpClient http;

    WebSeedSource(Metainfo metainfo, URI baseUrl, HttpClient http) {
        this.metainfo = metainfo;
        this.baseUrl = baseUrl;
        this.http = http;
    }

    URI baseUrl() {
        return baseUrl;
    }

    /**
     * Downloads the whole of {@code pieceIndex} by issuing an HTTP range request per file the piece overlaps.
     *
     * @return the piece bytes (length == {@code metainfo.pieceLengthAt(pieceIndex)})
     * @throws IOException the mirror was unreachable, returned a non-range response, or short data
     */
    byte[] fetchPiece(int pieceIndex) throws IOException, InterruptedException {
        int pieceLength = metainfo.pieceLengthAt(pieceIndex);
        byte[] piece = new byte[pieceLength];
        long pieceStart = (long) pieceIndex * metainfo.pieceLength();
        long pieceEnd = pieceStart + pieceLength;

        for (FileEntry file : metainfo.files()) {
            if (file.length() == 0) {
                continue;
            }
            long fileStart = file.offset();
            long fileEnd = fileStart + file.length();
            long overlapStart = Math.max(pieceStart, fileStart);
            long overlapEnd = Math.min(pieceEnd, fileEnd);
            if (overlapStart >= overlapEnd) {
                continue;
            }
            byte[] segment = fetchRange(fileUrl(file), overlapStart - fileStart, overlapEnd - fileStart - 1);
            int into = (int) (overlapStart - pieceStart);
            if (segment.length != (int) (overlapEnd - overlapStart)) {
                throw new IOException("web seed returned " + segment.length + " bytes for a "
                        + (overlapEnd - overlapStart) + "-byte range of " + file.displayPath());
            }
            System.arraycopy(segment, 0, piece, into, segment.length);
        }
        return piece;
    }

    /** Fetches the inclusive byte range [{@code start}, {@code end}] of {@code url}; requires a 206 response. */
    private byte[] fetchRange(URI url, long start, long end) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(REQUEST_TIMEOUT)
                .header("Range", "bytes=" + start + "-" + end)
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 206) {
            // 200 would mean the whole file (the server ignored Range) — refuse rather than pull gigabytes per piece.
            throw new IOException("web seed " + url + " did not honour range request (HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    /** Resolves the URL for a file per BEP 19. Package-private and static for unit testing. */
    static URI fileUrl(URI base, Metainfo metainfo, FileEntry file) {
        String s = base.toString();
        if (metainfo.files().size() > 1) {
            // multi-file: the base is a directory; append the file's full path (path already begins with the name)
            if (!s.endsWith("/")) {
                s += "/";
            }
            List<String> encoded = new ArrayList<>();
            for (String component : file.path()) {
                encoded.add(encodePathSegment(component));
            }
            s += String.join("/", encoded);
        } else if (s.endsWith("/")) {
            // single-file: a directory URL gets the name appended
            s += encodePathSegment(file.path().getLast());
        }
        // single-file with a non-slash URL: the base already points at the file
        return URI.create(s);
    }

    private URI fileUrl(FileEntry file) {
        return fileUrl(baseUrl, metainfo, file);
    }

    /** Percent-encodes a path segment (URLEncoder targets query strings, so restore the path conventions). */
    static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/")
                .replace("%7E", "~");
    }

    @Override
    public String toString() {
        return "WebSeedSource[" + baseUrl + "]";
    }
}
