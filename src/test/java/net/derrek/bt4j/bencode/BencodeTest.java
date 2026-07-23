package net.derrek.bt4j.bencode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BencodeTest {

    /** ISO-8859-1 保證 0-255 每個 byte 值原樣對映，適合建構含二進位內容的測試資料。 */
    private static byte[] raw(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static BValue decode(String s) {
        return Bencode.decode(raw(s));
    }

    private static String encodeToString(BValue value) {
        return new String(Bencode.encode(value), StandardCharsets.ISO_8859_1);
    }

    @Nested
    class Integers {

        @Test
        void basic() {
            assertEquals(new BValue.BInteger(0), decode("i0e"));
            assertEquals(new BValue.BInteger(42), decode("i42e"));
            assertEquals(new BValue.BInteger(-42), decode("i-42e"));
        }

        @Test
        void longBounds() {
            assertEquals(new BValue.BInteger(Long.MAX_VALUE), decode("i9223372036854775807e"));
            assertEquals(new BValue.BInteger(Long.MIN_VALUE), decode("i-9223372036854775808e"));
        }

        @Test
        void rejectMalformed() {
            assertThrows(BencodeException.class, () -> decode("ie"));
            assertThrows(BencodeException.class, () -> decode("i-e"));
            assertThrows(BencodeException.class, () -> decode("i03e"));
            assertThrows(BencodeException.class, () -> decode("i00e"));
            assertThrows(BencodeException.class, () -> decode("i-0e"));
            assertThrows(BencodeException.class, () -> decode("i1a2e"));
            assertThrows(BencodeException.class, () -> decode("i42"));
            assertThrows(BencodeException.class, () -> decode("i9223372036854775808e")); // long 溢位
        }
    }

    @Nested
    class Strings {

        @Test
        void basic() {
            assertEquals(BValue.BString.of("spam"), decode("4:spam"));
            assertEquals(BValue.BString.of(""), decode("0:"));
            assertEquals(BValue.BString.of("0123456789"), decode("10:0123456789"));
        }

        @Test
        void binaryContentIsPreserved() {
            byte[] data = {'2', ':', (byte) 0xFF, 0x00};
            BValue.BString result = (BValue.BString) Bencode.decode(data);
            assertArrayEquals(new byte[] {(byte) 0xFF, 0x00}, result.bytes());
        }

        @Test
        void rejectMalformed() {
            assertThrows(BencodeException.class, () -> decode("03:abc"));  // 長度前導零
            assertThrows(BencodeException.class, () -> decode("5:ab"));    // 長度超過剩餘資料
            assertThrows(BencodeException.class, () -> decode("4spam"));   // 缺冒號
            assertThrows(BencodeException.class, () -> decode("4:"));      // 內容不足
        }
    }

    @Nested
    class Lists {

        @Test
        void basic() {
            assertEquals(new BValue.BList(List.of()), decode("le"));
            assertEquals(
                    new BValue.BList(List.of(BValue.BString.of("spam"), new BValue.BInteger(42))),
                    decode("l4:spami42ee"));
        }

        @Test
        void nested() {
            assertEquals(
                    new BValue.BList(List.of(new BValue.BList(List.of(BValue.BString.of("spam"))))),
                    decode("ll4:spamee"));
        }

        @Test
        void rejectTruncated() {
            assertThrows(BencodeException.class, () -> decode("l4:spam"));
            assertThrows(BencodeException.class, () -> decode("l"));
        }
    }

    @Nested
    class Dictionaries {

        @Test
        void basic() {
            BValue.BDictionary dict = (BValue.BDictionary) decode("d3:cow3:moo4:spam4:eggse");
            assertEquals(2, dict.entries().size());
            assertEquals(BValue.BString.of("moo"), dict.get("cow").orElseThrow());
            assertEquals(BValue.BString.of("eggs"), dict.get("spam").orElseThrow());
            assertTrue(dict.get("missing").isEmpty());
            assertEquals(new BValue.BDictionary(new LinkedHashMap<>()), decode("de"));
        }

        @Test
        void unsortedKeysAcceptedAndOrderPreserved() {
            BValue.BDictionary dict = (BValue.BDictionary) decode("d1:b1:x1:a1:ye");
            assertEquals(List.of(BValue.BString.of("b"), BValue.BString.of("a")),
                    List.copyOf(dict.entries().keySet()));
        }

        @Test
        void rejectMalformed() {
            assertThrows(BencodeException.class, () -> decode("d1:ai1e1:ai2ee")); // 重複 key
            assertThrows(BencodeException.class, () -> decode("di1e1:ae"));       // 非字串 key
            assertThrows(BencodeException.class, () -> decode("d3:cow"));         // 截斷
            assertThrows(BencodeException.class, () -> decode("d3:cowe"));        // 有 key 沒 value
        }
    }

    @Nested
    class TopLevel {

        @Test
        void rejectTrailingGarbage() {
            assertThrows(BencodeException.class, () -> decode("i1ex"));
            assertThrows(BencodeException.class, () -> decode("lei0e"));
        }

        @Test
        void rejectEmptyAndBadStart() {
            assertThrows(BencodeException.class, () -> decode(""));
            assertThrows(BencodeException.class, () -> decode("x"));
            assertThrows(BencodeException.class, () -> decode("-1:a"));
        }

        @Test
        void deepNestingThrowsInsteadOfStackOverflow() {
            String bomb = "l".repeat(Bencode.MAX_DEPTH + 10);
            BencodeException e = assertThrows(BencodeException.class, () -> decode(bomb));
            assertTrue(e.getMessage().contains("深度"));
        }
    }

    @Nested
    class OffsetDecoding {

        @Test
        void decodeFromOffsetReportsSpan() {
            byte[] data = raw("i42e4:spam");
            Bencode.DecodeResult first = Bencode.decode(data, 0);
            assertEquals(new BValue.BInteger(42), first.value());
            assertEquals(0, first.start());
            assertEquals(4, first.end());

            Bencode.DecodeResult second = Bencode.decode(data, first.end());
            assertEquals(BValue.BString.of("spam"), second.value());
            assertEquals(4, second.start());
            assertEquals(10, second.end());
        }

        @Test
        void infoDictRawSpanExtraction() {
            // 模擬 .torrent：外層 dict 內含 info dict，擷取其原始位元組後可獨立重解
            byte[] data = raw("d4:infod3:foo3:baree");
            int infoStart = 7; // "d4:info" 之後
            Bencode.DecodeResult info = Bencode.decode(data, infoStart);
            assertInstanceOf(BValue.BDictionary.class, info.value());
            assertEquals(19, info.end());

            byte[] span = java.util.Arrays.copyOfRange(data, info.start(), info.end());
            assertEquals(info.value(), Bencode.decode(span));
        }

        @Test
        void rejectOutOfRangeOffset() {
            assertThrows(BencodeException.class, () -> Bencode.decode(raw("i1e"), 3));
            assertThrows(BencodeException.class, () -> Bencode.decode(raw("i1e"), -1));
        }
    }

    @Nested
    class Encoding {

        @Test
        void canonicalRoundTrip() {
            // canonical 輸入：decode 後 encode 必須位元組相同
            for (String canonical : List.of(
                    "i0e", "i-42e", "0:", "4:spam", "le", "de",
                    "l4:spami42ee",
                    "d3:cow3:moo4:spam4:eggse",
                    "d4:infod6:lengthi170917888e4:name8:test.iso12:piece lengthi262144eee")) {
                assertEquals(canonical, encodeToString(Bencode.decode(raw(canonical))), canonical);
            }
        }

        @Test
        void dictKeysAreSortedOnEncode() {
            SequencedMap<BValue.BString, BValue> entries = new LinkedHashMap<>();
            entries.put(BValue.BString.of("b"), new BValue.BInteger(2));
            entries.put(BValue.BString.of("a"), new BValue.BInteger(1));
            assertEquals("d1:ai1e1:bi2ee", encodeToString(new BValue.BDictionary(entries)));
        }

        @Test
        void dictKeySortIsUnsignedByteOrder() {
            // 0xFF 無號比較大於 'a'；若誤用有號比較 0xFF(-1) 會排在前面
            SequencedMap<BValue.BString, BValue> entries = new LinkedHashMap<>();
            entries.put(new BValue.BString(new byte[] {(byte) 0xFF}), new BValue.BInteger(2));
            entries.put(BValue.BString.of("a"), new BValue.BInteger(1));
            assertEquals("d1:ai1e1:ÿi2ee", encodeToString(new BValue.BDictionary(entries)));
        }

        @Test
        void decodeEncodeDecodeYieldsEqualValue() {
            BValue original = decode("d1:al1:a1:bi3ee1:bd1:xi1eee");
            assertEquals(original, Bencode.decode(Bencode.encode(original)));
        }
    }
}
