package net.derrek.bt4j.tracker;

/** tracker 通訊失敗：連線錯誤、逾時、或 tracker 回傳 failure reason。 */
public class TrackerException extends Exception {

    public TrackerException(String message) {
        super(message);
    }

    public TrackerException(String message, Throwable cause) {
        super(message, cause);
    }
}
