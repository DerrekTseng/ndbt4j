package net.derrek.bt4j.tracker;

import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.PeerId;

/**
 * The parameters of a single tracker announce (BEP 3).
 *
 * @param infoHash   the target torrent
 * @param peerId     the local peer id
 * @param port       the local TCP port that accepts incoming connections
 * @param uploaded   bytes uploaded so far
 * @param downloaded bytes downloaded so far
 * @param left       bytes remaining (for selective downloads = remaining bytes of the selected part)
 * @param event      the event
 * @param numWant    the desired number of peers (conventionally 50)
 */
public record AnnounceRequest(InfoHash infoHash,
                              PeerId peerId,
                              int port,
                              long uploaded,
                              long downloaded,
                              long left,
                              AnnounceEvent event,
                              int numWant) {
}
