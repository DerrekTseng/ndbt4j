package net.derrek.bt4j.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.piece.Bitfield;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResumeDataTest {

    private static Metainfo meta() {
        return TorrentFixtures.singleFile("r.bin", TorrentFixtures.randomBytes(40000, 3),
                16384, "http://tracker.example.com/announce");
    }

    private static ResumeData sample(Metainfo meta) {
        Bitfield completed = new Bitfield(meta.pieceCount());
        completed.set(0);
        completed.set(2);
        return new ResumeData(meta.toTorrentBytes(), completed, Set.of(0),
                Path.of("D:/downloads"), 12345, false);
    }

    @Test
    void encodeDecodeRoundTrip() {
        Metainfo meta = meta();
        ResumeData original = sample(meta);
        ResumeData decoded = ResumeData.decode(original.encode());

        assertEquals(original.infoHash(), decoded.infoHash());
        assertArrayEquals(original.completedPieces().toBytes(), decoded.completedPieces().toBytes());
        assertEquals(2, decoded.completedPieces().cardinality());
        assertEquals(Set.of(0), decoded.selectedFileIndices());
        assertEquals(Path.of("D:/downloads"), decoded.saveTo());
        assertEquals(12345, decoded.uploaded());
        assertFalse(decoded.seedingStopped());
        assertEquals(meta.name(), decoded.metainfo().name());
    }

    @Test
    void saveAndLoadFromFile(@TempDir Path tmp) throws Exception {
        Metainfo meta = meta();
        ResumeData original = sample(meta);
        Path file = tmp.resolve("state.resume");
        original.save(file);

        ResumeData loaded = ResumeData.load(file);
        assertEquals(original.infoHash(), loaded.infoHash());
        assertEquals(original.uploaded(), loaded.uploaded());
        assertTrue(loaded.completedPieces().get(2));
    }

    @Test
    void stoppedAndEmptySelectionPreserved() {
        Metainfo meta = meta();
        ResumeData original = new ResumeData(meta.toTorrentBytes(),
                new Bitfield(meta.pieceCount()), Set.of(), Path.of("D:/x"), 0, true);
        ResumeData decoded = ResumeData.decode(original.encode());
        assertTrue(decoded.seedingStopped());
        assertTrue(decoded.selectedFileIndices().isEmpty());
        assertEquals(0, decoded.completedPieces().cardinality());
    }
}
