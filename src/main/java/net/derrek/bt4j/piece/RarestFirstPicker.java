package net.derrek.bt4j.piece;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.derrek.bt4j.metainfo.Metainfo;

/**
 * rarest-first 策略（BEP 3 建議）：
 * 統計每個 piece 在已連線 peer 間的持有數，優先請求最稀有者。
 * 同時進行中的 piece 數有上限（限制 FileStorage 的記憶體用量）。
 * 所有方法皆同步（多條 peer 讀迴圈併發呼叫）。
 */
public final class RarestFirstPicker implements PiecePicker {

    /** 同時進行中的 piece 上限（每個 piece 在 storage 佔一個 pieceLength 的緩衝）。 */
    static final int MAX_ACTIVE_PIECES = 32;

    private final Metainfo metainfo;
    private final PieceSelection selection;
    private final int[] availability;
    private final BitSet verified;
    private final Map<Integer, PieceProgress> active = new HashMap<>();

    /** 進行中 piece 的 block 狀態。 */
    private static final class PieceProgress {
        final int blockCount;
        final BitSet requested;
        final BitSet received;

        PieceProgress(int blockCount) {
            this.blockCount = blockCount;
            this.requested = new BitSet(blockCount);
            this.received = new BitSet(blockCount);
        }
    }

    /**
     * @param alreadyHave 已完成（驗證過）的 piece，例如 resume 回復的進度
     */
    public RarestFirstPicker(Metainfo metainfo, PieceSelection selection, Bitfield alreadyHave) {
        this.metainfo = metainfo;
        this.selection = selection;
        this.availability = new int[metainfo.pieceCount()];
        this.verified = new BitSet(metainfo.pieceCount());
        for (int p = 0; p < metainfo.pieceCount(); p++) {
            if (alreadyHave.get(p)) {
                verified.set(p);
            }
        }
    }

    // ---- 稀有度統計 ----

    public synchronized void onPeerBitfield(Bitfield peerHas) {
        for (int p = 0; p < availability.length; p++) {
            if (peerHas.get(p)) {
                availability[p]++;
            }
        }
    }

    public synchronized void onPeerHave(int pieceIndex) {
        availability[pieceIndex]++;
    }

    public synchronized void onPeerGone(Bitfield peerHad) {
        for (int p = 0; p < availability.length; p++) {
            if (peerHad.get(p)) {
                availability[p]--;
            }
        }
    }

    // ---- 排程 ----

    @Override
    public synchronized List<BlockRequest> pick(Bitfield peerHas, int maxBlocks) {
        List<BlockRequest> out = new ArrayList<>();

        // 1) 先補完進行中的 piece（減少同時展開的 piece 數）
        for (Map.Entry<Integer, PieceProgress> entry : active.entrySet()) {
            fillFrom(entry.getKey(), entry.getValue(), peerHas, out, maxBlocks);
            if (out.size() >= maxBlocks) {
                return out;
            }
        }

        // 2) 展開新 piece：稀有度最低者優先
        while (out.size() < maxBlocks && active.size() < MAX_ACTIVE_PIECES) {
            int best = -1;
            for (int p = 0; p < availability.length; p++) {
                if (selection.isWanted(p) && !verified.get(p) && !active.containsKey(p) && peerHas.get(p)
                        && (best < 0 || availability[p] < availability[best])) {
                    best = p;
                }
            }
            if (best < 0) {
                break;
            }
            PieceProgress progress = new PieceProgress(blockCount(best));
            active.put(best, progress);
            fillFrom(best, progress, peerHas, out, maxBlocks);
        }

        // 3) endgame：所有剩餘 block 都已派發時，重複請求未到貨的 block（跨 peer 加速尾段）
        if (out.isEmpty() && isEndgameLocked()) {
            for (Map.Entry<Integer, PieceProgress> entry : active.entrySet()) {
                int p = entry.getKey();
                PieceProgress progress = entry.getValue();
                if (!peerHas.get(p)) {
                    continue;
                }
                for (int b = 0; b < progress.blockCount && out.size() < maxBlocks; b++) {
                    if (progress.requested.get(b) && !progress.received.get(b)) {
                        out.add(blockRequest(p, b));
                    }
                }
            }
        }
        return out;
    }

    /**
     * 只針對指定 piece 挑 block（Fast Extension 的 Allowed Fast：即使被 choke 也可請求）。
     * piece 已完成/非需求時回傳空。
     */
    public synchronized List<BlockRequest> pickFromPiece(int pieceIndex, int maxBlocks) {
        List<BlockRequest> out = new ArrayList<>();
        if (pieceIndex < 0 || pieceIndex >= availability.length
                || !selection.isWanted(pieceIndex) || verified.get(pieceIndex)) {
            return out;
        }
        PieceProgress progress = active.computeIfAbsent(pieceIndex, p -> new PieceProgress(blockCount(p)));
        for (int b = 0; b < progress.blockCount && out.size() < maxBlocks; b++) {
            if (!progress.requested.get(b)) {
                progress.requested.set(b);
                out.add(blockRequest(pieceIndex, b));
            }
        }
        return out;
    }

    private void fillFrom(int pieceIndex, PieceProgress progress, Bitfield peerHas,
                          List<BlockRequest> out, int maxBlocks) {
        if (!peerHas.get(pieceIndex)) {
            return;
        }
        for (int b = 0; b < progress.blockCount && out.size() < maxBlocks; b++) {
            if (!progress.requested.get(b)) {
                progress.requested.set(b);
                out.add(blockRequest(pieceIndex, b));
            }
        }
    }

    private BlockRequest blockRequest(int pieceIndex, int blockIndex) {
        int pieceLength = metainfo.pieceLengthAt(pieceIndex);
        int begin = blockIndex * BlockRequest.BLOCK_SIZE;
        return new BlockRequest(pieceIndex, begin, Math.min(BlockRequest.BLOCK_SIZE, pieceLength - begin));
    }

    private int blockCount(int pieceIndex) {
        return (metainfo.pieceLengthAt(pieceIndex) + BlockRequest.BLOCK_SIZE - 1) / BlockRequest.BLOCK_SIZE;
    }

    @Override
    public synchronized Optional<Integer> onBlockReceived(BlockRequest block) {
        PieceProgress progress = active.get(block.pieceIndex());
        if (progress == null) {
            return Optional.empty(); // 已驗證或已重設（endgame 重複到貨）
        }
        int blockIndex = block.begin() / BlockRequest.BLOCK_SIZE;
        if (progress.received.get(blockIndex)) {
            return Optional.empty();
        }
        progress.requested.set(blockIndex);
        progress.received.set(blockIndex);
        return progress.received.cardinality() == progress.blockCount
                ? Optional.of(block.pieceIndex())
                : Optional.empty();
    }

    @Override
    public synchronized void onPieceVerified(int pieceIndex, boolean valid) {
        active.remove(pieceIndex);
        if (valid) {
            verified.set(pieceIndex);
        }
        // 驗證失敗：回到未開始狀態，之後重新排入
    }

    @Override
    public synchronized void onRequestsAbandoned(List<BlockRequest> outstanding) {
        for (BlockRequest block : outstanding) {
            PieceProgress progress = active.get(block.pieceIndex());
            if (progress != null) {
                int blockIndex = block.begin() / BlockRequest.BLOCK_SIZE;
                if (!progress.received.get(blockIndex)) {
                    progress.requested.clear(blockIndex);
                }
            }
        }
    }

    @Override
    public synchronized boolean isEndgame() {
        return isEndgameLocked();
    }

    private boolean isEndgameLocked() {
        for (int p = 0; p < availability.length; p++) {
            if (selection.isWanted(p) && !verified.get(p) && !active.containsKey(p)) {
                return false; // 還有未展開的 piece
            }
        }
        for (PieceProgress progress : active.values()) {
            if (progress.requested.cardinality() < progress.blockCount) {
                return false; // 還有未派發的 block
            }
        }
        return true;
    }

    /** 所有需求 piece 都已驗證完成。 */
    public synchronized boolean isComplete() {
        for (int p = 0; p < availability.length; p++) {
            if (selection.isWanted(p) && !verified.get(p)) {
                return false;
            }
        }
        return true;
    }
}
