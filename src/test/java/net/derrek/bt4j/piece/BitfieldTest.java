package net.derrek.bt4j.piece;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BitfieldTest {

    @Test
    void setAndGetMsbFirst() {
        Bitfield bf = new Bitfield(10);
        bf.set(0);
        bf.set(9);
        assertTrue(bf.get(0));
        assertTrue(bf.get(9));
        assertFalse(bf.get(1));
        assertEquals(2, bf.cardinality());
        // piece 0 = MSB of the first byte; piece 9 = bit 1 of the second byte (MSB-first ordering)
        assertArrayEquals(new byte[] {(byte) 0x80, 0x40}, bf.toBytes());
    }

    @Test
    void roundTrip() {
        Bitfield bf = new Bitfield(13);
        bf.set(3);
        bf.set(12);
        Bitfield parsed = Bitfield.fromBytes(bf.toBytes(), 13);
        assertEquals(bf.cardinality(), parsed.cardinality());
        assertTrue(parsed.get(3));
        assertTrue(parsed.get(12));
    }

    @Test
    void completeness() {
        Bitfield bf = new Bitfield(9);
        assertFalse(bf.isComplete());
        bf.setAll();
        assertTrue(bf.isComplete());
        assertEquals(9, bf.cardinality());
        bf.set(3); // setting again does not affect cardinality
        assertEquals(9, bf.cardinality());
    }

    @Test
    void copyIsIndependent() {
        Bitfield bf = new Bitfield(8);
        bf.set(0);
        Bitfield copy = bf.copy();
        copy.set(1);
        assertFalse(bf.get(1));
        assertEquals(1, bf.cardinality());
        assertEquals(2, copy.cardinality());
    }

    @Test
    void rejectMalformedWireFormat() {
        assertThrows(IllegalArgumentException.class, () -> Bitfield.fromBytes(new byte[2], 20)); // wrong length
        // padding bit not 0: 10 pieces need 2 bytes, the last 6 bits must be 0
        assertThrows(IllegalArgumentException.class, () -> Bitfield.fromBytes(new byte[] {0, 0x01}, 10));
        assertThrows(IllegalArgumentException.class, () -> new Bitfield(0));
    }
}
