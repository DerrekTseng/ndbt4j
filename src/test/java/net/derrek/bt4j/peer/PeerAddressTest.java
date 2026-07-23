package net.derrek.bt4j.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PeerAddressTest {

    @Test
    void parseCompactIpv4() {
        // 192.168.1.10:51413 (0xC8D5) 與 10.0.0.1:6881 (0x1AE1)
        byte[] compact = {
                (byte) 192, (byte) 168, 1, 10, (byte) 0xC8, (byte) 0xD5,
                10, 0, 0, 1, 0x1A, (byte) 0xE1};
        List<PeerAddress> peers = PeerAddress.fromCompact(compact);

        assertEquals(2, peers.size());
        assertEquals("192.168.1.10", peers.get(0).socketAddress().getAddress().getHostAddress());
        assertEquals(51413, peers.get(0).socketAddress().getPort());
        assertEquals("10.0.0.1", peers.get(1).socketAddress().getAddress().getHostAddress());
        assertEquals(6881, peers.get(1).socketAddress().getPort());
    }

    @Test
    void portZeroEntriesAreSkipped() {
        byte[] compact = {(byte) 192, (byte) 168, 1, 10, 0, 0};
        assertTrue(PeerAddress.fromCompact(compact).isEmpty());
    }

    @Test
    void parseCompactIpv6() {
        byte[] compact = new byte[18];
        compact[15] = 1; // ::1
        compact[16] = 0x1A;
        compact[17] = (byte) 0xE1;
        List<PeerAddress> peers = PeerAddress.fromCompact6(compact);

        assertEquals(1, peers.size());
        assertEquals("0:0:0:0:0:0:0:1", peers.get(0).socketAddress().getAddress().getHostAddress());
        assertEquals(6881, peers.get(0).socketAddress().getPort());
    }

    @Test
    void emptyInputYieldsEmptyList() {
        assertTrue(PeerAddress.fromCompact(new byte[0]).isEmpty());
    }

    @Test
    void rejectBadLength() {
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.fromCompact(new byte[7]));
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.fromCompact6(new byte[6]));
    }
}
