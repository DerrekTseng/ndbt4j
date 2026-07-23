package net.derrek.bt4j.storage;

import java.io.Closeable;
import java.io.IOException;
import net.derrek.bt4j.piece.Bitfield;

/**
 * Persistence-layer abstraction for piece data. Implementations handle the mapping between piece offsets and file offsets.
 * Reads and writes are addressed by offset within a piece; implementations must be thread-safe.
 */
public interface Storage extends Closeable {

    /** Write a block (whether the piece is buffered before verification or written straight to its destination is up to the implementation). */
    void write(int pieceIndex, int offset, byte[] data) throws IOException;

    /** Read a block (to answer others' requests, or to read back a whole piece during verification). */
    byte[] read(int pieceIndex, int offset, int length) throws IOException;

    /**
     * Verify SHA-1 once all blocks of a piece have arrived.
     * On success, mark it complete and return true; on failure, discard the piece's data.
     */
    boolean verifyPiece(int pieceIndex) throws IOException;

    /** The set of verified, completed pieces (the basis for resume and progress calculation). */
    Bitfield completedPieces();

    /** Re-verify existing files at startup (a full recheck when resume data is missing or untrusted). */
    Bitfield recheck() throws IOException;
}
