package net.derrek.bt4j.bencode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * The four bencoding value types (BEP 3).
 * Represented as a sealed interface + records; the decoded result is an immutable tree.
 */
public sealed interface BValue permits BValue.BString, BValue.BInteger, BValue.BList, BValue.BDictionary {

    /** byte string. A bencoding string is a byte sequence, not guaranteed to be valid UTF-8 (e.g. the pieces field). */
    record BString(byte[] bytes) implements BValue {

        /** Interpret as UTF-8; only for fields known to be text (such as name, announce). */
        public String utf8() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        public static BString of(String text) {
            return new BString(text.getBytes(StandardCharsets.UTF_8));
        }

        // byte[] must be compared by content (BString is used as a BDictionary key)
        @Override
        public boolean equals(Object o) {
            return o instanceof BString other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "BString[" + utf8() + "]";
        }
    }

    /** integer. The bencoding spec has no size limit; in practice carried as a long, out-of-range values are treated as malformed. */
    record BInteger(long value) implements BValue {
    }

    /** list. */
    record BList(List<BValue> values) implements BValue {
    }

    /**
     * dictionary. BEP 3 requires keys to be sorted by their raw bytes;
     * decoding leniently accepts unsorted input and preserves the original order (re-encoding always sorts to canonical form).
     */
    record BDictionary(SequencedMap<BString, BValue> entries) implements BValue {

        /** Look up a value by UTF-8 key. */
        public Optional<BValue> get(String key) {
            return Optional.ofNullable(entries.get(BString.of(key)));
        }
    }
}
