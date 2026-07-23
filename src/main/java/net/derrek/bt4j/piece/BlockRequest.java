package net.derrek.bt4j.piece;

/**
 * A single block request (a slice of a piece). Corresponds to the wire request/piece/cancel triple.
 * Length is conventionally 16 KiB (BLOCK_SIZE); the block at the end of a piece may be shorter.
 */
public record BlockRequest(int pieceIndex, int begin, int length) {

    public static final int BLOCK_SIZE = 16 * 1024;
}
