package net.derrek.bt4j.piece;

import java.util.Set;
import net.derrek.bt4j.metainfo.Metainfo;

/**
 * 「勾選的檔案」→「需要下載的 piece 集合」的換算。
 *
 * piece 是跨檔案連續切割的：勾選檔案的頭尾 piece 可能與未勾選檔案共用，
 * 這些邊界 piece 仍須完整下載（否則無法驗證 hash），
 * 但只有屬於勾選檔案的位元組會寫入磁碟（見 storage.FileStorage）。
 */
public final class PieceSelection {

    /**
     * @param selectedFileIndices 勾選的檔案 index（FileEntry.index）；空集合＝全選
     */
    public static PieceSelection of(Metainfo metainfo, Set<Integer> selectedFileIndices) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 此 piece 是否在需求集合內（與任一勾選檔案有位元組重疊）。 */
    public boolean isWanted(int pieceIndex) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 需求集合的 bitfield 形式，供進度計算與 picker 使用。 */
    public Bitfield wantedPieces() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 需求集合的總位元組數（僅計勾選檔案的位元組，進度分母）。 */
    public long wantedBytes() {
        throw new UnsupportedOperationException("尚未實作");
    }
}
