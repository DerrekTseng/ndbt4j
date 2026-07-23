package net.derrek.bt4j.bencode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * bencoding 編碼／解碼入口（BEP 3）。純函式、無 IO。
 *
 * 嚴格性：
 * <ul>
 *   <li>整數拒絕前導零（i03e）與負零（i-0e）；超出 long 範圍視為錯誤</li>
 *   <li>字串長度拒絕前導零（03:abc）</li>
 *   <li>dict 拒絕重複 key 與非字串 key；未排序的 key 寬容接受（保留原始順序）</li>
 *   <li>巢狀深度上限 {@value #MAX_DEPTH}，防止惡意輸入造成 StackOverflow</li>
 * </ul>
 */
public final class Bencode {

    /** 巢狀深度上限。正常 torrent 深度個位數，此值僅為防禦。 */
    public static final int MAX_DEPTH = 512;

    private Bencode() {
    }

    /**
     * 解碼完整的 bencoded 位元組。
     *
     * @throws BencodeException 格式錯誤，或 data 尾端有多餘位元組
     */
    public static BValue decode(byte[] data) {
        DecodeResult result = decode(data, 0);
        if (result.end() != data.length) {
            throw new BencodeException("位置 " + result.end() + " 之後有多餘位元組");
        }
        return result.value();
    }

    /**
     * 從 data 的 offset 開始解碼單一值，並回報結束位置。
     * 用於需要取得原始位元組區段的情境（例如擷取 info 字典的原始 bytes 以計算 info-hash）。
     */
    public static DecodeResult decode(byte[] data, int offset) {
        if (offset < 0 || offset >= data.length) {
            throw new BencodeException("offset " + offset + " 超出範圍（資料長度 " + data.length + "）");
        }
        Decoder decoder = new Decoder(data, offset);
        BValue value = decoder.parseValue(0);
        return new DecodeResult(value, offset, decoder.pos);
    }

    /** 編碼為 bencoded 位元組。dict key 依原始位元組無號排序，輸出為 canonical 形式。 */
    public static byte[] encode(BValue value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeTo(value, out);
        return out.toByteArray();
    }

    /** decode(byte[], int) 的結果：解出的值與其在原始資料中的區段 [start, end)。 */
    public record DecodeResult(BValue value, int start, int end) {
    }

    // ---- 編碼 ----

    private static void encodeTo(BValue value, ByteArrayOutputStream out) {
        switch (value) {
            case BValue.BString(byte[] bytes) -> {
                out.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                out.write(':');
                out.writeBytes(bytes);
            }
            case BValue.BInteger(long v) -> {
                out.write('i');
                out.writeBytes(Long.toString(v).getBytes(StandardCharsets.US_ASCII));
                out.write('e');
            }
            case BValue.BList(List<BValue> values) -> {
                out.write('l');
                for (BValue item : values) {
                    encodeTo(item, out);
                }
                out.write('e');
            }
            case BValue.BDictionary(SequencedMap<BValue.BString, BValue> entries) -> {
                out.write('d');
                List<BValue.BString> keys = new ArrayList<>(entries.keySet());
                keys.sort((a, b) -> Arrays.compareUnsigned(a.bytes(), b.bytes()));
                for (BValue.BString key : keys) {
                    encodeTo(key, out);
                    encodeTo(entries.get(key), out);
                }
                out.write('e');
            }
        }
    }

    // ---- 解碼 ----

    private static final class Decoder {

        private final byte[] data;
        private int pos;

        Decoder(byte[] data, int offset) {
            this.data = data;
            this.pos = offset;
        }

        BValue parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new BencodeException("巢狀深度超過上限 " + MAX_DEPTH);
            }
            byte b = peek();
            return switch (b) {
                case 'i' -> parseInteger();
                case 'l' -> parseList(depth);
                case 'd' -> parseDictionary(depth);
                default -> {
                    if (isDigit(b)) {
                        yield parseString();
                    }
                    throw new BencodeException("位置 " + pos + "：非預期字元 '" + (char) b + "'");
                }
            };
        }

        private BValue.BInteger parseInteger() {
            int start = pos;
            pos++; // 'i'
            int numStart = pos;
            if (peek() == '-') {
                pos++;
            }
            while (peek() != 'e') {
                if (!isDigit(peek())) {
                    throw new BencodeException("位置 " + pos + "：整數含非法字元 '" + (char) peek() + "'");
                }
                pos++;
            }
            String text = new String(data, numStart, pos - numStart, StandardCharsets.US_ASCII);
            pos++; // 'e'
            validateIntegerFormat(text, start);
            try {
                return new BValue.BInteger(Long.parseLong(text));
            } catch (NumberFormatException e) {
                throw new BencodeException("位置 " + start + "：整數超出 long 範圍: " + text);
            }
        }

        private void validateIntegerFormat(String text, int start) {
            String digits = text.startsWith("-") ? text.substring(1) : text;
            if (digits.isEmpty()) {
                throw new BencodeException("位置 " + start + "：整數缺少數字");
            }
            if (digits.length() > 1 && digits.charAt(0) == '0') {
                throw new BencodeException("位置 " + start + "：整數不得有前導零: " + text);
            }
            if (text.equals("-0")) {
                throw new BencodeException("位置 " + start + "：不允許負零");
            }
        }

        private BValue.BString parseString() {
            int lenStart = pos;
            while (peek() != ':') {
                if (!isDigit(peek())) {
                    throw new BencodeException("位置 " + pos + "：字串長度含非法字元 '" + (char) peek() + "'");
                }
                pos++;
            }
            String lenText = new String(data, lenStart, pos - lenStart, StandardCharsets.US_ASCII);
            if (lenText.length() > 1 && lenText.charAt(0) == '0') {
                throw new BencodeException("位置 " + lenStart + "：字串長度不得有前導零: " + lenText);
            }
            long length;
            try {
                length = Long.parseLong(lenText);
            } catch (NumberFormatException e) {
                throw new BencodeException("位置 " + lenStart + "：字串長度無法解析: " + lenText);
            }
            pos++; // ':'
            if (length > data.length - pos) {
                throw new BencodeException("位置 " + lenStart + "：字串長度 " + length + " 超出剩餘資料 " + (data.length - pos));
            }
            byte[] bytes = Arrays.copyOfRange(data, pos, pos + (int) length);
            pos += (int) length;
            return new BValue.BString(bytes);
        }

        private BValue.BList parseList(int depth) {
            pos++; // 'l'
            List<BValue> values = new ArrayList<>();
            while (peek() != 'e') {
                values.add(parseValue(depth + 1));
            }
            pos++; // 'e'
            return new BValue.BList(List.copyOf(values));
        }

        private BValue.BDictionary parseDictionary(int depth) {
            pos++; // 'd'
            SequencedMap<BValue.BString, BValue> entries = new LinkedHashMap<>();
            while (peek() != 'e') {
                int keyPos = pos;
                if (!isDigit(peek())) {
                    throw new BencodeException("位置 " + keyPos + "：dict 的 key 必須是字串");
                }
                BValue.BString key = parseString();
                BValue value = parseValue(depth + 1);
                if (entries.putIfAbsent(key, value) != null) {
                    throw new BencodeException("位置 " + keyPos + "：dict 有重複的 key: " + key.utf8());
                }
            }
            pos++; // 'e'
            return new BValue.BDictionary(Collections.unmodifiableSequencedMap(entries));
        }

        private byte peek() {
            if (pos >= data.length) {
                throw new BencodeException("資料在位置 " + pos + " 意外結束");
            }
            return data[pos];
        }

        private static boolean isDigit(byte b) {
            return b >= '0' && b <= '9';
        }
    }
}
