package net.derrek.bt4j.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.piece.PieceSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStorageTest {

    private static final int PIECE_LENGTH = 16384;

    private static void writePiece(FileStorage storage, Metainfo meta, byte[] content, int piece) {
        int start = piece * PIECE_LENGTH;
        int length = meta.pieceLengthAt(piece);
        storage.write(piece, 0, Arrays.copyOfRange(content, start, start + length));
    }

    @Test
    void singleFileWriteVerifyRead(@TempDir Path tmp) throws IOException {
        byte[] content = TorrentFixtures.randomBytes(40000, 11); // 3 pieces
        Metainfo meta = TorrentFixtures.singleFile("s.bin", content, PIECE_LENGTH, "http://t/a");
        try (FileStorage storage = new FileStorage(meta, PieceSelection.of(meta, Set.of()), tmp)) {

            for (int p = 0; p < meta.pieceCount(); p++) {
                writePiece(storage, meta, content, p);
                assertTrue(storage.verifyPiece(p), "piece " + p + " should pass verification");
            }
            assertTrue(storage.completedPieces().isComplete());
            assertArrayEquals(content, Files.readAllBytes(tmp.resolve("s.bin")));

            // read across a piece boundary
            byte[] read = storage.read(0, PIECE_LENGTH - 10, 10);
            assertArrayEquals(Arrays.copyOfRange(content, PIECE_LENGTH - 10, PIECE_LENGTH), read);
        }
    }

    @Test
    void corruptPieceIsRejectedAndDiscarded(@TempDir Path tmp) throws IOException {
        byte[] content = TorrentFixtures.randomBytes(20000, 12); // 2 pieces
        Metainfo meta = TorrentFixtures.singleFile("c.bin", content, PIECE_LENGTH, "http://t/a");
        try (FileStorage storage = new FileStorage(meta, PieceSelection.of(meta, Set.of()), tmp)) {

            byte[] corrupt = Arrays.copyOfRange(content, 0, PIECE_LENGTH);
            corrupt[100] ^= 0x01;
            storage.write(0, 0, corrupt);
            assertFalse(storage.verifyPiece(0));
            assertFalse(storage.completedPieces().get(0));

            // after discarding, rewriting the correct data passes
            writePiece(storage, meta, content, 0);
            assertTrue(storage.verifyPiece(0));
        }
    }

    @Test
    void blocksAccumulateIntoPiece(@TempDir Path tmp) throws IOException {
        byte[] content = TorrentFixtures.randomBytes(PIECE_LENGTH, 13); // exactly 1 piece
        Metainfo meta = TorrentFixtures.singleFile("b.bin", content, PIECE_LENGTH, "http://t/a");
        try (FileStorage storage = new FileStorage(meta, PieceSelection.of(meta, Set.of()), tmp)) {

            int half = PIECE_LENGTH / 2;
            storage.write(0, half, Arrays.copyOfRange(content, half, PIECE_LENGTH)); // out-of-order write
            storage.write(0, 0, Arrays.copyOfRange(content, 0, half));
            assertTrue(storage.verifyPiece(0));
            assertArrayEquals(content, Files.readAllBytes(tmp.resolve("b.bin")));
        }
    }

    @Test
    void unselectedFilesAreNotCreatedAndBoundaryBytesDiscarded(@TempDir Path tmp) throws IOException {
        // a[0,20000) b[20000,40000) c[40000,50000); pieces 1/2 are b's boundary pieces
        byte[] a = TorrentFixtures.randomBytes(20000, 21);
        byte[] b = TorrentFixtures.randomBytes(20000, 22);
        byte[] c = TorrentFixtures.randomBytes(10000, 23);
        byte[] all = new byte[50000];
        System.arraycopy(a, 0, all, 0, 20000);
        System.arraycopy(b, 0, all, 20000, 20000);
        System.arraycopy(c, 0, all, 40000, 10000);
        Metainfo meta = TorrentFixtures.multiFile("multi", List.of(
                new TorrentFixtures.TestFile(List.of("a.bin"), a),
                new TorrentFixtures.TestFile(List.of("sub", "b.bin"), b),
                new TorrentFixtures.TestFile(List.of("c.bin"), c)), PIECE_LENGTH, "http://t/a");

        try (FileStorage storage = new FileStorage(meta, PieceSelection.of(meta, Set.of(1)), tmp)) {
            // pieces 1 and 2 needed for b (downloaded in full to verify)
            writePiece(storage, meta, all, 1);
            writePiece(storage, meta, all, 2);
            assertTrue(storage.verifyPiece(1));
            assertTrue(storage.verifyPiece(2));

            // only b.bin lands on disk; a and c are not created
            assertTrue(Files.exists(tmp.resolve("multi").resolve("sub").resolve("b.bin")));
            assertFalse(Files.exists(tmp.resolve("multi").resolve("a.bin")));
            assertFalse(Files.exists(tmp.resolve("multi").resolve("c.bin")));

            // b.bin content is correct (a/c ranges of the boundary pieces discarded, b range written to the right position)
            byte[] written = Files.readAllBytes(tmp.resolve("multi").resolve("sub").resolve("b.bin"));
            assertArrayEquals(b, written);
        }
    }

    @Test
    void concurrentWriteAndVerifyAcrossPieces(@TempDir Path tmp) throws Exception {
        // Many pieces written and verified concurrently from separate threads: exercises the lock-free SHA-1
        // path in verifyPiece (hashing runs outside the storage monitor) and must still assemble byte-perfectly.
        byte[] content = TorrentFixtures.randomBytes(64 * PIECE_LENGTH + 5000, 77); // 65 pieces
        Metainfo meta = TorrentFixtures.singleFile("cc.bin", content, PIECE_LENGTH, "http://t/a");
        try (FileStorage storage = new FileStorage(meta, PieceSelection.of(meta, Set.of()), tmp)) {
            int pieces = meta.pieceCount();
            var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            var start = new java.util.concurrent.CountDownLatch(1);
            var threads = new java.util.ArrayList<Thread>();
            for (int p = 0; p < pieces; p++) {
                final int piece = p;
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        writePiece(storage, meta, content, piece);
                        if (!storage.verifyPiece(piece)) {
                            errors.add(new AssertionError("piece " + piece + " failed verification"));
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }
            start.countDown();
            for (Thread t : threads) {
                t.join();
            }
            assertTrue(errors.isEmpty(), () -> "concurrent verify errors: " + errors);
            assertTrue(storage.completedPieces().isComplete());
            assertArrayEquals(content, Files.readAllBytes(tmp.resolve("cc.bin")));
        }
    }

    @Test
    void filesArePreallocatedToTheirFullLength(@TempDir Path tmp) throws IOException {
        byte[] content = TorrentFixtures.randomBytes(40000, 55); // 3 pieces
        Metainfo meta = TorrentFixtures.singleFile("prealloc.bin", content, PIECE_LENGTH, "http://t/a");
        try (FileStorage storage = new FileStorage(meta, PieceSelection.of(meta, Set.of()), tmp)) {
            // writing only the FIRST piece must already size the whole file
            writePiece(storage, meta, content, 0);
            assertTrue(storage.verifyPiece(0));
            assertEquals(content.length, Files.size(tmp.resolve("prealloc.bin")),
                    "file should be preallocated to its full length, not grown piece by piece");
        }
    }

    @Test
    void parallelRecheckVerifiesEveryPiece(@TempDir Path tmp) throws IOException {
        // Enough pieces that the recheck fans out across workers; result must match a sequential scan exactly.
        byte[] content = TorrentFixtures.randomBytes(50 * PIECE_LENGTH + 123, 56);
        Metainfo meta = TorrentFixtures.singleFile("recheck.bin", content, PIECE_LENGTH, "http://t/a");
        PieceSelection selection = PieceSelection.of(meta, Set.of());

        try (FileStorage first = new FileStorage(meta, selection, tmp)) {
            for (int p = 0; p < meta.pieceCount(); p++) {
                writePiece(first, meta, content, p);
                first.verifyPiece(p);
            }
        }
        try (FileStorage second = new FileStorage(meta, selection, tmp)) {
            assertTrue(second.recheck().isComplete(), "a parallel recheck should find every piece on disk");
        }

        // corrupt one piece on disk: recheck must find exactly that piece missing
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(tmp.resolve("recheck.bin").toFile(), "rw")) {
            raf.seek(7L * PIECE_LENGTH + 10);
            raf.write(0xFF ^ raf.readByte());
        }
        try (FileStorage third = new FileStorage(meta, selection, tmp)) {
            var bits = third.recheck();
            assertFalse(bits.get(7), "the corrupted piece must not be marked complete");
            assertEquals(meta.pieceCount() - 1, bits.cardinality(), "every other piece should still verify");
        }
    }

    @Test
    void partialPieceProgressSurvivesARestart(@TempDir Path tmp) throws IOException {
        int block = net.derrek.bt4j.piece.BlockRequest.BLOCK_SIZE;
        int pieceLength = 4 * block; // 4 blocks per piece
        byte[] content = TorrentFixtures.randomBytes(2 * pieceLength, 88);
        Metainfo meta = TorrentFixtures.singleFile("partial.bin", content, pieceLength, "http://t/a");
        PieceSelection selection = PieceSelection.of(meta, Set.of());

        java.util.Map<Integer, java.util.BitSet> partials;
        try (FileStorage first = new FileStorage(meta, selection, tmp)) {
            // only 3 of piece 0's 4 blocks arrive before the "crash"
            for (int b = 0; b < 3; b++) {
                first.write(0, b * block, Arrays.copyOfRange(content, b * block, (b + 1) * block));
            }
            first.persistPartialPieces();
            partials = first.partialProgress();
            assertEquals(Set.of(0), partials.keySet());
            assertEquals(3, partials.get(0).cardinality());
        }

        // restart: the three persisted blocks must come back, so supplying only the 4th completes the piece
        try (FileStorage second = new FileStorage(meta, selection, tmp,
                new net.derrek.bt4j.piece.Bitfield(meta.pieceCount()), partials)) {
            assertEquals(3, second.restoredPartials().get(0).cardinality());
            second.write(0, 3 * block, Arrays.copyOfRange(content, 3 * block, 4 * block));
            assertTrue(second.verifyPiece(0),
                    "piece should verify from restored blocks plus the one new block");
            assertArrayEquals(Arrays.copyOfRange(content, 0, pieceLength),
                    second.read(0, 0, pieceLength));
        }
    }

    @Test
    void repeatedReadsAreConsistentAcrossTheCache(@TempDir Path tmp) throws IOException {
        // More pieces than the read cache holds, read repeatedly and out of order: every slice must still match.
        byte[] content = TorrentFixtures.randomBytes(10 * PIECE_LENGTH, 99);
        Metainfo meta = TorrentFixtures.singleFile("cache.bin", content, PIECE_LENGTH, "http://t/a");
        try (FileStorage storage = new FileStorage(meta, PieceSelection.of(meta, Set.of()), tmp)) {
            for (int p = 0; p < meta.pieceCount(); p++) {
                writePiece(storage, meta, content, p);
                assertTrue(storage.verifyPiece(p));
            }
            for (int round = 0; round < 3; round++) {
                for (int p = meta.pieceCount() - 1; p >= 0; p--) {
                    int start = p * PIECE_LENGTH;
                    assertArrayEquals(Arrays.copyOfRange(content, start, start + 100),
                            storage.read(p, 0, 100), "piece " + p + " head, round " + round);
                    int mid = PIECE_LENGTH / 2;
                    assertArrayEquals(Arrays.copyOfRange(content, start + mid, start + mid + 64),
                            storage.read(p, mid, 64), "piece " + p + " middle, round " + round);
                }
            }
        }
    }

    @Test
    void reselectionRevokesCompletedBoundaryPieces(@TempDir Path tmp) throws IOException {
        // a.bin [0,30000) and b.bin [30000,60000) with 16384-byte pieces: piece 1 straddles both files.
        byte[] a = TorrentFixtures.randomBytes(30000, 41);
        byte[] b = TorrentFixtures.randomBytes(30000, 42);
        byte[] flat = new byte[60000];
        System.arraycopy(a, 0, flat, 0, 30000);
        System.arraycopy(b, 0, flat, 30000, 30000);
        Metainfo meta = TorrentFixtures.multiFile("multi", List.of(
                new TorrentFixtures.TestFile(List.of("a.bin"), a),
                new TorrentFixtures.TestFile(List.of("b.bin"), b)), PIECE_LENGTH, "http://t/a");

        try (FileStorage storage = new FileStorage(meta, PieceSelection.of(meta, Set.of(0)), tmp)) {
            // download a.bin's pieces (0 fully inside a.bin, 1 the boundary piece)
            for (int p = 0; p <= 1; p++) {
                int start = p * PIECE_LENGTH;
                storage.write(p, 0, Arrays.copyOfRange(flat, start, start + meta.pieceLengthAt(p)));
                assertTrue(storage.verifyPiece(p));
            }
            assertTrue(storage.completedPieces().get(1), "boundary piece completes with a.bin selected");

            // expand the selection to include b.bin: the boundary piece is now missing b.bin's bytes
            var invalidated = storage.updateSelection(PieceSelection.of(meta, Set.of()));
            assertTrue(invalidated.contains(1), "boundary piece must be revoked for re-download");
            assertFalse(invalidated.contains(0), "a piece entirely inside a.bin needs no refetch");
            assertFalse(storage.completedPieces().get(1), "revoked piece is no longer complete");
            assertTrue(storage.completedPieces().get(0), "the fully-owned piece stays complete");
        }
    }

    @Test
    void flushIsSafeAndIdempotent(@TempDir Path tmp) throws IOException {
        byte[] content = TorrentFixtures.randomBytes(20000, 57);
        Metainfo meta = TorrentFixtures.singleFile("f.bin", content, PIECE_LENGTH, "http://t/a");
        try (FileStorage storage = new FileStorage(meta, PieceSelection.of(meta, Set.of()), tmp)) {
            storage.flush(); // no channels open yet
            for (int p = 0; p < meta.pieceCount(); p++) {
                writePiece(storage, meta, content, p);
                storage.verifyPiece(p);
            }
            storage.flush();
            storage.flush();
            assertArrayEquals(content, Files.readAllBytes(tmp.resolve("f.bin")));
        }
    }

    @Test
    void recheckRestoresCompletedPiecesFromDisk(@TempDir Path tmp) throws IOException {
        byte[] content = TorrentFixtures.randomBytes(40000, 31); // 3 pieces
        Metainfo meta = TorrentFixtures.singleFile("r.bin", content, PIECE_LENGTH, "http://t/a");
        PieceSelection selection = PieceSelection.of(meta, Set.of());

        try (FileStorage first = new FileStorage(meta, selection, tmp)) {
            for (int p = 0; p < meta.pieceCount(); p++) {
                writePiece(first, meta, content, p);
                first.verifyPiece(p);
            }
        }
        // simulate a restart: a new storage instance should recover all progress after recheck
        try (FileStorage second = new FileStorage(meta, selection, tmp)) {
            assertEquals(0, second.completedPieces().cardinality());
            assertTrue(second.recheck().isComplete());
        }
    }
}
