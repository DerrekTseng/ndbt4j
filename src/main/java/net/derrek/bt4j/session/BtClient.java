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
 * 套件對外入口。一個程序通常只建一個實例：
 * 持有 listen socket（接受連入 peer）、DHT、peer id，管理多個 {@link TorrentSession}。
 *
 * <pre>{@code
 * try (var client = BtClient.builder().listenPort(6881).build()) {
 *     var session = client.addMagnet("magnet:?xt=urn:btih:...");
 *     var meta = session.awaitMetadata(Duration.ofMinutes(5));
 *     // UI 顯示 meta.files()，使用者勾選後：
 *     session.start(DownloadPlan.files(Path.of("/data"), Set.of(0, 2)));
 * }
 * }</pre>
 */
public final class BtClient implements AutoCloseable {

    private static final Logger LOG = System.getLogger(BtClient.class.getName());

    private final PeerId peerId = PeerId.generate();
    private final int listenPort;
    private final int maxPeersPerTorrent;
    private final DhtClient dht; // null = 停用
    private final net.derrek.bt4j.util.RateLimiter downloadLimiter;
    private final net.derrek.bt4j.util.RateLimiter uploadLimiter;
    private final Map<InfoHash, TorrentSession> sessions = new ConcurrentHashMap<>();

    private final ServerSocket listenSocket;
    private volatile boolean closed;

    private BtClient(Builder builder) {
        this.maxPeersPerTorrent = builder.maxPeersPerTorrent;
        // 先綁定 TCP listen socket 取得實際 port（builder.listenPort=0 時由系統指派），
        // 再讓 DHT 與 session 使用同一個實際 port 對外宣告。
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

        // 下載沒有「封鎖自己」的概念：<=0 一律視為不限速（傳 -1 給 RateLimiter）
        this.downloadLimiter = new net.derrek.bt4j.util.RateLimiter(
                builder.downloadRateLimit <= 0 ? -1 : builder.downloadRateLimit);
        // 上傳：0 = 封鎖（不上傳）、<0 = 不限、>0 = 限速
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

    /** 實際綁定的 TCP listen port（builder 傳 0 時為系統指派的 port）。 */
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

    /** 讀出連入 peer 的 handshake，依 info-hash 交給對應的 session。 */
    private void routeIncoming(Socket socket) {
        try {
            socket.setSoTimeout(10_000);
            // 精確讀出 68 bytes handshake（不緩衝，後續由 PeerConnection 接手同一 socket）
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
                socket.close(); // 未知 torrent
            }
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 加入磁力連結，立即回傳（背景開始取 metadata，狀態 FETCHING_METADATA）。
     * peer 來源：磁力連結的 tr=（tracker）、x.pe=（直連位址）與 DHT。
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

    /** 加入 .torrent 檔（狀態直接 METADATA_READY）。 */
    public TorrentSession addTorrent(Path torrentFile) {
        return addTorrent(Metainfo.parse(torrentFile));
    }

    public TorrentSession addTorrent(Metainfo metainfo) {
        return sessions.computeIfAbsent(metainfo.infoHash(),
                hash -> new DefaultTorrentSession(metainfo, runtime()));
    }

    /**
     * 伺服器重啟後由 resume 資料恢復 session（不重新下載已完成 piece，跳過已驗證部分）。
     * resume 資料自足（內嵌 metadata），無需另外提供 .torrent。
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

    /** 關閉並移除某個 session（例如磁力連結取得 metadata 後丟棄暫時 session）。 */
    public void remove(InfoHash infoHash) {
        TorrentSession removed = sessions.remove(infoHash);
        if (removed != null) {
            removed.close();
        }
    }

    public PeerId peerId() {
        return peerId;
    }

    /** 關閉所有 session、DHT 與 listen socket。 */
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
        private long downloadRateLimit = -1; // 預設不限（<=0 = 不限）
        private long uploadRateLimit = -1;   // 預設不限（0 = 不上傳、<0 = 不限）
        private List<InetSocketAddress> dhtBootstrapNodes = DhtClient.DEFAULT_BOOTSTRAP_NODES;

        private Builder() {
        }

        /**
         * peer wire 的 TCP listen port，同時作為 DHT UDP port。預設 6881；
         * 傳 0 表示由系統指派（實際 port 見 {@link BtClient#listenPort()}）。
         */
        public Builder listenPort(int port) {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port 超出範圍: " + port);
            }
            this.listenPort = port;
            return this;
        }

        /** 停用 DHT（預設啟用）。private torrent 無論此設定皆不用 DHT。 */
        public Builder dhtEnabled(boolean enabled) {
            this.dhtEnabled = enabled;
            return this;
        }

        /**
         * 覆寫 DHT bootstrap 節點（預設 {@link DhtClient#DEFAULT_BOOTSTRAP_NODES}）。
         * 僅冷啟動時使用；有 resume 的路由表時優先用既有節點。
         */
        public Builder dhtBootstrapNodes(List<InetSocketAddress> nodes) {
            this.dhtBootstrapNodes = List.copyOf(nodes);
            return this;
        }

        /** 每個 torrent 的最大 peer 連線數，預設 30。 */
        public Builder maxPeersPerTorrent(int max) {
            if (max < 1) {
                throw new IllegalArgumentException("maxPeersPerTorrent 必須為正: " + max);
            }
            this.maxPeersPerTorrent = max;
            return this;
        }

        /**
         * 全域下載速率上限（bytes/s，所有 torrent 共用）。
         * {@code <= 0} 不限速；{@code > 0} 限制在該速率。
         */
        public Builder downloadRateLimit(long bytesPerSec) {
            this.downloadRateLimit = bytesPerSec;
            return this;
        }

        /**
         * 全域上傳速率上限（bytes/s，所有 torrent 共用）。
         * {@code == 0} 完全不上傳（下載/做種期間對 peer 保持 choke、拒絕 request）；
         * {@code < 0} 不限速；{@code > 0} 限制在該速率。
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
