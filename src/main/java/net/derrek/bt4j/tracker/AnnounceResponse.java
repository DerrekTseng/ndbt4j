package net.derrek.bt4j.tracker;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import net.derrek.bt4j.peer.PeerAddress;

/**
 * tracker announce 回應（BEP 3 / compact 格式 BEP 23）。
 *
 * @param interval 下次 announce 前應等待的時間
 * @param seeders  完成者數（complete，tracker 可能不提供）
 * @param leechers 未完成者數（incomplete，tracker 可能不提供）
 * @param peers    peer 清單
 */
public record AnnounceResponse(Duration interval,
                               OptionalInt seeders,
                               OptionalInt leechers,
                               List<PeerAddress> peers) {
}
