package net.derrek.bt4j.peer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.piece.Bitfield;

/**
 * The lifecycle of a single peer TCP connection: handshake, message encode/decode, and the four
 * state flags (am_choking / am_interested / peer_choking / peer_interested).
 *
 * Threading model: two virtual threads per connection -- a read loop (processes messages in order
 * and invokes the listener) and a write queue (sends a keep-alive automatically after
 * {@value #KEEP_ALIVE_IDLE_SECONDS} idle seconds).
 * {@link Listener} callbacks always run on the read-loop thread.
 */
public final class PeerConnection implements AutoCloseable {

    private static final Logger LOG = System.getLogger(PeerConnection.class.getName());

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 150_000;
    private static final int KEEP_ALIVE_IDLE_SECONDS = 110;

    /** Connection event callbacks. Invoked on the read-loop thread; implementations must not block for long. */
    public interface Listener {

        void onHandshakeCompleted(PeerConnection connection, Handshake theirs);

        void onMessage(PeerConnection connection, PeerMessage message);

        /** Connection closed (normally or with an error). A null error means a normal close. */
        void onClosed(PeerConnection connection, IOException error);
    }

    private final PeerAddress address;
    private final InfoHash infoHash;
    private final PeerId localId;
    private final int pieceCount;
    private final boolean advertiseDht;
    private final Listener listener;
    private final BlockingQueue<PeerMessage> sendQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Incoming connection: the pre-accepted socket and the peer's already-read handshake (both null for outgoing). */
    private final Socket incomingSocket;
    private final Handshake incomingHandshake;

    private volatile Socket socket;
    private volatile Thread writeThread;
    private volatile boolean amChoking = true;
    private volatile boolean amInterested = false;
    private volatile boolean peerChoking = true;
    private volatile boolean peerInterested = false;
    private volatile Handshake theirHandshake;
    private Bitfield peerBitfield; // written only on the read-loop thread

    private PeerConnection(PeerAddress address, InfoHash infoHash, PeerId localId, int pieceCount,
                           boolean advertiseDht, Socket incomingSocket, Handshake incomingHandshake,
                           Listener listener) {
        this.address = address;
        this.infoHash = infoHash;
        this.localId = localId;
        this.pieceCount = pieceCount;
        this.advertiseDht = advertiseDht;
        this.incomingSocket = incomingSocket;
        this.incomingHandshake = incomingHandshake;
        this.listener = listener;
        // pieceCount <= 0: magnet case, metadata unknown; start with a minimal bitfield (replaced when the bitfield message arrives)
        this.peerBitfield = new Bitfield(Math.max(1, pieceCount));
    }

    /** Active outgoing connection. IO begins only after calling {@link #start()} post-construction. */
    public static PeerConnection outgoing(PeerAddress address, InfoHash infoHash, PeerId localId,
                                          int pieceCount, boolean advertiseDht, Listener listener) {
        return new PeerConnection(address, infoHash, localId, pieceCount, advertiseDht, null, null, listener);
    }

    /**
     * Passive incoming connection: the caller (BtClient) has already accepted the socket and read the
     * peer's handshake to determine which torrent it belongs to.
     * This connection is responsible for sending back our handshake and entering the message loop.
     */
    public static PeerConnection incoming(Socket socket, Handshake theirHandshake, InfoHash infoHash,
                                          PeerId localId, int pieceCount, boolean advertiseDht, Listener listener) {
        InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
        return new PeerConnection(new PeerAddress(remote), infoHash, localId, pieceCount, advertiseDht,
                socket, theirHandshake, listener);
    }

    /** Starts the read/write virtual threads and performs the handshake. Returns immediately. */
    public void start() {
        Thread.ofVirtual().name("bt4j-peer-" + address).start(this::runRead);
    }

    private void runRead() {
        try {
            DataInputStream in;
            DataOutputStream out;
            Handshake theirs;
            if (incomingSocket != null) {
                Socket s = incomingSocket;
                this.socket = s;
                s.setSoTimeout(READ_TIMEOUT_MILLIS);
                s.setTcpNoDelay(true);
                in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                // the peer's handshake was already read and its info-hash verified by the caller; send back our handshake
                out.write(Handshake.outgoing(infoHash, localId, advertiseDht, true, true).encode());
                out.flush();
                theirs = incomingHandshake;
                LOG.log(Level.DEBUG, () -> "incoming connection established: " + address);
            } else {
                Socket s = new Socket();
                this.socket = s;
                if (closed.get()) {
                    s.close();
                    return;
                }
                s.connect(resolve(address.socketAddress()), CONNECT_TIMEOUT_MILLIS);
                s.setSoTimeout(READ_TIMEOUT_MILLIS);
                s.setTcpNoDelay(true);
                in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

                out.write(Handshake.outgoing(infoHash, localId, advertiseDht, true, true).encode());
                out.flush();
                byte[] response = in.readNBytes(Handshake.LENGTH);
                if (response.length != Handshake.LENGTH) {
                    throw new IOException("disconnected before handshake completed");
                }
                try {
                    theirs = Handshake.decode(response);
                } catch (IllegalArgumentException e) {
                    throw new IOException("malformed handshake", e);
                }
                if (!theirs.infoHash().equals(infoHash)) {
                    throw new IOException("peer info-hash mismatch");
                }
                LOG.log(Level.DEBUG, () -> "outgoing connection handshaked: " + address);
            }
            this.theirHandshake = theirs;
            this.writeThread = Thread.ofVirtual().name("bt4j-peer-write-" + address).start(() -> runWrite(out));
            listener.onHandshakeCompleted(this, theirs);

            while (!closed.get()) {
                PeerMessage message = PeerMessage.read(in, pieceCount);
                LOG.log(Level.TRACE, () -> "recv " + address + " -> " + message.getClass().getSimpleName());
                handleInternal(message);
                listener.onMessage(this, message);
            }
        } catch (IOException e) {
            if (!closed.get()) {
                LOG.log(Level.DEBUG, () -> "connection " + address + " dropped: " + e.getMessage());
            }
            closeInternal(closed.get() ? null : e);
        }
    }

    /** Read-side internal state update (applied before the listener callback so it sees the latest state). */
    private void handleInternal(PeerMessage message) {
        switch (message) {
            case PeerMessage.Choke() -> peerChoking = true;
            case PeerMessage.Unchoke() -> peerChoking = false;
            case PeerMessage.Interested() -> peerInterested = true;
            case PeerMessage.NotInterested() -> peerInterested = false;
            case PeerMessage.Have(int piece) -> {
                if (piece >= 0 && piece < peerBitfield.pieceCount()) {
                    peerBitfield.set(piece);
                }
                // out of range: the bitfield has not yet arrived during the metadata-unknown phase, ignore
            }
            case PeerMessage.BitfieldMessage(Bitfield bf) -> peerBitfield = bf.copy();
            case PeerMessage.HaveAll() -> peerBitfield.setAll();
            case PeerMessage.HaveNone() -> peerBitfield = new Bitfield(Math.max(1, pieceCount));
            default -> {
            }
        }
    }

    private void runWrite(DataOutputStream out) {
        try {
            while (!closed.get()) {
                PeerMessage message = sendQueue.poll(KEEP_ALIVE_IDLE_SECONDS, TimeUnit.SECONDS);
                if (closed.get()) {
                    return;
                }
                if (message == null) {
                    message = new PeerMessage.KeepAlive();
                }
                switch (message) {
                    case PeerMessage.Choke() -> amChoking = true;
                    case PeerMessage.Unchoke() -> amChoking = false;
                    case PeerMessage.Interested() -> amInterested = true;
                    case PeerMessage.NotInterested() -> amInterested = false;
                    default -> {
                    }
                }
                PeerMessage.write(out, message);
            }
        } catch (IOException e) {
            closeInternal(closed.get() ? null : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static InetSocketAddress resolve(InetSocketAddress address) {
        return address.isUnresolved()
                ? new InetSocketAddress(address.getHostString(), address.getPort())
                : address;
    }

    /** Sends a message asynchronously (into the write queue). Silently ignored if the connection is already closed. */
    public void send(PeerMessage message) {
        if (!closed.get()) {
            sendQueue.offer(message);
        }
    }

    public PeerAddress address() {
        return address;
    }

    /** The peer's handshake (null until completed). */
    public Handshake theirHandshake() {
        return theirHandshake;
    }

    /**
     * The piece-availability the peer has advertised (bitfield + accumulated have messages).
     * Consistency is only guaranteed when read within a listener callback (on the read-loop thread).
     */
    public Bitfield peerBitfield() {
        return peerBitfield;
    }

    public boolean amChoking() {
        return amChoking;
    }

    public boolean amInterested() {
        return amInterested;
    }

    public boolean peerChoking() {
        return peerChoking;
    }

    public boolean peerInterested() {
        return peerInterested;
    }

    @Override
    public void close() {
        closeInternal(null);
    }

    private void closeInternal(IOException error) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        Thread w = writeThread;
        if (w != null) {
            w.interrupt();
        }
        listener.onClosed(this, error);
    }

    @Override
    public String toString() {
        return "PeerConnection[" + address + "]";
    }
}
