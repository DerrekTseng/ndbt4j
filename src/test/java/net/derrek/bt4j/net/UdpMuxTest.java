package net.derrek.bt4j.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The first-byte discriminator that routes datagrams between uTP and KRPC (DHT). */
class UdpMuxTest {

    @Test
    void utpTypeVersionBytesAreRecognised() {
        // (type << 4) | version, version 1, types 0..4
        for (int type = 0; type <= 4; type++) {
            byte first = (byte) ((type << 4) | 1);
            assertTrue(UdpMux.isUtp(first), "type " + type + " version 1 should be uTP");
        }
    }

    @Test
    void bencodedKrpcIsNotUtp() {
        assertFalse(UdpMux.isUtp((byte) 'd'), "KRPC starts with 'd' (0x64) -> not uTP");
    }

    @Test
    void wrongVersionOrHighTypeIsNotUtp() {
        assertFalse(UdpMux.isUtp((byte) ((0 << 4) | 2)), "version 2 is not uTP v1");
        assertFalse(UdpMux.isUtp((byte) ((5 << 4) | 1)), "type 5 is not a valid uTP packet type");
        assertFalse(UdpMux.isUtp((byte) 0x00), "all-zero is neither");
    }
}
