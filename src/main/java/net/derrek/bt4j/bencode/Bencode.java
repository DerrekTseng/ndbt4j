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
 * bencoding encode/decode entry point (BEP 3). Pure functions, no IO.
 *
 * Strictness:
 * <ul>
 *   <li>Integers reject leading zeros (i03e) and negative zero (i-0e); out-of-long-range is an error</li>
 *   <li>String lengths reject leading zeros (03:abc)</li>
 *   <li>Dicts reject duplicate keys and non-string keys; unsorted keys are leniently accepted (original order preserved)</li>
 *   <li>Nesting depth limit {@value #MAX_DEPTH}, to prevent malicious input from causing a StackOverflow</li>
 * </ul>
 */
public final class Bencode {

    /** Nesting depth limit. Normal torrents are single-digit deep; this value is purely defensive. */
    public static final int MAX_DEPTH = 512;

    private Bencode() {
    }

    /**
     * Decode a complete bencoded byte array.
     *
     * @throws BencodeException on malformed input, or if there are trailing bytes after data
     */
    public static BValue decode(byte[] data) {
        DecodeResult result = decode(data, 0);
        if (result.end() != data.length) {
            throw new BencodeException("trailing bytes after position " + result.end());
        }
        return result.value();
    }

    /**
     * Decode a single value starting at offset in data, reporting the end position.
     * Used when the raw byte span is needed (e.g. extracting the raw bytes of the info dictionary to compute the info-hash).
     */
    public static DecodeResult decode(byte[] data, int offset) {
        if (offset < 0 || offset >= data.length) {
            throw new BencodeException("offset " + offset + " out of range (data length " + data.length + ")");
        }
        Decoder decoder = new Decoder(data, offset);
        BValue value = decoder.parseValue(0);
        return new DecodeResult(value, offset, decoder.pos);
    }

    /** Encode to bencoded bytes. Dict keys are sorted by unsigned raw-byte order; output is canonical form. */
    public static byte[] encode(BValue value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeTo(value, out);
        return out.toByteArray();
    }

    /** Result of decode(byte[], int): the decoded value and its span [start, end) in the original data. */
    public record DecodeResult(BValue value, int start, int end) {
    }

    // ---- Encoding ----

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

    // ---- Decoding ----

    private static final class Decoder {

        private final byte[] data;
        private int pos;

        Decoder(byte[] data, int offset) {
            this.data = data;
            this.pos = offset;
        }

        BValue parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new BencodeException("nesting depth exceeds limit " + MAX_DEPTH);
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
                    throw new BencodeException("position " + pos + ": unexpected character '" + (char) b + "'");
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
                    throw new BencodeException("position " + pos + ": integer contains illegal character '" + (char) peek() + "'");
                }
                pos++;
            }
            String text = new String(data, numStart, pos - numStart, StandardCharsets.US_ASCII);
            pos++; // 'e'
            validateIntegerFormat(text, start);
            try {
                return new BValue.BInteger(Long.parseLong(text));
            } catch (NumberFormatException e) {
                throw new BencodeException("position " + start + ": integer out of long range: " + text);
            }
        }

        private void validateIntegerFormat(String text, int start) {
            String digits = text.startsWith("-") ? text.substring(1) : text;
            if (digits.isEmpty()) {
                throw new BencodeException("position " + start + ": integer has no digits");
            }
            if (digits.length() > 1 && digits.charAt(0) == '0') {
                throw new BencodeException("position " + start + ": integer must not have leading zeros: " + text);
            }
            if (text.equals("-0")) {
                throw new BencodeException("position " + start + ": negative zero is not allowed");
            }
        }

        private BValue.BString parseString() {
            int lenStart = pos;
            while (peek() != ':') {
                if (!isDigit(peek())) {
                    throw new BencodeException("position " + pos + ": string length contains illegal character '" + (char) peek() + "'");
                }
                pos++;
            }
            String lenText = new String(data, lenStart, pos - lenStart, StandardCharsets.US_ASCII);
            if (lenText.length() > 1 && lenText.charAt(0) == '0') {
                throw new BencodeException("position " + lenStart + ": string length must not have leading zeros: " + lenText);
            }
            long length;
            try {
                length = Long.parseLong(lenText);
            } catch (NumberFormatException e) {
                throw new BencodeException("position " + lenStart + ": string length could not be parsed: " + lenText);
            }
            pos++; // ':'
            if (length > data.length - pos) {
                throw new BencodeException("position " + lenStart + ": string length " + length + " exceeds remaining data " + (data.length - pos));
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
                    throw new BencodeException("position " + keyPos + ": dict key must be a string");
                }
                BValue.BString key = parseString();
                BValue value = parseValue(depth + 1);
                if (entries.putIfAbsent(key, value) != null) {
                    throw new BencodeException("position " + keyPos + ": dict has a duplicate key: " + key.utf8());
                }
            }
            pos++; // 'e'
            return new BValue.BDictionary(Collections.unmodifiableSequencedMap(entries));
        }

        private byte peek() {
            if (pos >= data.length) {
                throw new BencodeException("data ended unexpectedly at position " + pos);
            }
            return data[pos];
        }

        private static boolean isDigit(byte b) {
            return b >= '0' && b <= '9';
        }
    }
}
