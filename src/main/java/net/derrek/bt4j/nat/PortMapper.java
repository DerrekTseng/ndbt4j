package net.derrek.bt4j.nat;

import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps a single TCP port forwarded on the home gateway so peers on the internet can open incoming connections to
 * a host behind NAT. Tries NAT-PMP first (one quick datagram to the gateway) and falls back to UPnP-IGD (SSDP +
 * SOAP), then refreshes the mapping before its lease expires. A background virtual thread owns all of this; the
 * caller just constructs it with the listen port and {@link #close}s it on shutdown.
 *
 * Entirely best-effort and fail-soft: no gateway, an unsupportive one, or a restricted network simply leaves the
 * port unmapped (the client still works by dialing out), never throwing.
 */
public final class PortMapper implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PortMapper.class.getName());

    /** Requested mapping lifetime; the refresh loop renews at half this interval (NAT-PMP recommends renewing early). */
    static final int LEASE_SECONDS = 3600;
    private static final long REFRESH_MILLIS = LEASE_SECONDS * 1000L / 2;
    private static final String DESCRIPTION = "bt4j";

    private final int port;
    private final AtomicReference<InetAddress> externalAddress = new AtomicReference<>();
    private volatile boolean closed;
    private volatile Thread worker;

    // The active mapping method, remembered so close() can tear down exactly what it set up.
    private volatile NatPmpClient activePmp;
    private volatile UpnpIgd activeUpnp;
    private volatile UpnpIgd.Gateway activeGateway;

    private PortMapper(int port) {
        this.port = port;
    }

    /** Starts mapping {@code listenPort} in the background. Returns immediately. */
    public static PortMapper start(int listenPort) {
        PortMapper mapper = new PortMapper(listenPort);
        mapper.worker = Thread.ofVirtual().name("bt4j-portmap").start(mapper::run);
        return mapper;
    }

    /** The gateway's observed public IP address once a mapping succeeds, or empty until then (or if it never does). */
    public Optional<InetAddress> externalAddress() {
        return Optional.ofNullable(externalAddress.get());
    }

    private void run() {
        while (!closed) {
            boolean mapped = tryNatPmp() || tryUpnp();
            if (closed) {
                break;
            }
            long sleep = mapped ? REFRESH_MILLIS : 60_000; // renew a live lease early; retry a failed setup each minute
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private boolean tryNatPmp() {
        for (InetAddress gateway : NatPmpClient.gatewayGuesses()) {
            if (closed) {
                return false;
            }
            NatPmpClient pmp = new NatPmpClient(gateway);
            Optional<Integer> mapped = pmp.map(port, LEASE_SECONDS);
            if (mapped.isPresent()) {
                activePmp = pmp;
                activeUpnp = null;
                activeGateway = null;
                pmp.queryExternalAddress().ifPresent(externalAddress::set);
                LOG.log(System.Logger.Level.DEBUG, () -> "NAT-PMP mapped external port " + mapped.get()
                        + " via " + gateway.getHostAddress());
                return true;
            }
        }
        return false;
    }

    private boolean tryUpnp() {
        UpnpIgd upnp = new UpnpIgd();
        Optional<UpnpIgd.Gateway> discovered = upnp.discover();
        if (discovered.isEmpty()) {
            return false;
        }
        UpnpIgd.Gateway gateway = discovered.get();
        InetAddress gatewayHost = hostAddress(gateway);
        InetAddress local = gatewayHost == null ? null : UpnpIgd.localAddressTowards(gatewayHost).orElse(null);
        if (local == null) {
            return false;
        }
        if (!upnp.addPortMapping(gateway, port, local, LEASE_SECONDS, DESCRIPTION)) {
            return false;
        }
        activeUpnp = upnp;
        activeGateway = gateway;
        activePmp = null;
        upnp.externalAddress(gateway).ifPresent(externalAddress::set);
        LOG.log(System.Logger.Level.DEBUG, () -> "UPnP mapped external port " + port + " via " + gateway.controlUrl());
        return true;
    }

    private static InetAddress hostAddress(UpnpIgd.Gateway gateway) {
        try {
            return InetAddress.getByName(gateway.controlUrl().getHost());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
        // Remove whatever mapping is currently in place, so we do not leave a dangling forward on the router.
        NatPmpClient pmp = activePmp;
        if (pmp != null) {
            pmp.unmap(port);
        }
        UpnpIgd upnp = activeUpnp;
        UpnpIgd.Gateway gateway = activeGateway;
        if (upnp != null && gateway != null) {
            upnp.deletePortMapping(gateway, port);
        }
    }
}
