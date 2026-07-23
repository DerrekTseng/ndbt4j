package net.derrek.bt4j;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.peer.Handshake;
import net.derrek.bt4j.peer.PeerId;
import net.derrek.bt4j.peer.PeerMessage;
import net.derrek.bt4j.piece.Bitfield;

/**
 * 測試用最小 seeder：
 * 回應 handshake（亮擴充 bit）、送 extension handshake（ut_metadata + metadata_size）、
 * 全滿 bitfield、Interested→Unchoke、Request→Piece、
 * ut_metadata request→data（BEP 9 服務端）。
 */
public final class TestSeeder implements AutoCloseable {

    private static final int LOCAL_UT_METADATA_ID = 1; // 本端宣告的接收 id

    private final ServerSocket server;
    private final Metainfo metainfo;
    private final byte[] content;
    private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicLong uploaded = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean closed;

    /** 測試用：此 seeder 已上傳（回應 Request）的位元組總數。 */
    public long uploadedBytesForTest() {
        return uploaded.get();
    }

    public TestSeeder(Metainfo metainfo, byte[] content) throws IOException {
        this.metainfo = metainfo;
        this.content = content;
        this.server = new ServerSocket(0);
        Thread.ofVirtual().name("test-seeder-accept").start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        try {
            while (!closed) {
                Socket socket = server.accept();
                sockets.add(socket);
                Thread.ofVirtual().name("test-seeder-conn").start(() -> serve(socket));
            }
        } catch (IOException ignored) {
            // server closed
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            Handshake theirs = Handshake.decode(in.readNBytes(Handshake.LENGTH));
            out.write(Handshake.outgoing(theirs.infoHash(), PeerId.generate(), false, true, false).encode());
            out.flush();

            // extension handshake：宣告 ut_metadata（id=1）與 metadata_size
            byte[] infoBytes = metainfo.infoDictBytes();
            PeerMessage.write(out, new PeerMessage.Extended(0, Bencode.encode(dict(
                    "m", dict("ut_metadata", new BValue.BInteger(LOCAL_UT_METADATA_ID)),
                    "metadata_size", new BValue.BInteger(infoBytes.length)))));

            Bitfield full = new Bitfield(metainfo.pieceCount());
            full.setAll();
            PeerMessage.write(out, new PeerMessage.BitfieldMessage(full));

            int clientUtMetadataId = -1;
            while (!closed) {
                PeerMessage message = PeerMessage.read(in, metainfo.pieceCount());
                switch (message) {
                    case PeerMessage.Interested() -> PeerMessage.write(out, new PeerMessage.Unchoke());
                    case PeerMessage.Request(int piece, int begin, int length) -> {
                        int start = piece * (int) metainfo.pieceLength() + begin;
                        PeerMessage.write(out, new PeerMessage.Piece(piece, begin,
                                Arrays.copyOfRange(content, start, start + length)));
                        uploaded.addAndGet(length);
                    }
                    case PeerMessage.Extended(int extendedId, byte[] payload) -> {
                        if (extendedId == 0) {
                            clientUtMetadataId = parseClientUtMetadataId(payload);
                        } else if (extendedId == LOCAL_UT_METADATA_ID && clientUtMetadataId > 0) {
                            replyMetadata(out, payload, infoBytes, clientUtMetadataId);
                        }
                    }
                    default -> {
                    }
                }
            }
        } catch (IOException ignored) {
            // 對方斷線
        }
    }

    private static int parseClientUtMetadataId(byte[] extensionHandshake) {
        if (Bencode.decode(extensionHandshake) instanceof BValue.BDictionary dict
                && dict.get("m").orElse(null) instanceof BValue.BDictionary m
                && m.get("ut_metadata").orElse(null) instanceof BValue.BInteger(long id)) {
            return (int) id;
        }
        return -1;
    }

    private void replyMetadata(DataOutputStream out, byte[] payload, byte[] infoBytes, int clientId)
            throws IOException {
        Bencode.DecodeResult result = Bencode.decode(payload, 0);
        if (!(result.value() instanceof BValue.BDictionary header)
                || !(header.get("msg_type").orElse(null) instanceof BValue.BInteger(long msgType))
                || msgType != 0
                || !(header.get("piece").orElse(null) instanceof BValue.BInteger(long piece))) {
            return;
        }
        int pieceSize = 16 * 1024;
        int start = (int) piece * pieceSize;
        byte[] data = Arrays.copyOfRange(infoBytes, start, Math.min(infoBytes.length, start + pieceSize));
        byte[] reply = Bencode.encode(dict(
                "msg_type", new BValue.BInteger(1),
                "piece", new BValue.BInteger(piece),
                "total_size", new BValue.BInteger(infoBytes.length)));
        byte[] message = new byte[reply.length + data.length];
        System.arraycopy(reply, 0, message, 0, reply.length);
        System.arraycopy(data, 0, message, reply.length, data.length);
        PeerMessage.write(out, new PeerMessage.Extended(clientId, message));
    }

    private static BValue.BDictionary dict(Object... keyValues) {
        SequencedMap<BValue.BString, BValue> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(BValue.BString.of((String) keyValues[i]), (BValue) keyValues[i + 1]);
        }
        return new BValue.BDictionary(map);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
