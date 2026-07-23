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
 * ut_metadata（BEP 9）：磁力連結情境下向 peer 索取 info 字典。
 * 以 16 KiB 為單位請求，全部到齊後驗證 SHA-1 == infoHash 才完成；
 * 驗證失敗整個丟棄重來（防偽造）。
 * 每個 torrent（session）一個實例，跨連線共享進度；也回應他人的 request。
 */
public final class MetadataExchange implements Extension {

    private static final System.Logger LOG = System.getLogger(MetadataExchange.class.getName());

    public static final int PIECE_SIZE = 16 * 1024;

    /** metadata 大小上限（正常 info 字典遠小於此；防惡意 metadata_size）。 */
    static final int MAX_METADATA_SIZE = 8 * 1024 * 1024;

    private static final int MSG_REQUEST = 0;
    private static final int MSG_DATA = 1;
    private static final int MSG_REJECT = 2;

    private final InfoHash expected;
    private final CompletableFuture<byte[]> future = new CompletableFuture<>();

    private byte[] buffer;      // 取得中的 metadata（null = 尚未知道大小或已重設）
    private BitSet received;
    private int pieceTotal;
    private volatile byte[] supplied; // 已驗證的完整 metadata（可回應他人）

    public MetadataExchange(InfoHash expected) {
        this.expected = expected;
    }

    /** 驗證通過的 info 字典原始位元組。 */
    public CompletableFuture<byte[]> metadata() {
        return future;
    }

    /** 已持有 metadata 時（.torrent 情境）設定之，讓本端可回應他人的 request。 */
    public synchronized void supply(byte[] infoDictBytes) {
        this.supplied = infoDictBytes.clone();
        future.complete(supplied);
    }

    /** 本端可提供的 metadata 大小（extension handshake 的 metadata_size 欄位）。 */
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
            return; // 對方也沒有 metadata（同樣在等）
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

    /** 向該 peer 請求所有還缺的 piece（重複請求無害，資料到貨以先到者為準）。 */
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
                // 對方不願提供；其他 peer 的 handshake 會再觸發請求
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
                return; // 長度不符，丟棄此片
            }
            System.arraycopy(data, 0, buffer, start, data.length);
            received.set(piece);
            if (received.cardinality() < pieceTotal) {
                return;
            }
            // 全部到齊：驗證 SHA-1 防偽造
            if (InfoHash.ofInfoDict(buffer).equals(expected)) {
                completed = buffer;
            } else {
                LOG.log(System.Logger.Level.WARNING, () -> "metadata SHA-1 mismatch with info-hash (forged/corrupt), discarding and retrying: " + expected.hex());
                buffer = null; // 偽造或損毀：整個重來（之後的 extension handshake 會重新初始化）
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
