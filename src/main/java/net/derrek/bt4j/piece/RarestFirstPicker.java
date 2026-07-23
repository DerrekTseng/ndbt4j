package net.derrek.bt4j.piece;

import java.util.List;
import java.util.Optional;
import net.derrek.bt4j.metainfo.Metainfo;

/**
 * rarest-first 策略（BEP 3 建議）：
 * 統計每個 piece 在已連線 peer 間的持有數，優先請求最稀有者；
 * 第一個 piece 用 random-first 以求快速湊出可上傳的完整 piece。
 */
public final class RarestFirstPicker implements PiecePicker {

    public RarestFirstPicker(Metainfo metainfo, PieceSelection selection, Bitfield alreadyHave) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** peer 的 bitfield/have 變動時更新稀有度統計。 */
    public void onPeerHave(int pieceIndex) {
        throw new UnsupportedOperationException("尚未實作");
    }

    public void onPeerBitfield(Bitfield peerHas) {
        throw new UnsupportedOperationException("尚未實作");
    }

    public void onPeerGone(Bitfield peerHad) {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public List<BlockRequest> pick(Bitfield peerHas, int maxBlocks) {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public Optional<Integer> onBlockReceived(BlockRequest block) {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public void onPieceVerified(int pieceIndex, boolean valid) {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public void onRequestsAbandoned(List<BlockRequest> outstanding) {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public boolean isEndgame() {
        throw new UnsupportedOperationException("尚未實作");
    }
}
