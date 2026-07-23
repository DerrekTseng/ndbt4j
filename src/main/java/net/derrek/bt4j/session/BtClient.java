package net.derrek.bt4j.session;

import java.nio.file.Path;
import java.util.List;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.Metainfo;
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

    private BtClient() {
    }

    public static Builder builder() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 加入磁力連結，立即回傳（背景開始取 metadata，狀態 FETCHING_METADATA）。 */
    public TorrentSession addMagnet(String magnetLink) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 加入 .torrent 檔（狀態直接 METADATA_READY）。 */
    public TorrentSession addTorrent(Path torrentFile) {
        throw new UnsupportedOperationException("尚未實作");
    }

    public TorrentSession addTorrent(Metainfo metainfo) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 伺服器重啟後由 resume 資料恢復 session（不重新下載已完成 piece）。 */
    public TorrentSession restore(ResumeData resumeData) {
        throw new UnsupportedOperationException("尚未實作");
    }

    public List<TorrentSession> sessions() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public java.util.Optional<TorrentSession> session(InfoHash infoHash) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 關閉所有 session、DHT 與 listen socket。 */
    @Override
    public void close() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public static final class Builder {

        /** peer wire 的 TCP listen port，同時作為 DHT UDP port。預設 6881。 */
        public Builder listenPort(int port) {
            throw new UnsupportedOperationException("尚未實作");
        }

        /** 停用 DHT（預設啟用）。private torrent 無論此設定皆不用 DHT。 */
        public Builder dhtEnabled(boolean enabled) {
            throw new UnsupportedOperationException("尚未實作");
        }

        /**
         * 覆寫 DHT bootstrap 節點（預設 {@link net.derrek.bt4j.dht.DhtClient#DEFAULT_BOOTSTRAP_NODES}）。
         * 僅冷啟動時使用；有 resume 的路由表時優先用既有節點。
         */
        public Builder dhtBootstrapNodes(java.util.List<java.net.InetSocketAddress> nodes) {
            throw new UnsupportedOperationException("尚未實作");
        }

        /** 每個 torrent 的最大 peer 連線數，預設 50。 */
        public Builder maxPeersPerTorrent(int max) {
            throw new UnsupportedOperationException("尚未實作");
        }

        public BtClient build() {
            throw new UnsupportedOperationException("尚未實作");
        }
    }
}
