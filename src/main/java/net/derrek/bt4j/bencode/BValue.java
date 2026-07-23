package net.derrek.bt4j.bencode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * bencoding 的四種值型別（BEP 3）。
 * 以 sealed interface + record 表示，解碼結果為不可變樹狀結構。
 */
public sealed interface BValue permits BValue.BString, BValue.BInteger, BValue.BList, BValue.BDictionary {

    /** byte string。bencoding 的字串是位元組序列，不保證是合法 UTF-8（例如 pieces 欄位）。 */
    record BString(byte[] bytes) implements BValue {

        /** 以 UTF-8 解讀，僅適用於已知為文字的欄位（如 name、announce）。 */
        public String utf8() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        public static BString of(String text) {
            return new BString(text.getBytes(StandardCharsets.UTF_8));
        }

        // byte[] 需以內容比較（BString 作為 BDictionary 的 key）
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

    /** 整數。bencoding 規格無大小上限，實務上以 long 承載，超出範圍視為格式錯誤。 */
    record BInteger(long value) implements BValue {
    }

    /** list。 */
    record BList(List<BValue> values) implements BValue {
    }

    /**
     * dictionary。BEP 3 要求 key 依原始位元組排序；
     * 解碼時寬容接受未排序輸入並保留原始順序（重新編碼時一律排序為 canonical 形式）。
     */
    record BDictionary(SequencedMap<BString, BValue> entries) implements BValue {

        /** 以 UTF-8 key 取值。 */
        public Optional<BValue> get(String key) {
            return Optional.ofNullable(entries.get(BString.of(key)));
        }
    }
}
