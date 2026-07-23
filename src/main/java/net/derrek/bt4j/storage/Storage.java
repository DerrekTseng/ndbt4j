package net.derrek.bt4j.storage;

import java.io.Closeable;
import java.io.IOException;
import net.derrek.bt4j.piece.Bitfield;

/**
 * piece 資料的持久層抽象。實作負責 piece 位移 ↔ 檔案位移的對映。
 * 讀寫以 piece 內 offset 定址；實作必須是執行緒安全的。
 */
public interface Storage extends Closeable {

    /** 寫入一個 block（piece 驗證前先落地暫存或直接寫目的位置，由實作決定）。 */
    void write(int pieceIndex, int offset, byte[] data) throws IOException;

    /** 讀取 block（回應他人 request、或驗證時讀回整個 piece）。 */
    byte[] read(int pieceIndex, int offset, int length) throws IOException;

    /**
     * piece 全部 block 到齊後驗證 SHA-1。
     * 通過則標記完成並回傳 true；失敗則丟棄該 piece 的資料。
     */
    boolean verifyPiece(int pieceIndex) throws IOException;

    /** 已驗證完成的 piece 集合（resume 與進度計算的依據）。 */
    Bitfield completedPieces();

    /** 啟動時對既有檔案重新驗證（resume 資料遺失或不可信時的 full recheck）。 */
    Bitfield recheck() throws IOException;
}
