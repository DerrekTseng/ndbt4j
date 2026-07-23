package net.derrek.bt4j.dht;

import net.derrek.bt4j.bencode.BValue;

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

    static KrpcMessage decode(byte[] datagram) {
        throw new UnsupportedOperationException("尚未實作");
    }

    static byte[] encode(KrpcMessage message) {
        throw new UnsupportedOperationException("尚未實作");
    }
}
