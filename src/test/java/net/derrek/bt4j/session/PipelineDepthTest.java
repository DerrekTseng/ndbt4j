package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** RTT-based pipeline depth (bandwidth-delay product): DefaultTorrentSession.pipelineDepth. */
class PipelineDepthTest {

    private static final long MS = 1_000_000L;

    @Test
    void zeroRateUsesTheFloor() {
        assertEquals(16, DefaultTorrentSession.pipelineDepth(0, 50 * MS));
    }

    @Test
    void extremeRateAndRttIsCapped() {
        // huge rate x huge RTT would be enormous; capped at MAX_PIPELINE
        assertEquals(256, DefaultTorrentSession.pipelineDepth(100_000_000L, 1_000 * MS));
    }

    @Test
    void lowRttFastPeerStaysShallow() {
        // 5 MB/s, 5ms RTT: BDP is tiny -> window ~= 12.5ms -> ~4 blocks -> floor 16
        int depth = DefaultTorrentSession.pipelineDepth(5_000_000L, 5 * MS);
        assertEquals(16, depth);
    }

    @Test
    void highRttSameRateNeedsDeeperPipeline() {
        long rate = 3_000_000L; // 3 MB/s
        int lowRtt = DefaultTorrentSession.pipelineDepth(rate, 20 * MS);
        int highRtt = DefaultTorrentSession.pipelineDepth(rate, 300 * MS);
        assertTrue(highRtt > lowRtt, "higher RTT should need a deeper pipeline: " + lowRtt + " vs " + highRtt);
        // 3 MB/s x (0.3s x 2.5) / 16 KiB ~= 137
        assertTrue(highRtt >= 120 && highRtt <= 160, "unexpected depth: " + highRtt);
    }

    @Test
    void monotonicInRttAndRate() {
        assertTrue(DefaultTorrentSession.pipelineDepth(2_000_000L, 400 * MS)
                >= DefaultTorrentSession.pipelineDepth(2_000_000L, 100 * MS));
        assertTrue(DefaultTorrentSession.pipelineDepth(4_000_000L, 200 * MS)
                >= DefaultTorrentSession.pipelineDepth(1_000_000L, 200 * MS));
    }
}
