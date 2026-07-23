package net.derrek.bt4j.tracker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.derrek.bt4j.peer.PeerAddress;

/**
 * Announce scheduling for a single torrent (BEP 12 multitracker):
 * each round tries the tiers in order; within a tier a successful tracker is promoted to the front (preferred next round);
 * if all fail, retry at a fixed interval. started/completed/stopped events are triggered by the lifecycle methods.
 */
public final class TrackerManager implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(TrackerManager.class.getName());

    /** Upper bound on the interval reported by the tracker (keeps peer discovery responsive). */
    private static final Duration MAX_INTERVAL = Duration.ofMinutes(5);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final List<List<Tracker>> tiers;
    private final Function<AnnounceEvent, AnnounceRequest> requests;
    private final Consumer<List<PeerAddress>> onPeersFound;

    private volatile Thread thread;
    private volatile boolean closed;
    private volatile boolean completedPending;
    private boolean startedSent;

    /**
     * @param tiers        BEP 12 tracker tiers; each tier is shuffled once at construction (per BEP 12)
     * @param requests     produces announce parameters per event (the session supplies the current stats)
     * @param onPeersFound callback for the peer list obtained on each announce
     */
    public TrackerManager(List<List<Tracker>> tiers,
                          Function<AnnounceEvent, AnnounceRequest> requests,
                          Consumer<List<PeerAddress>> onPeersFound) {
        List<List<Tracker>> copy = new ArrayList<>();
        for (List<Tracker> tier : tiers) {
            List<Tracker> shuffled = new ArrayList<>(tier);
            Collections.shuffle(shuffled);
            copy.add(shuffled);
        }
        this.tiers = copy;
        this.requests = requests;
        this.onPeersFound = onPeersFound;
    }

    /** Starts periodic announces (the first successful announce carries started). */
    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        thread = Thread.ofVirtual().name("bt4j-tracker-manager").start(this::loop);
    }

    /** Download complete: send completed to the tracker as soon as possible. */
    public void announceCompleted() {
        completedPending = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Stops the schedule; if started was already sent, makes a best effort to send stopped (on the scheduling thread, without blocking the caller). */
    @Override
    public void close() {
        closed = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void loop() {
        while (!closed) {
            AnnounceEvent event = !startedSent ? AnnounceEvent.STARTED
                    : completedPending ? AnnounceEvent.COMPLETED
                    : AnnounceEvent.NONE;
            Duration interval = announceOnce(event);
            if (interval != null) {
                startedSent = true;
                if (event == AnnounceEvent.COMPLETED) {
                    completedPending = false;
                }
            }
            try {
                Duration wait = interval == null ? RETRY_DELAY
                        : interval.compareTo(MAX_INTERVAL) > 0 ? MAX_INTERVAL : interval;
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException e) {
                // wakeup reason: completed pending (back to top of loop) or close (loop condition exits)
            }
        }
        Thread.interrupted(); // clear the flag so the stopped announce is not interrupted immediately
        if (startedSent) {
            announceOnce(AnnounceEvent.STOPPED);
        }
    }

    /** Tries one round in tier order. On success returns the interval and promotes that tracker to the front of its tier; returns null if all fail. */
    private Duration announceOnce(AnnounceEvent event) {
        for (List<Tracker> tier : tiers) {
            List<Tracker> snapshot;
            synchronized (tier) {
                snapshot = List.copyOf(tier);
            }
            for (Tracker tracker : snapshot) {
                try {
                    AnnounceResponse response = tracker.announce(requests.apply(event));
                    synchronized (tier) {
                        tier.remove(tracker);
                        tier.addFirst(tracker); // BEP 12: a successful tracker is preferred next round
                    }
                    if (!response.peers().isEmpty()) {
                        onPeersFound.accept(response.peers());
                    }
                    return response.interval();
                } catch (TrackerException | RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, () -> "tracker " + tracker.uri() + " announce failed: " + e.getMessage());
                }
            }
        }
        LOG.log(System.Logger.Level.WARNING, () -> "all trackers failed to announce (event=" + event + "), retrying in " + RETRY_DELAY.toSeconds() + "s");
        return null;
    }
}
