package net.derrek.bt4j.dht;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import net.derrek.bt4j.bencode.BValue;
import org.junit.jupiter.api.Test;

class KrpcMessageTest {

    private static BValue.BDictionary dict(String key, BValue value) {
        SequencedMap<BValue.BString, BValue> map = new LinkedHashMap<>();
        map.put(BValue.BString.of(key), value);
        return new BValue.BDictionary(map);
    }

    @Test
    void decodeBep5PingExample() {
        // The ping query example from the BEP 5 document
        byte[] packet = "d1:ad2:id20:abcdefghij0123456789e1:q4:ping1:t2:aa1:y1:qe"
                .getBytes(StandardCharsets.ISO_8859_1);
        KrpcMessage.Query query = assertInstanceOf(KrpcMessage.Query.class, KrpcMessage.decode(packet));
        assertEquals("ping", query.method());
        assertArrayEquals("aa".getBytes(StandardCharsets.ISO_8859_1), query.transactionId());
        assertEquals(BValue.BString.of("abcdefghij0123456789"),
                query.arguments().get("id").orElseThrow());
    }

    @Test
    void queryRoundTrip() {
        KrpcMessage.Query original = new KrpcMessage.Query(
                new byte[] {0x01, 0x02}, "get_peers", dict("id", BValue.BString.of("x".repeat(20))));
        KrpcMessage.Query decoded = assertInstanceOf(KrpcMessage.Query.class,
                KrpcMessage.decode(KrpcMessage.encode(original)));
        assertEquals(original.method(), decoded.method());
        assertArrayEquals(original.transactionId(), decoded.transactionId());
    }

    @Test
    void responseRoundTrip() {
        KrpcMessage.Response original = new KrpcMessage.Response(
                new byte[] {0x0A}, dict("id", BValue.BString.of("y".repeat(20))));
        KrpcMessage.Response decoded = assertInstanceOf(KrpcMessage.Response.class,
                KrpcMessage.decode(KrpcMessage.encode(original)));
        assertEquals(BValue.BString.of("y".repeat(20)), decoded.returnValues().get("id").orElseThrow());
    }

    @Test
    void errorRoundTrip() {
        KrpcMessage.Error original = new KrpcMessage.Error(new byte[] {0x0B}, 203, "Protocol Error");
        KrpcMessage.Error decoded = assertInstanceOf(KrpcMessage.Error.class,
                KrpcMessage.decode(KrpcMessage.encode(original)));
        assertEquals(203, decoded.code());
        assertEquals("Protocol Error", decoded.message());
    }

    @Test
    void rejectMalformed() {
        assertThrows(RuntimeException.class, () -> KrpcMessage.decode("le".getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(RuntimeException.class, () -> KrpcMessage.decode("de".getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(RuntimeException.class,
                () -> KrpcMessage.decode("d1:t2:aa1:y1:xe".getBytes(StandardCharsets.ISO_8859_1)));
    }
}
