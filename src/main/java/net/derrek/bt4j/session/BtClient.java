package net.derrek.bt4j.session;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.derrek.bt4j.dht.DhtClient;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.peer.Handshake;
import net.derrek.bt4j.peer.PeerId;
import net.derrek.bt4j.storage.ResumeData;

/**
 * The package's public entry point. A process typically creates only one instance:
 * it holds the listen socket (accepting incoming peers), DHT, and peer id, and manages multiple {@link TorrentSession}.
 *
 * <pre>{@code
 * try (var client = BtClient.builder().listenPort(6881).build()) {
 *     var session = client.addMagnet("magnet:?xt=urn:btih:...");
 *     var meta = session.awaitMetadata(Duration.ofMinutes(5));
 *     // UI displays meta.files(); after the user selects files:
 *     session.start(DownloadPlan.files(Path.of("/data"), Set.of(0, 2)));
 * }
 * }</pre>
 */
public final class BtClient implements AutoCloseable {

    private static final Logger LOG = System.getLogger(BtClient.class.getName());

    private final PeerId peerId = PeerId.generate();
    private final int listenPort;
    private final int maxPeersPerTorrent;
    private final DhtClient dht; // null = disabled
    private final net.derrek.bt4j.util.RateLimiter downloadLimiter;
    private final net.derrek.bt4j.util.RateLimiter uploadLimiter;
    private final Map<InfoHash, TorrentSession> sessions = new ConcurrentHashMap<>();

    private final ServerSocket listenSocket;
    private volatile boolean closed;

    private BtClient(Builder builder) {
        this.maxPeersPerTorrent = builder.maxPeersPerTorrent;
        // First bind the TCP listen socket to obtain the actual port (assigned by the system when builder.listenPort=0),
        // then have DHT and sessions announce themselves using the same actual port.
        ServerSocket ls = null;
        try {
            ls = new ServerSocket();
            ls.setReuseAddress(true);
            ls.bind(new InetSocketAddress(builder.listenPort));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not bind listen port " + builder.listenPort
                    + ", will only dial out (cannot accept incoming peers)", e);
            ls = null;
        }
        this.listenSocket = ls;
        this.listenPort = ls != null ? ls.getLocalPort() : builder.listenPort;

        // Download has no notion of "blocking yourself": <=0 always means unlimited (pass -1 to RateLimiter)
        this.downloadLimiter = new net.derrek.bt4j.util.RateLimiter(
                builder.downloadRateLimit <= 0 ? -1 : builder.downloadRateLimit);
        // Upload: 0 = blocked (no uploading), <0 = unlimited, >0 = rate-limited
        this.uploadLimiter = new net.derrek.bt4j.util.RateLimiter(builder.uploadRateLimit);
        if (builder.dhtEnabled) {
            DhtClient client = new DhtClient(listenPort, builder.dhtBootstrapNodes);
            client.start();
            this.dht = client;
        } else {
            this.dht = null;
        }
        if (listenSocket != null) {
            Thread.ofVirtual().name("bt4j-accept").start(this::acceptLoop);
            LOG.log(Level.DEBUG, () -> "accepting incoming peers on TCP port " + listenPort);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The actual bound TCP listen port (system-assigned when the builder is passed 0). */
    public int listenPort() {
        return listenPort;
    }

    private void acceptLoop() {
        while (!closed) {
            Socket socket;
            try {
                socket = listenSocket.accept();
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(Level.DEBUG, () -> "accept failed: " + e.getMessage());
                }
                return;
            }
            Thread.ofVirtual().name("bt4j-incoming").start(() -> routeIncoming(socket));
        }
    }

    /** Reads the incoming peer's handshake and dispatches it to the corresponding session by info-hash. */
    private void routeIncoming(Socket socket) {
        try {
            socket.setSoTimeout(10_000);
            // Read exactly the 68-byte handshake (unbuffered; PeerConnection later takes over the same socket)
            byte[] raw = socket.getInputStream().readNBytes(Handshake.LENGTH);
            if (raw.length != Handshake.LENGTH) {
                socket.close();
                return;
            }
            Handshake theirs = Handshake.decode(raw);
            TorrentSession session = sessions.get(theirs.infoHash());
            if (session instanceof DefaultTorrentSession dts) {
                dts.acceptIncoming(socket, theirs);
            } else {
                socket.close(); // unknown torrent
            }
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Adds a magnet link and returns immediately (metadata fetching starts in the background, state FETCHING_METADATA).
     * Peer sources: the magnet link's tr= (trackers), x.pe= (direct addresses), and DHT.
     */
    public TorrentSession addMagnet(String magnetLink) {
        net.derrek.bt4j.metainfo.MagnetUri magnet = net.derrek.bt4j.metainfo.MagnetUri.parse(magnetLink);
        return sessions.computeIfAbsent(magnet.infoHash(),
                hash -> DefaultTorrentSession.fromMagnet(magnet, runtime()));
    }

    private DefaultTorrentSession.Runtime runtime() {
        return new DefaultTorrentSession.Runtime(
                peerId, listenPort, maxPeersPerTorrent, dht, downloadLimiter, uploadLimiter);
    }

    /** Adds a .torrent file (state goes directly to METADATA_READY). */
    public TorrentSession addTorrent(Path torrentFile) {
        return addTorrent(Metainfo.parse(torrentFile));
    }

    public TorrentSession addTorrent(Metainfo metainfo) {
        return sessions.computeIfAbsent(metainfo.infoHash(),
                hash -> new DefaultTorrentSession(metainfo, runtime()));
    }

    /**
     * Restores a session from resume data after a server restart (does not re-download completed pieces, skips already-verified parts).
     * Resume data is self-contained (embeds metadata), so no separate .torrent is needed.
     */
    public TorrentSession restore(ResumeData resumeData) {
        return sessions.computeIfAbsent(resumeData.infoHash(),
                hash -> DefaultTorrentSession.fromResume(resumeData, runtime()));
    }

    public List<TorrentSession> sessions() {
        return List.copyOf(sessions.values());
    }

    public Optional<TorrentSession> session(InfoHash infoHash) {
        return Optional.ofNullable(sessions.get(infoHash));
    }

    /** Closes and removes a session (e.g. discarding a temporary session after a magnet link has fetched its metadata). */
    public void remove(InfoHash infoHash) {
        TorrentSession removed = sessions.remove(infoHash);
        if (removed != null) {
            removed.close();
        }
    }

    public PeerId peerId() {
        return peerId;
    }

    /** Closes all sessions, DHT, and the listen socket. */
    @Override
    public void close() {
        closed = true;
        ServerSocket ls = listenSocket;
        if (ls != null) {
            try {
                ls.close();
            } catch (IOException ignored) {
            }
        }
        for (TorrentSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        if (dht != null) {
            dht.close();
        }
    }

    public static final class Builder {

        private int listenPort = 6881;
        private boolean dhtEnabled = true;
        private int maxPeersPerTorrent = 30;
        private long downloadRateLimit = -1; // default unlimited (<=0 = unlimited)
        private long uploadRateLimit = -1;   // default unlimited (0 = no uploading, <0 = unlimited)
        private List<InetSocketAddress> dhtBootstrapNodes = DhtClient.DEFAULT_BOOTSTRAP_NODES;

        private Builder() {
        }

        /**
         * The TCP listen port for the peer wire, also used as the DHT UDP port. Default 6881;
         * pass 0 to let the system assign one (see {@link BtClient#listenPort()} for the actual port).
         */
        public Builder listenPort(int port) {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
            this.listenPort = port;
            return this;
        }

        /** Disables DHT (enabled by default). Private torrents never use DHT regardless of this setting. */
        public Builder dhtEnabled(boolean enabled) {
            this.dhtEnabled = enabled;
            return this;
        }

        /**
         * Overrides the DHT bootstrap nodes (default {@link DhtClient#DEFAULT_BOOTSTRAP_NODES}).
         * Used only on a cold start; existing nodes take priority when a resumed routing table is available.
         */
        public Builder dhtBootstrapNodes(List<InetSocketAddress> nodes) {
            this.dhtBootstrapNodes = List.copyOf(nodes);
            return this;
        }

        /** Maximum number of peer connections per torrent, default 30. */
        public Builder maxPeersPerTorrent(int max) {
            if (max < 1) {
                throw new IllegalArgumentException("maxPeersPerTorrent must be positive: " + max);
            }
            this.maxPeersPerTorrent = max;
            return this;
        }

        /**
         * Global download rate limit (bytes/s, shared across all torrents).
         * {@code <= 0} unlimited; {@code > 0} caps at that rate.
         */
        public Builder downloadRateLimit(long bytesPerSec) {
            this.downloadRateLimit = bytesPerSec;
            return this;
        }

        /**
         * Global upload rate limit (bytes/s, shared across all torrents).
         * {@code == 0} no uploading at all (keeps peers choked and rejects requests during download/seeding);
         * {@code < 0} unlimited; {@code > 0} caps at that rate.
         */
        public Builder uploadRateLimit(long bytesPerSec) {
            this.uploadRateLimit = bytesPerSec;
            return this;
        }

        public BtClient build() {
            return new BtClient(this);
        }
    }
}
