package net.derrek.bt4j.metainfo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;
import org.junit.jupiter.api.Test;

class MetainfoTest {

    private static final byte[] PIECE_HASH_1 = "AAAAAAAAAAAAAAAAAAAA".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PIECE_HASH_2 = "BBBBBBBBBBBBBBBBBBBB".getBytes(StandardCharsets.US_ASCII);

    // ---- Test data construction helpers ----

    private static BValue.BDictionary dict(Object... keyValues) {
        SequencedMap<BValue.BString, BValue> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(BValue.BString.of((String) keyValues[i]), (BValue) keyValues[i + 1]);
        }
        return new BValue.BDictionary(map);
    }

    private static BValue.BInteger integer(long v) {
        return new BValue.BInteger(v);
    }

    private static BValue.BString string(String s) {
        return BValue.BString.of(s);
    }

    private static byte[] concat(byte[]... arrays) {
        var out = new java.io.ByteArrayOutputStream();
        for (byte[] a : arrays) {
            out.writeBytes(a);
        }
        return out.toByteArray();
    }

    /** info for a single-file torrent: length=40000, pieceLength=16384 → 3 pieces. */
    private static BValue.BDictionary singleFileInfo() {
        return dict(
                "length", integer(40000),
                "name", string("test.bin"),
                "piece length", integer(16384),
                "pieces", new BValue.BString(concat(PIECE_HASH_1, PIECE_HASH_2, PIECE_HASH_1)));
    }

    private static byte[] torrentBytes(BValue.BDictionary info) {
        return Bencode.encode(dict(
                "announce", string("http://tracker.example.com/announce"),
                "info", info));
    }

    // ---- Single file ----

    @Test
    void parseSingleFileTorrent() {
        Metainfo meta = Metainfo.parse(torrentBytes(singleFileInfo()));

        assertEquals("test.bin", meta.name());
        assertEquals(40000, meta.totalLength());
        assertEquals(16384, meta.pieceLength());
        assertEquals(3, meta.pieceCount());
        assertEquals(16384, meta.pieceLengthAt(0));
        assertEquals(40000 - 2 * 16384, meta.pieceLengthAt(2)); // the last piece is shorter
        assertArrayEquals(PIECE_HASH_2, meta.pieceHash(1));
        assertFalse(meta.isPrivate());

        assertEquals(1, meta.files().size());
        FileEntry file = meta.files().getFirst();
        assertEquals(new FileEntry(0, List.of("test.bin"), 40000, 0), file);
        assertEquals("test.bin", file.displayPath());

        assertEquals(List.of(List.of(URI.create("http://tracker.example.com/announce"))), meta.announceList());
    }

    @Test
    void infoHashIsSha1OfInfoDictBytes() {
        Metainfo meta = Metainfo.parse(torrentBytes(singleFileInfo()));
        assertEquals(InfoHash.ofInfoDict(Bencode.encode(singleFileInfo())), meta.infoHash());
        assertArrayEquals(Bencode.encode(singleFileInfo()), meta.infoDictBytes());
    }

    @Test
    void infoHashUsesRawBytesEvenForNonCanonicalInput() {
        // hand-build an info dict with unsorted keys (name before length) -- lenient parsing,
        // and the info-hash must be computed over the "raw bytes", differing from the re-encoded canonical form
        byte[] rawInfo = ("d4:name1:a6:lengthi1e12:piece lengthi16384e6:pieces20:AAAAAAAAAAAAAAAAAAAAe")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] torrent = concat("d4:info".getBytes(StandardCharsets.ISO_8859_1), rawInfo,
                "e".getBytes(StandardCharsets.ISO_8859_1));

        Metainfo meta = Metainfo.parse(torrent);
        assertEquals(InfoHash.ofInfoDict(rawInfo), meta.infoHash());
        assertArrayEquals(rawInfo, meta.infoDictBytes());
    }

    // ---- Multi-file ----

    @Test
    void parseMultiFileTorrent() {
        BValue.BDictionary info = dict(
                "files", new BValue.BList(List.of(
                        dict("length", integer(10), "path", new BValue.BList(List.of(string("a.txt")))),
                        dict("length", integer(20), "path", new BValue.BList(List.of(string("sub"), string("b.txt")))))),
                "name", string("mytorrent"),
                "piece length", integer(16384),
                "pieces", new BValue.BString(PIECE_HASH_1),
                "private", integer(1));

        Metainfo meta = Metainfo.parse(torrentBytes(info));

        assertEquals("mytorrent", meta.name());
        assertEquals(30, meta.totalLength());
        assertEquals(1, meta.pieceCount());
        assertTrue(meta.isPrivate());
        assertEquals(List.of(
                new FileEntry(0, List.of("mytorrent", "a.txt"), 10, 0),
                new FileEntry(1, List.of("mytorrent", "sub", "b.txt"), 20, 10)), meta.files());
        assertEquals("mytorrent/sub/b.txt", meta.files().get(1).displayPath());
    }

    @Test
    void announceListTiersTakePrecedenceOverAnnounce() {
        byte[] torrent = Bencode.encode(dict(
                "announce", string("http://old.example.com/announce"),
                "announce-list", new BValue.BList(List.of(
                        new BValue.BList(List.of(string("udp://t1.example.com:6969"), string("udp://t2.example.com:6969"))),
                        new BValue.BList(List.of(string("https://backup.example.com/announce"))))),
                "info", singleFileInfo()));

        Metainfo meta = Metainfo.parse(torrent);
        assertEquals(List.of(
                List.of(URI.create("udp://t1.example.com:6969"), URI.create("udp://t2.example.com:6969")),
                List.of(URI.create("https://backup.example.com/announce"))), meta.announceList());
    }

    // ---- Magnet scenario and export ----

    @Test
    void fromInfoDictMatchesParsedTorrent() {
        byte[] infoBytes = Bencode.encode(singleFileInfo());
        List<URI> trackers = List.of(URI.create("udp://tracker.example.com:6969"));

        Metainfo meta = Metainfo.fromInfoDict(infoBytes, trackers);
        assertEquals(Metainfo.parse(torrentBytes(singleFileInfo())).infoHash(), meta.infoHash());
        assertEquals(List.of(trackers), meta.announceList());
    }

    @Test
    void toTorrentBytesRoundTripPreservesInfoHashAndTrackers() {
        byte[] infoBytes = Bencode.encode(singleFileInfo());
        List<URI> trackers = List.of(
                URI.create("udp://t1.example.com:6969"),
                URI.create("udp://t2.example.com:6969"));
        Metainfo original = Metainfo.fromInfoDict(infoBytes, trackers);

        Metainfo reparsed = Metainfo.parse(original.toTorrentBytes());
        assertEquals(original.infoHash(), reparsed.infoHash());
        assertEquals("test.bin", reparsed.name());
        assertEquals(List.of(trackers), reparsed.announceList());
    }

    @Test
    void toTorrentBytesWithoutTrackersIsStillValid() {
        Metainfo meta = Metainfo.fromInfoDict(Bencode.encode(singleFileInfo()), List.of());
        Metainfo reparsed = Metainfo.parse(meta.toTorrentBytes());
        assertEquals(meta.infoHash(), reparsed.infoHash());
        assertTrue(reparsed.announceList().isEmpty());
    }

    // ---- Rejecting malformed input ----

    @Test
    void rejectPieceCountMismatch() {
        BValue.BDictionary info = dict(
                "length", integer(40000),          // should be 3 pieces
                "name", string("test.bin"),
                "piece length", integer(16384),
                "pieces", new BValue.BString(PIECE_HASH_1)); // only 1
        assertThrows(IllegalArgumentException.class, () -> Metainfo.parse(torrentBytes(info)));
    }

    @Test
    void rejectMalformedInfo() {
        assertThrows(IllegalArgumentException.class, // missing info
                () -> Metainfo.parse(Bencode.encode(dict("announce", string("http://x/")))));
        assertThrows(IllegalArgumentException.class, // pieces not a multiple of 20
                () -> Metainfo.parse(torrentBytes(dict(
                        "length", integer(1), "name", string("a"),
                        "piece length", integer(16384), "pieces", new BValue.BString(new byte[7])))));
        assertThrows(IllegalArgumentException.class, // piece length <= 0
                () -> Metainfo.parse(torrentBytes(dict(
                        "length", integer(1), "name", string("a"),
                        "piece length", integer(0), "pieces", new BValue.BString(PIECE_HASH_1)))));
        assertThrows(IllegalArgumentException.class, // missing name
                () -> Metainfo.parse(torrentBytes(dict(
                        "length", integer(1),
                        "piece length", integer(16384), "pieces", new BValue.BString(PIECE_HASH_1)))));
    }

    @Test
    void rejectPathTraversal() {
        BValue.BDictionary info = dict(
                "files", new BValue.BList(List.of(
                        dict("length", integer(1), "path", new BValue.BList(List.of(string(".."), string("evil.sh")))))),
                "name", string("mytorrent"),
                "piece length", integer(16384),
                "pieces", new BValue.BString(PIECE_HASH_1));
        assertThrows(IllegalArgumentException.class, () -> Metainfo.parse(torrentBytes(info)));

        BValue.BDictionary slashInName = dict(
                "length", integer(1),
                "name", string("../evil"),
                "piece length", integer(16384),
                "pieces", new BValue.BString(PIECE_HASH_1));
        assertThrows(IllegalArgumentException.class, () -> Metainfo.parse(torrentBytes(slashInName)));
    }
}
