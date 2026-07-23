package net.derrek.bt4j.piece;

import java.util.List;
import java.util.Optional;

/**
 * block 排程策略：決定接下來向哪個 peer 要哪個 block。
 * 預設實作為 rarest-first（見 {@link RarestFirstPicker}）。
 * 實作必須是執行緒安全的（多條 peer 讀迴圈同時呼叫）。
 */
public interface PiecePicker {

    /**
     * 為某個 peer 挑選接下來要送出的 request。
     *
     * @param peerHas    該 peer 持有的 piece
     * @param maxBlocks  最多回傳幾個（依 pipeline 深度，慣例每連線 5~10 個 outstanding）
     * @return 可請求的 block；無事可做（該 peer 沒有我們要的 piece）時為空
     */
    List<BlockRequest> pick(Bitfield peerHas, int maxBlocks);

    /** 收到 block。回傳 Optional 有值時表示該 piece 已組完，應交給 storage 驗證。 */
    Optional<Integer> onBlockReceived(BlockRequest block);

    /** piece 驗證結果回報：失敗時該 piece 全部 block 重新排入。 */
    void onPieceVerified(int pieceIndex, boolean valid);

    /** peer 斷線：其未完成的 request 重新排入。 */
    void onRequestsAbandoned(List<BlockRequest> outstanding);

    /** 剩餘需求 block 數低於門檻時進入 endgame：同一 block 可重複派發給多個 peer。 */
    boolean isEndgame();
}
