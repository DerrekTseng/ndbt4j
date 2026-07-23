package net.derrek.bt4j.bencode;

import java.nio.charset.StandardCharsets;
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
    }

    /** 整數。bencoding 無大小上限，實務上以 long 承載。 */
    record BInteger(long value) implements BValue {
    }

    /** list。 */
    record BList(List<BValue> values) implements BValue {
    }

    /**
     * dictionary。key 依原始位元組排序（bencoding 規範），
     * 以 SequencedMap 保留順序，確保重新編碼可還原出相同位元組（info-hash 計算依賴此性質）。
     */
    record BDictionary(SequencedMap<BString, BValue> entries) implements BValue {

        /** 以 UTF-8 key 取值。 */
        public Optional<BValue> get(String key) {
            throw new UnsupportedOperationException("尚未實作");
        }
    }
}
