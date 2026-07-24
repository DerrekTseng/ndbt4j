package net.derrek.bt4j.peer;

import java.io.IOException;

/** Dials an outgoing {@link PeerTransport} to a peer. Lets a connection be TCP or uTP without PeerConnection caring. */
@FunctionalInterface
public interface PeerConnector {

    PeerTransport connect(PeerAddress address) throws IOException;
}
