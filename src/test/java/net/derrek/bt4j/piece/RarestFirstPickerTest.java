package net.derrek.bt4j.piece;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import org.junit.jupiter.api.Test;

class RarestFirstPickerTest {

    /** 3 pieces (16384 x 2 + 7232 = 40000). */
    private static Metainfo meta() {
        return TorrentFixtures.singleFile("p.bin", TorrentFixtures.randomBytes(40000, 7),
                16384, "http://t.example.com/announce");
    }

    private static Bitfield full(int count) {
        Bitfield bf = new Bitfield(count);
        bf.setAll();
        return bf;
    }

    private static RarestFirstPicker picker(Metainfo meta) {
        return new RarestFirstPicker(meta,
                PieceSelection.of(meta, Set.of()), new Bitfield(meta.pieceCount()));
    }

    @Test
    void sequentialModePicksInFileOrderRegardlessOfRarity() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        // make piece 0 the most common and piece 2 the rarest: rarest-first would start at piece 2
        picker.onPeerBitfield(full(3));
        Bitfield onlyEarly = new Bitfield(3);
        onlyEarly.set(0);
        onlyEarly.set(1);
        picker.onPeerBitfield(onlyEarly);
        picker.onPeerBitfield(onlyEarly);

        RarestFirstPicker rarest = picker(meta);
        rarest.onPeerBitfield(full(3));
        rarest.onPeerBitfield(onlyEarly);
        rarest.onPeerBitfield(onlyEarly);
        assertEquals(2, rarest.pick(full(3), 1).getFirst().pieceIndex(), "rarest-first should start at the rare piece");

        picker.setSequential(true);
        assertEquals(0, picker.pick(full(3), 1).getFirst().pieceIndex(),
                "sequential mode should start at the first piece, ignoring rarity");
    }

    @Test
    void endgameDuplicatesAreWithheldFromDisallowedPeers() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        picker.onPeerBitfield(full(3));

        // dispatch every block so the picker enters endgame (all requested, none received)
        List<BlockRequest> all = picker.pick(full(3), 1000);
        assertFalse(all.isEmpty());
        assertTrue(picker.pick(full(3), 1000, false).isEmpty(),
                "a peer excluded from endgame duplicates should receive nothing once all blocks are dispatched");
        assertFalse(picker.pick(full(3), 1000, true).isEmpty(),
                "an eligible peer should still receive endgame duplicates");
    }

    @Test
    void picksRarestPieceFirst() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);

        // two peers have everything, one peer has only piece 2 -> piece 0/1 availability=2, piece 2=3
        picker.onPeerBitfield(full(3));
        picker.onPeerBitfield(full(3));
        Bitfield onlyPiece2 = new Bitfield(3);
        onlyPiece2.set(2);
        picker.onPeerBitfield(onlyPiece2);

        // request 1 block from the peer that has everything -> should start from the rarest piece 0 or 1 (availability 2 < 3)
        List<BlockRequest> picked = picker.pick(full(3), 1);
        assertEquals(1, picked.size());
        assertTrue(picked.getFirst().pieceIndex() != 2, "should pick the rarer piece first, actual: " + picked);
    }

    @Test
    void blockSizesCoverPieceExactly() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        picker.onPeerBitfield(full(3));

        List<BlockRequest> all = picker.pick(full(3), 100);
        // two full 16384 blocks + a final 7232 piece -> 1 block per piece (pieceLength=16384=1 block)
        long total = all.stream().mapToLong(BlockRequest::length).sum();
        assertEquals(40000, total);
        // the same block is not dispatched twice
        assertEquals(all.size(), all.stream().distinct().count());
        // request again -> empty (all dispatched, and not re-issued unless in endgame)
        assertTrue(picker.pick(full(3), 100).isEmpty() || picker.isEndgame());
    }

    @Test
    void completeFlowWithVerification() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        picker.onPeerBitfield(full(3));

        List<BlockRequest> all = picker.pick(full(3), 100);
        for (BlockRequest block : all) {
            var complete = picker.onBlockReceived(block);
            if (complete.isPresent()) {
                picker.onPieceVerified(complete.get(), true);
            }
        }
        assertTrue(picker.isComplete());
        assertTrue(picker.pick(full(3), 100).isEmpty());
    }

    @Test
    void failedVerificationRequeuesPiece() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        picker.onPeerBitfield(full(3));

        List<BlockRequest> all = picker.pick(full(3), 100);
        BlockRequest first = all.getFirst();
        var complete = picker.onBlockReceived(first);
        // piece 0 has only one block (16384) -> assembled immediately
        assertEquals(first.pieceIndex(), complete.orElseThrow());
        picker.onPieceVerified(first.pieceIndex(), false); // verification failed

        assertFalse(picker.isComplete());
        // the blocks of that piece should be pickable again
        List<BlockRequest> again = picker.pick(full(3), 100);
        assertTrue(again.stream().anyMatch(b -> b.pieceIndex() == first.pieceIndex()),
                "a piece that failed verification should be re-queued: " + again);
    }

    @Test
    void abandonedRequestsAreRepickable() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        picker.onPeerBitfield(full(3));

        List<BlockRequest> all = picker.pick(full(3), 100);
        assertTrue(picker.pick(full(3), 100).isEmpty() || picker.isEndgame());

        picker.onRequestsAbandoned(all); // peer disconnected
        List<BlockRequest> again = picker.pick(full(3), 100);
        assertEquals(all.size(), again.size());
    }

    @Test
    void endgameReissuesOutstandingBlocks() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        picker.onPeerBitfield(full(3));

        List<BlockRequest> all = picker.pick(full(3), 100); // all dispatched to peer A
        assertTrue(picker.isEndgame());
        // in endgame peer B should get the duplicate not-yet-delivered blocks
        List<BlockRequest> dup = picker.pick(full(3), 100);
        assertEquals(all.size(), dup.size());
    }

    @Test
    void pickFromPieceReturnsBlocksForThatPieceOnly() {
        Metainfo meta = meta(); // 3 pieces
        RarestFirstPicker picker = picker(meta);

        List<BlockRequest> blocks = picker.pickFromPiece(1, 100);
        assertFalse(blocks.isEmpty());
        assertTrue(blocks.stream().allMatch(b -> b.pieceIndex() == 1));
        // calling again does not return the same block (already marked requested)
        assertTrue(picker.pickFromPiece(1, 100).isEmpty());
    }

    @Test
    void pickFromPieceRejectsCompletedOrOutOfRange() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        // first complete piece 0
        picker.onPeerBitfield(full(3));
        for (BlockRequest b : picker.pickFromPiece(0, 100)) {
            picker.onBlockReceived(b).ifPresent(p -> picker.onPieceVerified(p, true));
        }
        assertTrue(picker.pickFromPiece(0, 100).isEmpty(), "a completed piece is no longer picked");
        assertTrue(picker.pickFromPiece(99, 100).isEmpty(), "out of range returns empty");
    }

    @Test
    void availabilityDropsWhenPeerLeaves() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        Bitfield peerHad = full(3);
        picker.onPeerBitfield(peerHad);
        picker.onPeerGone(peerHad);
        // still pickable after availability drops to zero (availability affects only ordering, not pickability)
        assertFalse(picker.pick(full(3), 1).isEmpty());
    }
}
