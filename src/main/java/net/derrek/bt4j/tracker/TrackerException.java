package net.derrek.bt4j.tracker;

/** Tracker communication failure: connection error, timeout, or a failure reason returned by the tracker. */
public class TrackerException extends Exception {

    public TrackerException(String message) {
        super(message);
    }

    public TrackerException(String message, Throwable cause) {
        super(message, cause);
    }
}
