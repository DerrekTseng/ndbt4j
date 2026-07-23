package net.derrek.bt4j.tracker;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.security.SecureRandom;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.PeerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 需要網路的整合測試，預設不執行。
 * 執行方式：{@code mvn test -Dbt4j.integration=true}
 */
@EnabledIfSystemProperty(named = "bt4j.integration", matches = "true")
class HttpTrackerIntegrationTest {

    @Test
    void announceToPublicTracker() throws TrackerException {
        // 隨機 info-hash：只驗證協定往返（tracker 對未知 hash 回空 peer 清單 + interval）
        byte[] randomHash = new byte[20];
        new SecureRandom().nextBytes(randomHash);

        HttpTracker tracker = new HttpTracker(URI.create("http://tracker.opentrackr.org:1337/announce"));
        AnnounceResponse response = tracker.announce(new AnnounceRequest(
                new InfoHash(randomHash), PeerId.generate(), 6881, 0, 0, 0, AnnounceEvent.STARTED, 10));

        assertTrue(response.interval().toSeconds() > 0, "interval 應為正數");
        // 對隨機 hash，peers 幾乎必為空，但不強制（萬一撞到真 hash）
    }
}
