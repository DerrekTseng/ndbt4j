package net.derrek.bt4j.piece;

import java.util.Set;
import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.metainfo.Metainfo;

/**
 * Conversion from "selected files" to "the set of pieces that must be downloaded". Immutable.
 *
 * Pieces are cut contiguously across files: the first and last piece of a selected file may be
 * shared with unselected files. These boundary pieces still must be downloaded in full (otherwise
 * the hash cannot be verified), but only the bytes belonging to selected files are written to disk
 * (see storage.FileStorage).
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
     * @param selectedFileIndices indices of selected files (FileEntry.index); empty set = select all
     * @throws IllegalArgumentException if an index is out of range
     */
    public static PieceSelection of(Metainfo metainfo, Set<Integer> selectedFileIndices) {
        for (int index : selectedFileIndices) {
            if (index < 0 || index >= metainfo.files().size()) {
                throw new IllegalArgumentException("file index out of range: " + index);
            }
        }
        return new PieceSelection(metainfo, Set.copyOf(selectedFileIndices));
    }

    /** Whether this piece is in the wanted set (overlaps in bytes with any selected file). */
    public boolean isWanted(int pieceIndex) {
        return wantedPieces.get(pieceIndex);
    }

    public boolean isFileWanted(int fileIndex) {
        return fileWanted[fileIndex];
    }

    /** The wanted set as a bitfield (a copy), for progress calculation and the picker. */
    public Bitfield wantedPieces() {
        return wantedPieces.copy();
    }

    /** Total number of wanted pieces. */
    public int wantedPieceCount() {
        return wantedPieces.cardinality();
    }

    /** Total byte count of the wanted set (counting only selected files' bytes, the progress denominator). */
    public long wantedBytes() {
        return wantedBytes;
    }

    /** Number of bytes in this piece that belong to selected files (the increment to the progress numerator). */
    public long wantedBytesInPiece(int pieceIndex) {
        return wantedBytesPerPiece[pieceIndex];
    }
}
