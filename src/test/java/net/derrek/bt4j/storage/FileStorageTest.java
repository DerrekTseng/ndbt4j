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
                assertTrue(storage.verifyPiece(p), "piece " + p + " 驗證應通過");
            }
            assertTrue(storage.completedPieces().isComplete());
            assertArrayEquals(content, Files.readAllBytes(tmp.resolve("s.bin")));

            // read 跨 piece 邊界
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

            // 丟棄後重寫正確資料可通過
            writePiece(storage, meta, content, 0);
            assertTrue(storage.verifyPiece(0));
        }
    }

    @Test
    void blocksAccumulateIntoPiece(@TempDir Path tmp) throws IOException {
        byte[] content = TorrentFixtures.randomBytes(PIECE_LENGTH, 13); // 恰 1 piece
        Metainfo meta = TorrentFixtures.singleFile("b.bin", content, PIECE_LENGTH, "http://t/a");
        try (FileStorage storage = new FileStorage(meta, PieceSelection.of(meta, Set.of()), tmp)) {

            int half = PIECE_LENGTH / 2;
            storage.write(0, half, Arrays.copyOfRange(content, half, PIECE_LENGTH)); // 亂序寫
            storage.write(0, 0, Arrays.copyOfRange(content, 0, half));
            assertTrue(storage.verifyPiece(0));
            assertArrayEquals(content, Files.readAllBytes(tmp.resolve("b.bin")));
        }
    }

    @Test
    void unselectedFilesAreNotCreatedAndBoundaryBytesDiscarded(@TempDir Path tmp) throws IOException {
        // a[0,20000) b[20000,40000) c[40000,50000)，piece 1/2 是 b 的邊界 piece
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
            // 下載 b 需要的 piece 1、2（完整下載以驗證）
            writePiece(storage, meta, all, 1);
            writePiece(storage, meta, all, 2);
            assertTrue(storage.verifyPiece(1));
            assertTrue(storage.verifyPiece(2));

            // 只有 b.bin 落地；a、c 不建檔
            assertTrue(Files.exists(tmp.resolve("multi").resolve("sub").resolve("b.bin")));
            assertFalse(Files.exists(tmp.resolve("multi").resolve("a.bin")));
            assertFalse(Files.exists(tmp.resolve("multi").resolve("c.bin")));

            // b.bin 內容正確（邊界 piece 的 a/c 區段被丟棄，b 區段寫對位置）
            byte[] written = Files.readAllBytes(tmp.resolve("multi").resolve("sub").resolve("b.bin"));
            assertArrayEquals(b, written);
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
        // 模擬重啟：新的 storage 例項 recheck 後應回復全部進度
        try (FileStorage second = new FileStorage(meta, selection, tmp)) {
            assertEquals(0, second.completedPieces().cardinality());
            assertTrue(second.recheck().isComplete());
        }
    }
}
