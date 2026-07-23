package net.derrek.bt4j.storage;

import java.io.IOException;
import java.nio.file.Path;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.piece.Bitfield;
import net.derrek.bt4j.piece.PieceSelection;

/**
 * 以 FileChannel 實作的檔案儲存。
 *
 * 選擇性下載的行為：
 * - 未勾選的檔案不建立、不預配空間；
 * - 邊界 piece 中屬於未勾選檔案的位元組區段，驗證後即丟棄（不落地）；
 *   因此邊界 piece 的驗證需在記憶體中對整個 piece 進行。
 */
public final class FileStorage implements Storage {

    /**
     * @param saveTo    下載根目錄（多檔 torrent 會在其下建立 name 目錄）
     * @param selection 勾選範圍，決定哪些檔案落地
     */
    public FileStorage(Metainfo metainfo, PieceSelection selection, Path saveTo) {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public void write(int pieceIndex, int offset, byte[] data) throws IOException {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public byte[] read(int pieceIndex, int offset, int length) throws IOException {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public boolean verifyPiece(int pieceIndex) throws IOException {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public Bitfield completedPieces() {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public Bitfield recheck() throws IOException {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException("尚未實作");
    }
}
