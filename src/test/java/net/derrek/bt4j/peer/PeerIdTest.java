package net.derrek.bt4j.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PeerIdTest {

    @Test
    void generatedIdHasPrefixAndLength() {
        PeerId id = PeerId.generate();
        assertEquals(20, id.bytes().length);
        assertEquals(PeerId.CLIENT_PREFIX,
                new String(id.bytes(), 0, PeerId.CLIENT_PREFIX.length(), StandardCharsets.US_ASCII));
    }

    @Test
    void generatedIdsAreUnique() {
        assertNotEquals(PeerId.generate(), PeerId.generate());
    }

    @Test
    void randomPartIsPrintable() {
        PeerId id = PeerId.generate();
        for (int i = PeerId.CLIENT_PREFIX.length(); i < 20; i++) {
            byte b = id.bytes()[i];
            assertTrue(b >= 0x20 && b < 0x7F, "position " + i + " is not a printable character: " + b);
        }
    }

    @Test
    void rejectWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> new PeerId(new byte[19]));
    }
}
