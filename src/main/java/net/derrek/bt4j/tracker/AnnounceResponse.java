package net.derrek.bt4j.tracker;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import net.derrek.bt4j.peer.PeerAddress;

/**
 * A tracker announce response (BEP 3 / compact format BEP 23).
 *
 * @param interval the time to wait before the next announce
 * @param seeders  number of seeders (complete; the tracker may not provide it)
 * @param leechers number of leechers (incomplete; the tracker may not provide it)
 * @param peers    the peer list
 */
public record AnnounceResponse(Duration interval,
                               OptionalInt seeders,
                               OptionalInt leechers,
                               List<PeerAddress> peers) {
}
