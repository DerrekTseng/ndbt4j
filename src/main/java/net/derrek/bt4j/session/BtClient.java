package net.derrek.bt4j.session;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.derrek.bt4j.dht.DhtClient;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.peer.PeerId;
import net.derrek.bt4j.storage.ResumeData;

/**
 * 套件對外入口。一個程序通常只建一個實例：
 * 持有 listen socket、DHT、peer id，管理多個 {@link TorrentSession}。
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

    private final PeerId peerId = PeerId.generate();
    private final int listenPort;
    private final int maxPeersPerTorrent;
    private final DhtClient dht; // null = 停用
    private final Map<InfoHash, TorrentSession> sessions = new ConcurrentHashMap<>();

    private BtClient(Builder builder) {
        this.listenPort = builder.listenPort;
        this.maxPeersPerTorrent = builder.maxPeersPerTorrent;
        if (builder.dhtEnabled) {
            DhtClient client = new DhtClient(listenPort, builder.dhtBootstrapNodes);
            client.start();
            this.dht = client;
        } else {
            this.dht = null;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 加入磁力連結，立即回傳（背景開始取 metadata，狀態 FETCHING_METADATA）。
     * peer 來源：磁力連結的 tr=（tracker）與 x.pe=（直連位址）；DHT 於 M7 加入。
     */
    public TorrentSession addMagnet(String magnetLink) {
        net.derrek.bt4j.metainfo.MagnetUri magnet = net.derrek.bt4j.metainfo.MagnetUri.parse(magnetLink);
        return sessions.computeIfAbsent(magnet.infoHash(),
                hash -> DefaultTorrentSession.fromMagnet(magnet, peerId, listenPort, maxPeersPerTorrent, dht));
    }

    /** 加入 .torrent 檔（狀態直接 METADATA_READY）。 */
    public TorrentSession addTorrent(Path torrentFile) {
        return addTorrent(Metainfo.parse(torrentFile));
    }

    public TorrentSession addTorrent(Metainfo metainfo) {
        return sessions.computeIfAbsent(metainfo.infoHash(),
                hash -> new DefaultTorrentSession(metainfo, peerId, listenPort, maxPeersPerTorrent, dht));
    }

    /** 伺服器重啟後由 resume 資料恢復 session（不重新下載已完成 piece）。M8 實作。 */
    public TorrentSession restore(ResumeData resumeData) {
        throw new UnsupportedOperationException("尚未實作（M8）");
    }

    public List<TorrentSession> sessions() {
        return List.copyOf(sessions.values());
    }

    public Optional<TorrentSession> session(InfoHash infoHash) {
        return Optional.ofNullable(sessions.get(infoHash));
    }

    public PeerId peerId() {
        return peerId;
    }

    /** 關閉所有 session、DHT 與 listen socket。 */
    @Override
    public void close() {
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
        private List<InetSocketAddress> dhtBootstrapNodes = DhtClient.DEFAULT_BOOTSTRAP_NODES;

        private Builder() {
        }

        /** peer wire 的 TCP listen port，同時作為 DHT UDP port。預設 6881。 */
        public Builder listenPort(int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port 超出範圍: " + port);
            }
            this.listenPort = port;
            return this;
        }

        /** 停用 DHT（預設啟用）。private torrent 無論此設定皆不用 DHT。（M7 生效） */
        public Builder dhtEnabled(boolean enabled) {
            this.dhtEnabled = enabled;
            return this;
        }

        /**
         * 覆寫 DHT bootstrap 節點（預設 {@link DhtClient#DEFAULT_BOOTSTRAP_NODES}）。
         * 僅冷啟動時使用；有 resume 的路由表時優先用既有節點。（M7 生效）
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

        public BtClient build() {
            return new BtClient(this);
        }
    }
}
