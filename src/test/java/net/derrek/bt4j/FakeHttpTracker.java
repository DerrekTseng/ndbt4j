package net.derrek.bt4j;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** 測試用假 HTTP tracker：對任何 announce 回覆 compact 格式的固定 peer（127.0.0.1:peerPort）。 */
public final class FakeHttpTracker implements AutoCloseable {

    private final HttpServer server;

    public FakeHttpTracker(int peerPort) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/announce", exchange -> {
            byte[] peer = {127, 0, 0, 1, (byte) (peerPort >> 8), (byte) (peerPort & 0xFF)};
            byte[] prefix = "d8:intervali120e5:peers6:".getBytes(StandardCharsets.ISO_8859_1);
            byte[] body = new byte[prefix.length + peer.length + 1];
            System.arraycopy(prefix, 0, body, 0, prefix.length);
            System.arraycopy(peer, 0, body, prefix.length, peer.length);
            body[body.length - 1] = 'e';
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    public String announceUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/announce";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
