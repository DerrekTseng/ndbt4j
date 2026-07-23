package net.derrek.bt4j.peer.ext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentHashMap;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;
import net.derrek.bt4j.bencode.BencodeException;
import net.derrek.bt4j.peer.PeerConnection;
import net.derrek.bt4j.peer.PeerMessage;

/**
 * BEP 10 extension protocol framework (one instance per PeerConnection):
 * builds/parses the extension handshake (the bencoded dictionary of extended id=0),
 * maintains the bidirectional name &lt;-&gt; id mapping, and dispatches Extended messages to the
 * registered {@link Extension}s.
 *
 * id semantics (BEP 10): each side declares "send this to me with this id" in its m dictionary --
 * when receiving a message, look it up by the id we advertised; when sending, use the id the peer advertised.
 */
public final class ExtensionRegistry {

    private static final System.Logger LOG = System.getLogger(ExtensionRegistry.class.getName());
    private static final String CLIENT_VERSION = "bt4j/1.0";

    private final Map<String, Extension> byName = new LinkedHashMap<>();
    private final Map<Integer, Extension> byLocalId = new LinkedHashMap<>();
    private final Map<String, Integer> theirIds = new ConcurrentHashMap<>();

    public ExtensionRegistry(List<Extension> extensions) {
        int nextId = 1;
        for (Extension extension : extensions) {
            byName.put(extension.name(), extension);
            byLocalId.put(nextId++, extension);
        }
    }

    /** Builds our extension handshake (sent immediately after the handshake completes, when both sides support the reserved bit). */
    public PeerMessage.Extended buildHandshake(Integer metadataSize) {
        SequencedMap<BValue.BString, BValue> m = new LinkedHashMap<>();
        for (Map.Entry<Integer, Extension> entry : byLocalId.entrySet()) {
            m.put(BValue.BString.of(entry.getValue().name()), new BValue.BInteger(entry.getKey()));
        }
        SequencedMap<BValue.BString, BValue> dict = new LinkedHashMap<>();
        dict.put(BValue.BString.of("m"), new BValue.BDictionary(m));
        dict.put(BValue.BString.of("v"), BValue.BString.of(CLIENT_VERSION));
        if (metadataSize != null) {
            dict.put(BValue.BString.of("metadata_size"), new BValue.BInteger(metadataSize));
        }
        return new PeerMessage.Extended(0, Bencode.encode(new BValue.BDictionary(dict)));
    }

    /** Handles a received Extended message: id=0 is the handshake, the rest are dispatched by the id we advertised. Malformed messages are silently ignored. */
    public void dispatch(PeerConnection connection, PeerMessage.Extended message) {
        if (message.extendedId() == 0) {
            handleHandshake(connection, message.payload());
            return;
        }
        Extension extension = byLocalId.get(message.extendedId());
        if (extension != null) {
            extension.onMessage(connection, this, message.payload());
        }
    }

    private void handleHandshake(PeerConnection connection, byte[] payload) {
        BValue.BDictionary handshake;
        try {
            if (!(Bencode.decode(payload, 0).value() instanceof BValue.BDictionary dict)) {
                return;
            }
            handshake = dict;
        } catch (BencodeException e) {
            return;
        }
        if (handshake.get("m").orElse(null) instanceof BValue.BDictionary m) {
            for (Map.Entry<BValue.BString, BValue> entry : m.entries().entrySet()) {
                if (entry.getValue() instanceof BValue.BInteger(long id)) {
                    String name = entry.getKey().utf8();
                    if (id > 0) {
                        theirIds.put(name, (int) id);
                    } else {
                        theirIds.remove(name); // BEP 10: id=0 means disabled
                    }
                }
            }
        }
        LOG.log(System.Logger.Level.TRACE, () -> "peer extension handshake: " + theirIds.keySet());
        for (Extension extension : byName.values()) {
            extension.onExtensionHandshake(connection, this, handshake);
        }
    }

    /** Whether the peer advertised support for a given extension (valid after the extension handshake arrives). */
    public boolean peerSupports(String extensionName) {
        return theirIds.containsKey(extensionName);
    }

    /** Sends a message by extension name (automatically translated to the id the peer advertised). Returns false if the peer does not support it. */
    public boolean send(PeerConnection connection, String extensionName, byte[] payload) {
        Integer theirId = theirIds.get(extensionName);
        if (theirId == null) {
            return false;
        }
        connection.send(new PeerMessage.Extended(theirId, payload));
        return true;
    }
}
