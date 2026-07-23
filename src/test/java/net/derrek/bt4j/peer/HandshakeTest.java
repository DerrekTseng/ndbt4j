package net.derrek.bt4j.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.derrek.bt4j.metainfo.InfoHash;
import org.junit.jupiter.api.Test;

class HandshakeTest {

    private static final InfoHash HASH = InfoHash.fromHex("417999cdf5411a6522abeb34c2059434a69d1854");

    @Test
    void roundTrip() {
        Handshake original = Handshake.outgoing(HASH, PeerId.generate(), true, true, true);
        byte[] encoded = original.encode();
        assertEquals(68, encoded.length);

        Handshake decoded = Handshake.decode(encoded);
        assertEquals(HASH, decoded.infoHash());
        assertEquals(original.peerId(), decoded.peerId());
        assertTrue(decoded.supportsDht());
        assertTrue(decoded.supportsExtensionProtocol());
        assertTrue(decoded.supportsFastExtension());
    }

    @Test
    void reservedBitsIndependent() {
        Handshake extOnly = Handshake.outgoing(HASH, PeerId.generate(), false, true, false);
        assertTrue(extOnly.supportsExtensionProtocol());
        assertFalse(extOnly.supportsDht());
        assertFalse(extOnly.supportsFastExtension());

        Handshake none = Handshake.outgoing(HASH, PeerId.generate(), false, false, false);
        assertFalse(none.supportsExtensionProtocol());
    }

    @Test
    void rejectMalformed() {
        assertThrows(IllegalArgumentException.class, () -> Handshake.decode(new byte[67]));
        byte[] wrongProtocol = Handshake.outgoing(HASH, PeerId.generate(), false, false, false).encode();
        wrongProtocol[1] = 'X';
        assertThrows(IllegalArgumentException.class, () -> Handshake.decode(wrongProtocol));
    }
}
