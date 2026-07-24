package net.derrek.bt4j.peer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import net.derrek.bt4j.utp.UtpEndpoint;
import net.derrek.bt4j.utp.UtpSocket;

/** {@link PeerTransport} over a uTP connection (BEP 29). */
public final class UtpTransport implements PeerTransport {

    private final UtpSocket socket;

    public UtpTransport(UtpSocket socket) {
        this.socket = socket;
    }

    /** Dials a uTP connection to the peer through the given endpoint. */
    public static UtpTransport connect(UtpEndpoint endpoint, PeerAddress address) throws IOException {
        InetSocketAddress resolved = address.socketAddress().isUnresolved()
                ? new InetSocketAddress(address.socketAddress().getHostString(), address.socketAddress().getPort())
                : address.socketAddress();
        return new UtpTransport(endpoint.connect(resolved));
    }

    @Override
    public InputStream inputStream() {
        return socket.getInputStream();
    }

    @Override
    public OutputStream outputStream() {
        return socket.getOutputStream();
    }

    @Override
    public void close() {
        socket.close();
    }
}
