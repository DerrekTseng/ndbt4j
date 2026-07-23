package net.derrek.bt4j.piece;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import org.junit.jupiter.api.Test;

class PieceSelectionTest {

    /**
     * Layout (pieceLength=16384):
     * a.bin: [0, 20000)          -> piece 0, 1
     * b.bin: [20000, 40000)      -> piece 1, 2 (shares boundary pieces with a and c)
     * c.bin: [40000, 50000)      -> piece 2, 3
     * Total length 50000 -> 4 pieces (the last one 962 bytes)
     */
    private static Metainfo layout() {
        return TorrentFixtures.multiFile("sel", List.of(
                new TorrentFixtures.TestFile(List.of("a.bin"), TorrentFixtures.randomBytes(20000, 1)),
                new TorrentFixtures.TestFile(List.of("b.bin"), TorrentFixtures.randomBytes(20000, 2)),
                new TorrentFixtures.TestFile(List.of("c.bin"), TorrentFixtures.randomBytes(10000, 3))),
                16384, "http://t.example.com/announce");
    }

    @Test
    void emptySelectionMeansAllFiles() {
        PieceSelection selection = PieceSelection.of(layout(), Set.of());
        assertEquals(50000, selection.wantedBytes());
        assertEquals(4, selection.wantedPieceCount());
        assertTrue(selection.isFileWanted(0));
        assertTrue(selection.isFileWanted(2));
    }

    @Test
    void middleFileSelectsBoundaryPieces() {
        PieceSelection selection = PieceSelection.of(layout(), Set.of(1)); // select only b.bin
        assertEquals(20000, selection.wantedBytes());
        assertFalse(selection.isWanted(0));
        assertTrue(selection.isWanted(1));  // boundary: includes the tail of a
        assertTrue(selection.isWanted(2));  // boundary: includes the head of c
        assertFalse(selection.isWanted(3));
        assertFalse(selection.isFileWanted(0));
        assertTrue(selection.isFileWanted(1));

        // piece 1 = [16384, 32768), b occupies [20000, 32768) = 12768 bytes
        assertEquals(12768, selection.wantedBytesInPiece(1));
        // piece 2 = [32768, 49152), b occupies [32768, 40000) = 7232 bytes
        assertEquals(7232, selection.wantedBytesInPiece(2));
        assertEquals(0, selection.wantedBytesInPiece(0));
        assertEquals(20000, selection.wantedBytesInPiece(1) + selection.wantedBytesInPiece(2));
    }

    @Test
    void lastFileIncludesFinalShortPiece() {
        PieceSelection selection = PieceSelection.of(layout(), Set.of(2)); // select only c.bin
        assertFalse(selection.isWanted(0));
        assertFalse(selection.isWanted(1));
        assertTrue(selection.isWanted(2));
        assertTrue(selection.isWanted(3));
        assertEquals(10000, selection.wantedBytes());
    }

    @Test
    void rejectOutOfRangeIndex() {
        assertThrows(IllegalArgumentException.class, () -> PieceSelection.of(layout(), Set.of(3)));
        assertThrows(IllegalArgumentException.class, () -> PieceSelection.of(layout(), Set.of(-1)));
    }
}
