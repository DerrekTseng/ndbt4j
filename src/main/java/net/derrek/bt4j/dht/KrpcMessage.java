package net.derrek.bt4j.dht;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;

/**
 * KRPC 訊息（BEP 5）：bencoded 的 query / response / error，
 * 承載 ping、find_node、get_peers、announce_peer 四種 RPC。
 */
public sealed interface KrpcMessage {

    /** 交易 ID（t 欄位），配對 query 與 response。 */
    byte[] transactionId();

    record Query(byte[] transactionId, String method, BValue.BDictionary arguments) implements KrpcMessage {
    }

    record Response(byte[] transactionId, BValue.BDictionary returnValues) implements KrpcMessage {
    }

    record Error(byte[] transactionId, int code, String message) implements KrpcMessage {
    }

    /**
     * 解碼 KRPC 封包。
     *
     * @throws IllegalArgumentException 格式不符（呼叫端應忽略該封包）
     */
    static KrpcMessage decode(byte[] datagram) {
        // 寬容：部分實作會在訊息後帶多餘位元組，只解第一個值
        if (!(Bencode.decode(datagram, 0).value() instanceof BValue.BDictionary dict)) {
            throw new IllegalArgumentException("KRPC 頂層必須是 dictionary");
        }
        if (!(dict.get("t").orElse(null) instanceof BValue.BString(byte[] transactionId))) {
            throw new IllegalArgumentException("缺少交易 id (t)");
        }
        if (!(dict.get("y").orElse(null) instanceof BValue.BString y)) {
            throw new IllegalArgumentException("缺少訊息類型 (y)");
        }
        return switch (y.utf8()) {
            case "q" -> {
                if (!(dict.get("q").orElse(null) instanceof BValue.BString method)
                        || !(dict.get("a").orElse(null) instanceof BValue.BDictionary args)) {
                    throw new IllegalArgumentException("query 缺少 q 或 a");
                }
                yield new Query(transactionId, method.utf8(), args);
            }
            case "r" -> {
                if (!(dict.get("r").orElse(null) instanceof BValue.BDictionary values)) {
                    throw new IllegalArgumentException("response 缺少 r");
                }
                yield new Response(transactionId, values);
            }
            case "e" -> {
                if (dict.get("e").orElse(null) instanceof BValue.BList list
                        && list.values().size() >= 2
                        && list.values().get(0) instanceof BValue.BInteger(long code)
                        && list.values().get(1) instanceof BValue.BString message) {
                    yield new Error(transactionId, (int) code, message.utf8());
                }
                throw new IllegalArgumentException("error 缺少 [code, message]");
            }
            default -> throw new IllegalArgumentException("未知的訊息類型: " + y.utf8());
        };
    }

    static byte[] encode(KrpcMessage message) {
        SequencedMap<BValue.BString, BValue> dict = new LinkedHashMap<>();
        dict.put(BValue.BString.of("t"), new BValue.BString(message.transactionId()));
        switch (message) {
            case Query(byte[] t, String method, BValue.BDictionary args) -> {
                dict.put(BValue.BString.of("y"), BValue.BString.of("q"));
                dict.put(BValue.BString.of("q"), BValue.BString.of(method));
                dict.put(BValue.BString.of("a"), args);
            }
            case Response(byte[] t, BValue.BDictionary values) -> {
                dict.put(BValue.BString.of("y"), BValue.BString.of("r"));
                dict.put(BValue.BString.of("r"), values);
            }
            case Error(byte[] t, int code, String errorMessage) -> {
                dict.put(BValue.BString.of("y"), BValue.BString.of("e"));
                dict.put(BValue.BString.of("e"), new BValue.BList(List.of(
                        new BValue.BInteger(code), BValue.BString.of(errorMessage))));
            }
        }
        return Bencode.encode(new BValue.BDictionary(dict));
    }
}
