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

    /** 3 pieces（16384 × 2 + 7232 = 40000）。 */
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
    void picksRarestPieceFirst() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);

        // 兩個 peer 有全部，一個 peer 只有 piece 2 → piece 0/1 availability=2、piece 2=3
        picker.onPeerBitfield(full(3));
        picker.onPeerBitfield(full(3));
        Bitfield onlyPiece2 = new Bitfield(3);
        onlyPiece2.set(2);
        picker.onPeerBitfield(onlyPiece2);

        // 向持有全部的 peer 要 1 個 block → 應從最稀有的 piece 0 或 1 開始（availability 2 < 3）
        List<BlockRequest> picked = picker.pick(full(3), 1);
        assertEquals(1, picked.size());
        assertTrue(picked.getFirst().pieceIndex() != 2, "應優先挑稀有 piece，實際: " + picked);
    }

    @Test
    void blockSizesCoverPieceExactly() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        picker.onPeerBitfield(full(3));

        List<BlockRequest> all = picker.pick(full(3), 100);
        // 16384×2 個整 block + 最後 piece 7232 → 每 piece 1 block（pieceLength=16384=1 block）
        long total = all.stream().mapToLong(BlockRequest::length).sum();
        assertEquals(40000, total);
        // 同一 block 不會重複派發
        assertEquals(all.size(), all.stream().distinct().count());
        // 再要一次 → 空（全部已派發，且未進 endgame 條件下不重複）
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
        // piece 0 只有一個 block（16384）→ 立刻組完
        assertEquals(first.pieceIndex(), complete.orElseThrow());
        picker.onPieceVerified(first.pieceIndex(), false); // 驗證失敗

        assertFalse(picker.isComplete());
        // 該 piece 的 block 應可重新取得
        List<BlockRequest> again = picker.pick(full(3), 100);
        assertTrue(again.stream().anyMatch(b -> b.pieceIndex() == first.pieceIndex()),
                "驗證失敗的 piece 應重新排入: " + again);
    }

    @Test
    void abandonedRequestsAreRepickable() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        picker.onPeerBitfield(full(3));

        List<BlockRequest> all = picker.pick(full(3), 100);
        assertTrue(picker.pick(full(3), 100).isEmpty() || picker.isEndgame());

        picker.onRequestsAbandoned(all); // peer 斷線
        List<BlockRequest> again = picker.pick(full(3), 100);
        assertEquals(all.size(), again.size());
    }

    @Test
    void endgameReissuesOutstandingBlocks() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        picker.onPeerBitfield(full(3));

        List<BlockRequest> all = picker.pick(full(3), 100); // 全部派給 peer A
        assertTrue(picker.isEndgame());
        // peer B 在 endgame 應拿到重複的未到貨 block
        List<BlockRequest> dup = picker.pick(full(3), 100);
        assertEquals(all.size(), dup.size());
    }

    @Test
    void availabilityDropsWhenPeerLeaves() {
        Metainfo meta = meta();
        RarestFirstPicker picker = picker(meta);
        Bitfield peerHad = full(3);
        picker.onPeerBitfield(peerHad);
        picker.onPeerGone(peerHad);
        // availability 歸零後仍可挑（availability 只影響順序不影響可挑性）
        assertFalse(picker.pick(full(3), 1).isEmpty());
    }
}
