package net.derrek.bt4j.piece;

import java.util.Set;
import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.metainfo.Metainfo;

/**
 * 「勾選的檔案」→「需要下載的 piece 集合」的換算。不可變。
 *
 * piece 是跨檔案連續切割的：勾選檔案的頭尾 piece 可能與未勾選檔案共用，
 * 這些邊界 piece 仍須完整下載（否則無法驗證 hash），
 * 但只有屬於勾選檔案的位元組會寫入磁碟（見 storage.FileStorage）。
 */
public final class PieceSelection {

    private final boolean[] fileWanted;
    private final Bitfield wantedPieces;
    private final long[] wantedBytesPerPiece;
    private final long wantedBytes;

    private PieceSelection(Metainfo metainfo, Set<Integer> selectedFileIndices) {
        int fileCount = metainfo.files().size();
        this.fileWanted = new boolean[fileCount];
        for (int i = 0; i < fileCount; i++) {
            fileWanted[i] = selectedFileIndices.isEmpty() || selectedFileIndices.contains(i);
        }

        this.wantedPieces = new Bitfield(metainfo.pieceCount());
        this.wantedBytesPerPiece = new long[metainfo.pieceCount()];
        long totalWanted = 0;
        long pieceLength = metainfo.pieceLength();
        for (FileEntry file : metainfo.files()) {
            if (!fileWanted[file.index()] || file.length() == 0) {
                continue;
            }
            totalWanted += file.length();
            long fileStart = file.offset();
            long fileEnd = file.offset() + file.length();
            int firstPiece = (int) (fileStart / pieceLength);
            int lastPiece = (int) ((fileEnd - 1) / pieceLength);
            for (int p = firstPiece; p <= lastPiece; p++) {
                wantedPieces.set(p);
                long pieceStart = p * pieceLength;
                long pieceEnd = pieceStart + metainfo.pieceLengthAt(p);
                wantedBytesPerPiece[p] += Math.min(fileEnd, pieceEnd) - Math.max(fileStart, pieceStart);
            }
        }
        this.wantedBytes = totalWanted;
    }

    /**
     * @param selectedFileIndices 勾選的檔案 index（FileEntry.index）；空集合＝全選
     * @throws IllegalArgumentException index 超出範圍
     */
    public static PieceSelection of(Metainfo metainfo, Set<Integer> selectedFileIndices) {
        for (int index : selectedFileIndices) {
            if (index < 0 || index >= metainfo.files().size()) {
                throw new IllegalArgumentException("檔案 index 超出範圍: " + index);
            }
        }
        return new PieceSelection(metainfo, Set.copyOf(selectedFileIndices));
    }

    /** 此 piece 是否在需求集合內（與任一勾選檔案有位元組重疊）。 */
    public boolean isWanted(int pieceIndex) {
        return wantedPieces.get(pieceIndex);
    }

    public boolean isFileWanted(int fileIndex) {
        return fileWanted[fileIndex];
    }

    /** 需求集合的 bitfield 形式（copy），供進度計算與 picker 使用。 */
    public Bitfield wantedPieces() {
        return wantedPieces.copy();
    }

    /** 需求 piece 的總數。 */
    public int wantedPieceCount() {
        return wantedPieces.cardinality();
    }

    /** 需求集合的總位元組數（僅計勾選檔案的位元組，進度分母）。 */
    public long wantedBytes() {
        return wantedBytes;
    }

    /** 此 piece 中屬於勾選檔案的位元組數（進度分子的增量）。 */
    public long wantedBytesInPiece(int pieceIndex) {
        return wantedBytesPerPiece[pieceIndex];
    }
}
