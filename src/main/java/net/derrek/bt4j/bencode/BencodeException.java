package net.derrek.bt4j.bencode;

/** bencoding data format error. */
public class BencodeException extends RuntimeException {

    public BencodeException(String message) {
        super(message);
    }
}
