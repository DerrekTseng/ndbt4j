package net.derrek.bt4j.dht;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.PeerAddress;

/**
 * DHT 客戶端（BEP 5）：單一 DatagramSocket 上的 KRPC，
 * 全域一個實例，所有 torrent 共用。
 * 對外只暴露兩個能力：找 peer、宣告自己是 peer。
 */
public final class DhtClient implements AutoCloseable {

    /** 常用 bootstrap 節點：router.bittorrent.com:6881、dht.transmissionbt.com:6881。 */
    public DhtClient(int udpPort, List<InetSocketAddress> bootstrapNodes) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 啟動：綁定 socket、對 bootstrap 節點 find_node 填充路由表。 */
    public void start() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /**
     * 迭代 get_peers 查詢。回傳的 future 會在找到 peer 或查詢收斂時完成；
     * 找不到時以空清單完成（不以例外表示死種）。
     */
    public CompletableFuture<List<PeerAddress>> findPeers(InfoHash infoHash) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 對負責節點 announce_peer，宣告本機在 tcpPort 提供此 torrent。 */
    public void announce(InfoHash infoHash, int tcpPort) {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("尚未實作");
    }
}
