package net.derrek.bt4j.tracker;

import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.PeerId;

/**
 * 一次 tracker announce 的參數（BEP 3）。
 *
 * @param infoHash   目標 torrent
 * @param peerId     本機 peer id
 * @param port       本機接受連入的 TCP port
 * @param uploaded   已上傳 bytes
 * @param downloaded 已下載 bytes
 * @param left       剩餘 bytes（選擇性下載時 = 勾選部分的剩餘量）
 * @param event      事件
 * @param numWant    希望取得的 peer 數（慣例 50）
 */
public record AnnounceRequest(InfoHash infoHash,
                              PeerId peerId,
                              int port,
                              long uploaded,
                              long downloaded,
                              long left,
                              AnnounceEvent event,
                              int numWant) {
}
