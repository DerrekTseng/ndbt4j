package net.derrek.bt4j.peer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A bidirectional byte-stream connection to a peer, independent of the underlying protocol. Implemented over TCP
 * ({@link TcpTransport}) and over uTP ({@code UtpTransport}), so {@link PeerConnection} runs unchanged on either.
 */
public interface PeerTransport extends AutoCloseable {

    InputStream inputStream() throws IOException;

    OutputStream outputStream() throws IOException;

    @Override
    void close();
}
