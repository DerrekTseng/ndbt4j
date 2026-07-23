package net.derrek.bt4j.peer.ext;

import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;
import net.derrek.bt4j.bencode.BencodeException;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.PeerConnection;

/**
 * ut_metadata (BEP 9): fetches the info dictionary from peers in the magnet-link case.
 * Requests in 16 KiB units; only completes once all pieces have arrived and SHA-1 == infoHash is verified;
 * on verification failure the whole thing is discarded and retried (anti-forgery).
 * One instance per torrent (session), sharing progress across connections; also responds to others' requests.
 */
public final class MetadataExchange implements Extension {

    private static final System.Logger LOG = System.getLogger(MetadataExchange.class.getName());

    public static final int PIECE_SIZE = 16 * 1024;

    /** Upper bound on metadata size (a normal info dictionary is far smaller; guards against a malicious metadata_size). */
    static final int MAX_METADATA_SIZE = 8 * 1024 * 1024;

    private static final int MSG_REQUEST = 0;
    private static final int MSG_DATA = 1;
    private static final int MSG_REJECT = 2;

    private final InfoHash expected;
    private final CompletableFuture<byte[]> future = new CompletableFuture<>();

    private byte[] buffer;      // metadata being fetched (null = size not yet known or has been reset)
    private BitSet received;
    private int pieceTotal;
    private volatile byte[] supplied; // verified, complete metadata (can serve to others)

    public MetadataExchange(InfoHash expected) {
        this.expected = expected;
    }

    /** The raw bytes of the verified info dictionary. */
    public CompletableFuture<byte[]> metadata() {
        return future;
    }

    /** Set when metadata is already held (.torrent case), so we can respond to others' requests. */
    public synchronized void supply(byte[] infoDictBytes) {
        this.supplied = infoDictBytes.clone();
        future.complete(supplied);
    }

    /** The metadata size we can supply (the metadata_size field of the extension handshake). */
    public Optional<Integer> metadataSize() {
        byte[] meta = supplied;
        return meta == null ? Optional.empty() : Optional.of(meta.length);
    }

    @Override
    public String name() {
        return "ut_metadata";
    }

    @Override
    public void onExtensionHandshake(PeerConnection connection, ExtensionRegistry registry,
                                     BValue.BDictionary handshake) {
        if (future.isDone() || !registry.peerSupports(name())) {
            return;
        }
        if (!(handshake.get("metadata_size").orElse(null) instanceof BValue.BInteger(long size))) {
            return; // the peer has no metadata either (also waiting)
        }
        synchronized (this) {
            if (buffer == null) {
                if (size <= 0 || size > MAX_METADATA_SIZE) {
                    return;
                }
                buffer = new byte[(int) size];
                received = new BitSet();
                pieceTotal = (int) ((size + PIECE_SIZE - 1) / PIECE_SIZE);
            }
        }
        requestMissing(connection, registry);
    }

    /** Requests all still-missing pieces from this peer (duplicate requests are harmless; the first-arriving data wins). */
    private void requestMissing(PeerConnection connection, ExtensionRegistry registry) {
        int total;
        BitSet done;
        synchronized (this) {
            if (buffer == null || future.isDone()) {
                return;
            }
            total = pieceTotal;
            done = (BitSet) received.clone();
        }
        for (int piece = 0; piece < total; piece++) {
            if (!done.get(piece)) {
                registry.send(connection, name(), Bencode.encode(dict(
                        "msg_type", MSG_REQUEST, "piece", piece)));
            }
        }
    }

    @Override
    public void onMessage(PeerConnection connection, ExtensionRegistry registry, byte[] payload) {
        BValue.BDictionary header;
        int headerEnd;
        try {
            Bencode.DecodeResult result = Bencode.decode(payload, 0);
            if (!(result.value() instanceof BValue.BDictionary dict)) {
                return;
            }
            header = dict;
            headerEnd = result.end();
        } catch (BencodeException e) {
            return;
        }
        if (!(header.get("msg_type").orElse(null) instanceof BValue.BInteger(long msgType))
                || !(header.get("piece").orElse(null) instanceof BValue.BInteger(long piece))) {
            return;
        }
        switch ((int) msgType) {
            case MSG_REQUEST -> handleRequest(connection, registry, (int) piece);
            case MSG_DATA -> handleData(Arrays.copyOfRange(payload, headerEnd, payload.length), (int) piece);
            case MSG_REJECT -> {
                // the peer is unwilling to supply; another peer's handshake will trigger a request again
            }
            default -> {
            }
        }
    }

    private void handleRequest(PeerConnection connection, ExtensionRegistry registry, int piece) {
        byte[] meta = supplied;
        int start = piece * PIECE_SIZE;
        if (meta == null || piece < 0 || start >= meta.length) {
            registry.send(connection, name(), Bencode.encode(dict("msg_type", MSG_REJECT, "piece", piece)));
            return;
        }
        byte[] header = Bencode.encode(dict("msg_type", MSG_DATA, "piece", piece, "total_size", meta.length));
        int length = Math.min(PIECE_SIZE, meta.length - start);
        byte[] message = new byte[header.length + length];
        System.arraycopy(header, 0, message, 0, header.length);
        System.arraycopy(meta, start, message, header.length, length);
        registry.send(connection, name(), message);
    }

    private void handleData(byte[] data, int piece) {
        byte[] completed = null;
        synchronized (this) {
            if (future.isDone() || buffer == null || piece < 0 || piece >= pieceTotal || received.get(piece)) {
                return;
            }
            int start = piece * PIECE_SIZE;
            int expectedLength = Math.min(PIECE_SIZE, buffer.length - start);
            if (data.length != expectedLength) {
                return; // length mismatch, discard this piece
            }
            System.arraycopy(data, 0, buffer, start, data.length);
            received.set(piece);
            if (received.cardinality() < pieceTotal) {
                return;
            }
            // all pieces arrived: verify SHA-1 against forgery
            if (InfoHash.ofInfoDict(buffer).equals(expected)) {
                completed = buffer;
            } else {
                LOG.log(System.Logger.Level.WARNING, () -> "metadata SHA-1 mismatch with info-hash (forged/corrupt), discarding and retrying: " + expected.hex());
                buffer = null; // forged or corrupt: start over entirely (a later extension handshake reinitializes)
                received = null;
            }
        }
        if (completed != null) {
            LOG.log(System.Logger.Level.DEBUG, () -> "metadata fetched and verified: " + expected.hex());
            supplied = completed;
            future.complete(completed.clone());
        }
    }

    private static BValue.BDictionary dict(Object... keyValues) {
        SequencedMap<BValue.BString, BValue> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(BValue.BString.of((String) keyValues[i]),
                    new BValue.BInteger(((Number) keyValues[i + 1]).longValue()));
        }
        return new BValue.BDictionary(map);
    }
}
