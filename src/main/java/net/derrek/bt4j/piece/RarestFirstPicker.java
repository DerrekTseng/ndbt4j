package net.derrek.bt4j.piece;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.derrek.bt4j.metainfo.Metainfo;

/**
 * Rarest-first strategy (recommended by BEP 3):
 * counts how many connected peers hold each piece and requests the rarest ones first.
 * The number of pieces in progress at once is capped (to bound FileStorage's memory use).
 * All methods are synchronized (called concurrently by multiple peer read loops).
 */
public final class RarestFirstPicker implements PiecePicker {

    /** Cap on pieces in progress at once (each piece occupies a pieceLength buffer in storage). */
    static final int MAX_ACTIVE_PIECES = 32;

    private final Metainfo metainfo;
    private final PieceSelection selection;
    private final int[] availability;
    private final BitSet verified;
    private final Map<Integer, PieceProgress> active = new HashMap<>();

    /**
     * Lock-free snapshot of the endgame state, refreshed inside {@link #pick} (where the authoritative check
     * already runs) so hot callers can gate per-block work without taking the picker lock or scanning pieces.
     * May lag the true state slightly; a stale read only adds or skips a harmless duplicate-cancel scan.
     */
    private volatile boolean endgame;

    /** Block state of a piece in progress. */
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
     * @param alreadyHave pieces already completed (verified), e.g. progress restored on resume
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

    // ---- rarity statistics ----

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

    // ---- scheduling ----

    @Override
    public synchronized List<BlockRequest> pick(Bitfield peerHas, int maxBlocks) {
        List<BlockRequest> out = new ArrayList<>();

        // 1) first fill in pieces already in progress (reduces the number of pieces opened at once)
        for (Map.Entry<Integer, PieceProgress> entry : active.entrySet()) {
            fillFrom(entry.getKey(), entry.getValue(), peerHas, out, maxBlocks);
            if (out.size() >= maxBlocks) {
                endgame = false; // returned fresh, never-before-requested blocks
                return out;
            }
        }

        // 2) open new pieces: the rarest first
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
            endgame = false; // a fresh piece was opened: there are still undispatched blocks
            fillFrom(best, progress, peerHas, out, maxBlocks);
        }

        // Fresh, never-before-requested blocks were dispatched: by definition not endgame.
        if (!out.isEmpty()) {
            endgame = false;
            return out;
        }

        // 3) endgame: once all remaining blocks have been dispatched, re-request blocks not yet delivered (across peers to speed up the tail)
        endgame = isEndgameLocked();
        if (endgame) {
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
     * Pick blocks only from the specified piece (Fast Extension's Allowed Fast: may be requested even while choked).
     * Returns empty when the piece is already complete or not wanted.
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
            return Optional.empty(); // already verified or reset (duplicate delivery in endgame)
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
        } else {
            endgame = false; // the piece returns to the not-started state, so blocks must be re-dispatched
        }
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

    /**
     * Lock-free, best-effort endgame status refreshed on the last {@link #pick}. Cheap enough to gate per-block
     * work (e.g. duplicate-request cancellation); may briefly lag {@link #isEndgame()} but never misleads harmfully.
     */
    public boolean isEndgameFast() {
        return endgame;
    }

    private boolean isEndgameLocked() {
        for (int p = 0; p < availability.length; p++) {
            if (selection.isWanted(p) && !verified.get(p) && !active.containsKey(p)) {
                return false; // there are still unopened pieces
            }
        }
        for (PieceProgress progress : active.values()) {
            if (progress.requested.cardinality() < progress.blockCount) {
                return false; // there are still undispatched blocks
            }
        }
        return true;
    }

    /** All wanted pieces have been verified and completed. */
    public synchronized boolean isComplete() {
        for (int p = 0; p < availability.length; p++) {
            if (selection.isWanted(p) && !verified.get(p)) {
                return false;
            }
        }
        return true;
    }
}
