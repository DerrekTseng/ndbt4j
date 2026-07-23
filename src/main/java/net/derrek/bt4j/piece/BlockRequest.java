package net.derrek.bt4j.piece;

/**
 * 一個 block 請求（piece 的切片）。對應 wire 的 request/piece/cancel 三元組。
 * length 慣例 16 KiB（BLOCK_SIZE），piece 尾端的 block 可能較短。
 */
public record BlockRequest(int pieceIndex, int begin, int length) {

    public static final int BLOCK_SIZE = 16 * 1024;
}
