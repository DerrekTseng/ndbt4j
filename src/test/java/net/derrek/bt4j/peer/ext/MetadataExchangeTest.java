package net.derrek.bt4j.peer.ext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.peer.Handshake;
import net.derrek.bt4j.peer.PeerAddress;
import net.derrek.bt4j.peer.PeerConnection;
import net.derrek.bt4j.peer.PeerId;
import net.derrek.bt4j.peer.PeerMessage;
import org.junit.jupiter.api.Test;

/** Injects messages directly via ExtensionRegistry.dispatch to verify BEP 9 assembly and anti-forgery logic (without going over the network). */
class MetadataExchangeTest {

    private static final PeerConnection.Listener NOOP = new PeerConnection.Listener() {
        @Override
        public void onHandshakeCompleted(PeerConnection connection, Handshake theirs) {
        }

        @Override
        public void onMessage(PeerConnection connection, PeerMessage message) {
        }

        @Override
        public void onClosed(PeerConnection connection, IOException error) {
        }
    };

    private static PeerConnection dummyConnection(InfoHash hash) {
        return PeerConnection.outgoing(
                new PeerAddress(InetSocketAddress.createUnresolved("127.0.0.1", 1)),
                hash, PeerId.generate(), 0, false, NOOP);
    }

    private static BValue.BDictionary dict(Object... keyValues) {
        SequencedMap<BValue.BString, BValue> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(BValue.BString.of((String) keyValues[i]), (BValue) keyValues[i + 1]);
        }
        return new BValue.BDictionary(map);
    }

    /** The peer's extension handshake (announces ut_metadata id=7 and metadata_size). */
    private static PeerMessage.Extended peerHandshake(int metadataSize) {
        return new PeerMessage.Extended(0, Bencode.encode(dict(
                "m", dict("ut_metadata", new BValue.BInteger(7)),
                "metadata_size", new BValue.BInteger(metadataSize))));
    }

    /** A data message sent by the peer (our locally announced ut_metadata id = 1). */
    private static PeerMessage.Extended dataMessage(byte[] infoBytes, int piece) {
        int start = piece * MetadataExchange.PIECE_SIZE;
        byte[] data = Arrays.copyOfRange(infoBytes, start,
                Math.min(infoBytes.length, start + MetadataExchange.PIECE_SIZE));
        byte[] header = Bencode.encode(dict(
                "msg_type", new BValue.BInteger(1),
                "piece", new BValue.BInteger(piece),
                "total_size", new BValue.BInteger(infoBytes.length)));
        byte[] payload = new byte[header.length + data.length];
        System.arraycopy(header, 0, payload, 0, header.length);
        System.arraycopy(data, 0, payload, header.length, data.length);
        return new PeerMessage.Extended(1, payload);
    }

    /** Produces an info dictionary larger than 16 KiB (multi-piece metadata). */
    private static Metainfo bigMetainfo() {
        // 1200 piece hashes = a 24000-byte pieces field -> info dict exceeds one metadata piece
        int pieceLength = 16384;
        byte[] content = TorrentFixtures.randomBytes(pieceLength * 2, 5);
        var files = new java.util.ArrayList<TorrentFixtures.TestFile>();
        for (int i = 0; i < 600; i++) {
            files.add(new TorrentFixtures.TestFile(List.of("f" + i + ".bin"), new byte[0]));
        }
        files.add(new TorrentFixtures.TestFile(List.of("main.bin"), content));
        return TorrentFixtures.multiFile("big", files, pieceLength, "http://t/a");
    }

    @Test
    void assemblesMultiPieceMetadataAndVerifiesHash() throws Exception {
        Metainfo meta = bigMetainfo();
        byte[] infoBytes = meta.infoDictBytes();
        assertTrue(infoBytes.length > MetadataExchange.PIECE_SIZE, "test data should exceed one metadata piece");

        MetadataExchange exchange = new MetadataExchange(meta.infoHash());
        ExtensionRegistry registry = new ExtensionRegistry(List.of(exchange));
        PeerConnection conn = dummyConnection(meta.infoHash());

        registry.dispatch(conn, peerHandshake(infoBytes.length));
        assertTrue(registry.peerSupports("ut_metadata"));
        assertFalse(exchange.metadata().isDone());

        int pieceCount = (infoBytes.length + MetadataExchange.PIECE_SIZE - 1) / MetadataExchange.PIECE_SIZE;
        for (int p = 0; p < pieceCount; p++) {
            registry.dispatch(conn, dataMessage(infoBytes, p));
        }
        assertTrue(exchange.metadata().isDone());
        assertArrayEquals(infoBytes, exchange.metadata().get());
        assertEquals(infoBytes.length, exchange.metadataSize().orElseThrow());
    }

    @Test
    void forgedMetadataIsRejectedAndRetriable() throws Exception {
        Metainfo meta = TorrentFixtures.singleFile("f.bin",
                TorrentFixtures.randomBytes(16384, 6), 16384, "http://t/a");
        byte[] infoBytes = meta.infoDictBytes();
        byte[] forged = infoBytes.clone();
        forged[10] ^= 0x01; // tamper with one byte so the SHA-1 must not match

        MetadataExchange exchange = new MetadataExchange(meta.infoHash());
        ExtensionRegistry registry = new ExtensionRegistry(List.of(exchange));
        PeerConnection conn = dummyConnection(meta.infoHash());

        registry.dispatch(conn, peerHandshake(forged.length));
        registry.dispatch(conn, dataMessage(forged, 0));
        assertFalse(exchange.metadata().isDone(), "forged metadata must not pass verification");

        // switch to an honest peer: re-handshake then send correct data -> success
        registry.dispatch(conn, peerHandshake(infoBytes.length));
        registry.dispatch(conn, dataMessage(infoBytes, 0));
        assertTrue(exchange.metadata().isDone());
        assertArrayEquals(infoBytes, exchange.metadata().get());
    }

    @Test
    void oversizedMetadataSizeIsIgnored() {
        InfoHash hash = InfoHash.fromHex("417999cdf5411a6522abeb34c2059434a69d1854");
        MetadataExchange exchange = new MetadataExchange(hash);
        ExtensionRegistry registry = new ExtensionRegistry(List.of(exchange));
        PeerConnection conn = dummyConnection(hash);

        registry.dispatch(conn, peerHandshake(MetadataExchange.MAX_METADATA_SIZE + 1));
        registry.dispatch(conn, peerHandshake(-5));
        assertFalse(exchange.metadata().isDone());
    }

    @Test
    void suppliedMetadataCompletesImmediately() throws Exception {
        Metainfo meta = TorrentFixtures.singleFile("s.bin",
                TorrentFixtures.randomBytes(16384, 8), 16384, "http://t/a");
        MetadataExchange exchange = new MetadataExchange(meta.infoHash());
        exchange.supply(meta.infoDictBytes());

        assertTrue(exchange.metadata().isDone());
        assertArrayEquals(meta.infoDictBytes(), exchange.metadata().get());
        assertEquals(meta.infoDictBytes().length, exchange.metadataSize().orElseThrow());
    }
}
