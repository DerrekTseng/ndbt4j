package net.derrek.bt4j.peer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** {@link PeerTransport} over a plain TCP {@link Socket} — the default peer transport. */
public final class TcpTransport implements PeerTransport {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 150_000;

    private final Socket socket;

    private TcpTransport(Socket socket) {
        this.socket = socket;
    }

    /** Dials a TCP connection to the peer, applying the peer-wire socket options. */
    public static TcpTransport connect(PeerAddress address) throws IOException {
        Socket s = new Socket();
        s.connect(resolve(address.socketAddress()), CONNECT_TIMEOUT_MILLIS);
        s.setSoTimeout(READ_TIMEOUT_MILLIS);
        s.setTcpNoDelay(true);
        return new TcpTransport(s);
    }

    /** Wraps an already-accepted incoming socket, applying the peer-wire socket options. */
    public static TcpTransport accepted(Socket socket) throws IOException {
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        socket.setTcpNoDelay(true);
        return new TcpTransport(socket);
    }

    private static InetSocketAddress resolve(InetSocketAddress address) {
        return address.isUnresolved()
                ? new InetSocketAddress(address.getHostString(), address.getPort())
                : address;
    }

    @Override
    public InputStream inputStream() throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream outputStream() throws IOException {
        return socket.getOutputStream();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
