package net.derrek.bt4j.piece;

import java.util.List;
import java.util.Optional;

/**
 * Block scheduling strategy: decides which block to request from which peer next.
 * The default implementation is rarest-first (see {@link RarestFirstPicker}).
 * Implementations must be thread-safe (called concurrently by multiple peer read loops).
 */
public interface PiecePicker {

    /**
     * Pick the next requests to send to a given peer.
     *
     * @param peerHas    the pieces this peer holds
     * @param maxBlocks  the maximum number to return (based on pipeline depth, conventionally 5~10 outstanding per connection)
     * @return requestable blocks; empty when there is nothing to do (the peer has none of the pieces we want)
     */
    List<BlockRequest> pick(Bitfield peerHas, int maxBlocks);

    /** A block was received. A present Optional means the piece is fully assembled and should be handed to storage for verification. */
    Optional<Integer> onBlockReceived(BlockRequest block);

    /** Report of a piece verification result: on failure all blocks of the piece are re-queued. */
    void onPieceVerified(int pieceIndex, boolean valid);

    /** Peer disconnected: its outstanding requests are re-queued. */
    void onRequestsAbandoned(List<BlockRequest> outstanding);

    /** Enters endgame when the number of remaining needed blocks falls below the threshold: the same block may be dispatched to multiple peers. */
    boolean isEndgame();
}
