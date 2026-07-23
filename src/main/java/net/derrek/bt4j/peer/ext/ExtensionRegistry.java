package net.derrek.bt4j.peer.ext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentHashMap;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;
import net.derrek.bt4j.bencode.BencodeException;
import net.derrek.bt4j.peer.PeerConnection;
import net.derrek.bt4j.peer.PeerMessage;

/**
 * BEP 10 擴充協定框架（每條 PeerConnection 一個實例）：
 * 產生／解析 extension handshake（extended id=0 的 bencoded 字典）、
 * 維護雙向 name ↔ id 對映、分派 Extended 訊息給註冊的 {@link Extension}。
 *
 * id 語意（BEP 10）：雙方各自在 m 字典宣告「請用這個 id 送給我」——
 * 收訊息時用本端宣告的 id 查表；送訊息時用對方宣告的 id。
 */
public final class ExtensionRegistry {

    private static final String CLIENT_VERSION = "bt4j/1.0";

    private final Map<String, Extension> byName = new LinkedHashMap<>();
    private final Map<Integer, Extension> byLocalId = new LinkedHashMap<>();
    private final Map<String, Integer> theirIds = new ConcurrentHashMap<>();

    public ExtensionRegistry(List<Extension> extensions) {
        int nextId = 1;
        for (Extension extension : extensions) {
            byName.put(extension.name(), extension);
            byLocalId.put(nextId++, extension);
        }
    }

    /** 產生本端 extension handshake（雙方 reserved bit 支援時，handshake 完成後立即送出）。 */
    public PeerMessage.Extended buildHandshake(Integer metadataSize) {
        SequencedMap<BValue.BString, BValue> m = new LinkedHashMap<>();
        for (Map.Entry<Integer, Extension> entry : byLocalId.entrySet()) {
            m.put(BValue.BString.of(entry.getValue().name()), new BValue.BInteger(entry.getKey()));
        }
        SequencedMap<BValue.BString, BValue> dict = new LinkedHashMap<>();
        dict.put(BValue.BString.of("m"), new BValue.BDictionary(m));
        dict.put(BValue.BString.of("v"), BValue.BString.of(CLIENT_VERSION));
        if (metadataSize != null) {
            dict.put(BValue.BString.of("metadata_size"), new BValue.BInteger(metadataSize));
        }
        return new PeerMessage.Extended(0, Bencode.encode(new BValue.BDictionary(dict)));
    }

    /** 處理收到的 Extended 訊息：id=0 為 handshake，其餘依本端宣告的 id 分派。格式錯誤靜默忽略。 */
    public void dispatch(PeerConnection connection, PeerMessage.Extended message) {
        if (message.extendedId() == 0) {
            handleHandshake(connection, message.payload());
            return;
        }
        Extension extension = byLocalId.get(message.extendedId());
        if (extension != null) {
            extension.onMessage(connection, this, message.payload());
        }
    }

    private void handleHandshake(PeerConnection connection, byte[] payload) {
        BValue.BDictionary handshake;
        try {
            if (!(Bencode.decode(payload, 0).value() instanceof BValue.BDictionary dict)) {
                return;
            }
            handshake = dict;
        } catch (BencodeException e) {
            return;
        }
        if (handshake.get("m").orElse(null) instanceof BValue.BDictionary m) {
            for (Map.Entry<BValue.BString, BValue> entry : m.entries().entrySet()) {
                if (entry.getValue() instanceof BValue.BInteger(long id)) {
                    String name = entry.getKey().utf8();
                    if (id > 0) {
                        theirIds.put(name, (int) id);
                    } else {
                        theirIds.remove(name); // BEP 10：id=0 表示停用
                    }
                }
            }
        }
        for (Extension extension : byName.values()) {
            extension.onExtensionHandshake(connection, this, handshake);
        }
    }

    /** 對方是否宣告支援某擴充（extension handshake 到達後有效）。 */
    public boolean peerSupports(String extensionName) {
        return theirIds.containsKey(extensionName);
    }

    /** 以擴充名稱送訊息（自動換成對方宣告的 id）。對方不支援時回傳 false。 */
    public boolean send(PeerConnection connection, String extensionName, byte[] payload) {
        Integer theirId = theirIds.get(extensionName);
        if (theirId == null) {
            return false;
        }
        connection.send(new PeerMessage.Extended(theirId, payload));
        return true;
    }
}
