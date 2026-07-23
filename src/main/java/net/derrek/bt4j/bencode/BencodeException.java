package net.derrek.bt4j.bencode;

/** bencoding 資料格式錯誤。 */
public class BencodeException extends RuntimeException {

    public BencodeException(String message) {
        super(message);
    }
}
