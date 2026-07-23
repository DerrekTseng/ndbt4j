package net.derrek.bt4j.dht;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;

/**
 * KRPC message (BEP 5): bencoded query / response / error,
 * carrying the four RPCs ping, find_node, get_peers, announce_peer.
 */
public sealed interface KrpcMessage {

    /** Transaction ID (the t field), pairs a query with its response. */
    byte[] transactionId();

    record Query(byte[] transactionId, String method, BValue.BDictionary arguments) implements KrpcMessage {
    }

    record Response(byte[] transactionId, BValue.BDictionary returnValues) implements KrpcMessage {
    }

    record Error(byte[] transactionId, int code, String message) implements KrpcMessage {
    }

    /**
     * Decode a KRPC packet.
     *
     * @throws IllegalArgumentException on malformed input (the caller should ignore the packet)
     */
    static KrpcMessage decode(byte[] datagram) {
        // Lenient: some implementations append extra bytes after the message, so decode only the first value
        if (!(Bencode.decode(datagram, 0).value() instanceof BValue.BDictionary dict)) {
            throw new IllegalArgumentException("KRPC top level must be a dictionary");
        }
        if (!(dict.get("t").orElse(null) instanceof BValue.BString(byte[] transactionId))) {
            throw new IllegalArgumentException("missing transaction id (t)");
        }
        if (!(dict.get("y").orElse(null) instanceof BValue.BString y)) {
            throw new IllegalArgumentException("missing message type (y)");
        }
        return switch (y.utf8()) {
            case "q" -> {
                if (!(dict.get("q").orElse(null) instanceof BValue.BString method)
                        || !(dict.get("a").orElse(null) instanceof BValue.BDictionary args)) {
                    throw new IllegalArgumentException("query missing q or a");
                }
                yield new Query(transactionId, method.utf8(), args);
            }
            case "r" -> {
                if (!(dict.get("r").orElse(null) instanceof BValue.BDictionary values)) {
                    throw new IllegalArgumentException("response missing r");
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
                throw new IllegalArgumentException("error missing [code, message]");
            }
            default -> throw new IllegalArgumentException("unknown message type: " + y.utf8());
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
