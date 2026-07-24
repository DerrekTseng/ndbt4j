package net.derrek.bt4j.nat;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UPnP Internet Gateway Device client: discovers a home router via SSDP and asks it, over SOAP, to forward an
 * external TCP port to this host. This is what most consumer routers speak, and unlike NAT-PMP it self-discovers
 * the gateway (no address guessing).
 *
 * The message building and response parsing are pure static methods (unit-tested); the instance side performs the
 * SSDP multicast, the device-description fetch, and the SOAP calls. All best-effort: no gateway, or one that
 * declines, yields an empty result rather than an exception.
 */
public final class UpnpIgd {

    private static final System.Logger LOG = System.getLogger(UpnpIgd.class.getName());

    static final String SSDP_HOST = "239.255.255.250";
    static final int SSDP_PORT = 1900;
    private static final int SSDP_TIMEOUT_MILLIS = 2000;
    private static final int HTTP_TIMEOUT_MILLIS = 4000;

    /** The two IGD connection service types that expose port mapping (IGDv1 wording; IGDv2 routers also answer these). */
    static final List<String> SERVICE_TYPES = List.of(
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1");

    /** A discovered gateway: the absolute SOAP control URL and the service type to invoke on it. */
    public record Gateway(URI controlUrl, String serviceType) {
    }

    // ---- SSDP ----

    /** Builds an SSDP M-SEARCH datagram for the given IGD service/device target. */
    static String mSearch(String searchTarget) {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + SSDP_HOST + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + searchTarget + "\r\n"
                + "\r\n";
    }

    /** Extracts the LOCATION header (the device-description URL) from an SSDP response; empty if absent. */
    static Optional<String> parseLocation(String ssdpResponse) {
        for (String line : ssdpResponse.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("LOCATION")) {
                String value = line.substring(colon + 1).trim();
                return value.isEmpty() ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the first WAN connection service in a device-description XML and returns its (serviceType, controlURL).
     * The controlURL is resolved against {@code baseUrl}. Empty when the description exposes no such service.
     */
    static Optional<Gateway> parseDeviceDescription(String xml, URI baseUrl) {
        // Each <service> block carries a <serviceType> and a <controlURL>; scan them in document order.
        Matcher service = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL).matcher(xml);
        while (service.find()) {
            String block = service.group(1);
            String type = tagValue(block, "serviceType");
            if (type == null || !SERVICE_TYPES.contains(type)) {
                continue;
            }
            String control = tagValue(block, "controlURL");
            if (control == null || control.isBlank()) {
                continue;
            }
            try {
                return Optional.of(new Gateway(baseUrl.resolve(control.trim()), type));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static String tagValue(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + "\\s*>(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    // ---- SOAP ----

    /** Builds a SOAP body for the given IGD action and ordered arguments. */
    static String soapBody(String serviceType, String action, List<String[]> args) {
        StringBuilder inner = new StringBuilder();
        for (String[] arg : args) {
            inner.append('<').append(arg[0]).append('>').append(xmlEscape(arg[1]))
                    .append("</").append(arg[0]).append('>');
        }
        return "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>"
                + "<u:" + action + " xmlns:u=\"" + serviceType + "\">" + inner + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
    }

    /** The SOAPAction header value for an action on a service. */
    static String soapAction(String serviceType, String action) {
        return "\"" + serviceType + "#" + action + "\"";
    }

    static String parseExternalIp(String soapResponse) {
        String ip = tagValue(soapResponse, "NewExternalIPAddress");
        return ip == null || ip.isBlank() ? null : ip;
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---- orchestration ----

    /** Discovers a port-mapping-capable gateway via SSDP. Empty if none answers in time. */
    public Optional<Gateway> discover() {
        for (String target : List.of(
                "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "urn:schemas-upnp-org:service:WANIPConnection:1",
                "ssdp:all")) {
            Optional<String> location = search(target);
            if (location.isPresent()) {
                Optional<Gateway> gateway = fetchGateway(location.get());
                if (gateway.isPresent()) {
                    return gateway;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> search(String target) {
        byte[] request = mSearch(target).getBytes(StandardCharsets.ISO_8859_1);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(SSDP_TIMEOUT_MILLIS);
            socket.send(new DatagramPacket(request, request.length, InetAddress.getByName(SSDP_HOST), SSDP_PORT));
            long deadline = System.nanoTime() + SSDP_TIMEOUT_MILLIS * 1_000_000L;
            byte[] buffer = new byte[2048];
            while (System.nanoTime() < deadline) {
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);
                String text = new String(response.getData(), response.getOffset(), response.getLength(), StandardCharsets.ISO_8859_1);
                Optional<String> location = parseLocation(text);
                if (location.isPresent()) {
                    return location;
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "SSDP search for " + target + " failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<Gateway> fetchGateway(String location) {
        try {
            URI base = URI.create(location);
            String xml = httpGet(base.toURL());
            return xml == null ? Optional.empty() : parseDeviceDescription(xml, base);
        } catch (IllegalArgumentException | java.net.MalformedURLException e) {
            return Optional.empty();
        }
    }

    /**
     * Adds a TCP port mapping (external {@code port} → this host's {@code port}).
     *
     * @param localAddress the internal client address the gateway should forward to
     * @return true if the gateway accepted the mapping
     */
    public boolean addPortMapping(Gateway gateway, int port, InetAddress localAddress, int leaseSeconds, String description) {
        String body = soapBody(gateway.serviceType(), "AddPortMapping", List.of(
                new String[]{"NewRemoteHost", ""},
                new String[]{"NewExternalPort", String.valueOf(port)},
                new String[]{"NewProtocol", "TCP"},
                new String[]{"NewInternalPort", String.valueOf(port)},
                new String[]{"NewInternalClient", localAddress.getHostAddress()},
                new String[]{"NewEnabled", "1"},
                new String[]{"NewPortMappingDescription", description},
                new String[]{"NewLeaseDuration", String.valueOf(leaseSeconds)}));
        return soap(gateway, "AddPortMapping", body) != null;
    }

    /** Removes the TCP mapping for the external {@code port}. Best-effort. */
    public void deletePortMapping(Gateway gateway, int port) {
        String body = soapBody(gateway.serviceType(), "DeletePortMapping", List.of(
                new String[]{"NewRemoteHost", ""},
                new String[]{"NewExternalPort", String.valueOf(port)},
                new String[]{"NewProtocol", "TCP"}));
        soap(gateway, "DeletePortMapping", body);
    }

    /** Queries the gateway's public IP address. */
    public Optional<InetAddress> externalAddress(Gateway gateway) {
        String body = soapBody(gateway.serviceType(), "GetExternalIPAddress", List.of());
        String response = soap(gateway, "GetExternalIPAddress", body);
        if (response == null) {
            return Optional.empty();
        }
        String ip = parseExternalIp(response);
        try {
            return ip == null ? Optional.empty() : Optional.of(InetAddress.getByName(ip));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private String soap(Gateway gateway, String action, String body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) gateway.controlUrl().toURL().openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
            conn.setReadTimeout(HTTP_TIMEOUT_MILLIS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPAction", soapAction(gateway.serviceType(), action));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(payload);
            int status = conn.getResponseCode();
            InputStream stream = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String response = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (status >= 400) {
                LOG.log(System.Logger.Level.DEBUG, () -> "UPnP " + action + " returned HTTP " + status);
                return null;
            }
            return response;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "UPnP " + action + " failed: " + e.getMessage());
            return null;
        }
    }

    private String httpGet(URL url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
            conn.setReadTimeout(HTTP_TIMEOUT_MILLIS);
            if (conn.getResponseCode() != 200) {
                return null;
            }
            return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** The local address the gateway should forward to (the source address used to reach {@code gatewayHost}). */
    public static Optional<InetAddress> localAddressTowards(InetAddress gatewayHost) {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(new InetSocketAddress(gatewayHost, SSDP_PORT));
            InetAddress local = probe.getLocalAddress();
            return local == null || local.isAnyLocalAddress() ? Optional.empty() : Optional.of(local);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
