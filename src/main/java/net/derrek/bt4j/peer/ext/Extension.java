package net.derrek.bt4j.peer.ext;

import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.peer.PeerConnection;

/**
 * SPI for BEP 10 extensions. Each extension (ut_metadata, ut_pex, ...) implements this interface and
 * registers with {@link ExtensionRegistry} (one registry per connection; extension instances may be
 * shared across connections).
 * All callbacks run on the connection's read-loop thread; the registry parameter is the reply channel
 * for that connection.
 */
public interface Extension {

    /** The extension name, used in the m dictionary of the extension handshake (e.g. "ut_metadata"). */
    String name();

    /**
     * The peer's extension handshake has arrived (at which point {@link ExtensionRegistry#peerSupports}
     * can be queried). Additional fields (such as metadata_size) can be read from the handshake.
     */
    void onExtensionHandshake(PeerConnection connection, ExtensionRegistry registry, BValue.BDictionary handshake);

    /** A message belonging to this extension was received (dispatched by the id we advertised). */
    void onMessage(PeerConnection connection, ExtensionRegistry registry, byte[] payload);
}
