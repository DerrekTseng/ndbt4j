package net.derrek.bt4j.nat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** UPnP-IGD message building and response parsing (no real network). */
class UpnpIgdTest {

    @Test
    void mSearchIsAWellFormedSsdpDiscover() {
        String msg = UpnpIgd.mSearch("urn:schemas-upnp-org:device:InternetGatewayDevice:1");
        assertTrue(msg.startsWith("M-SEARCH * HTTP/1.1\r\n"));
        assertTrue(msg.contains("MAN: \"ssdp:discover\""));
        assertTrue(msg.contains("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1"));
        assertTrue(msg.endsWith("\r\n\r\n"));
    }

    @Test
    void parsesLocationHeaderCaseInsensitively() {
        String ssdp = "HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\nlOcAtIoN: http://192.168.1.1:5000/desc.xml\r\n\r\n";
        assertEquals(Optional.of("http://192.168.1.1:5000/desc.xml"), UpnpIgd.parseLocation(ssdp));
        assertEquals(Optional.empty(), UpnpIgd.parseLocation("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"));
    }

    @Test
    void findsWanServiceControlUrlAndResolvesItRelative() {
        String xml = """
                <root><device>
                  <serviceList>
                    <service>
                      <serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
                      <controlURL>/ignored</controlURL>
                    </service>
                    <service>
                      <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
                      <controlURL>/ctl/IPConn</controlURL>
                    </service>
                  </serviceList>
                </device></root>
                """;
        Optional<UpnpIgd.Gateway> gateway = UpnpIgd.parseDeviceDescription(xml, URI.create("http://192.168.1.1:5000/desc.xml"));
        assertTrue(gateway.isPresent());
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", gateway.get().serviceType());
        assertEquals("http://192.168.1.1:5000/ctl/IPConn", gateway.get().controlUrl().toString());
    }

    @Test
    void deviceDescriptionWithoutWanServiceYieldsEmpty() {
        String xml = "<root><device><serviceList><service>"
                + "<serviceType>urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1</serviceType>"
                + "<controlURL>/x</controlURL></service></serviceList></device></root>";
        assertFalse(UpnpIgd.parseDeviceDescription(xml, URI.create("http://192.168.1.1/desc.xml")).isPresent());
    }

    @Test
    void soapBodyContainsActionServiceAndArguments() {
        String type = "urn:schemas-upnp-org:service:WANIPConnection:1";
        String body = UpnpIgd.soapBody(type, "AddPortMapping", List.of(
                new String[]{"NewExternalPort", "6881"},
                new String[]{"NewProtocol", "TCP"},
                new String[]{"NewInternalClient", "192.168.1.57"}));
        assertTrue(body.contains("<u:AddPortMapping xmlns:u=\"" + type + "\">"));
        assertTrue(body.contains("<NewExternalPort>6881</NewExternalPort>"));
        assertTrue(body.contains("<NewProtocol>TCP</NewProtocol>"));
        assertTrue(body.contains("<NewInternalClient>192.168.1.57</NewInternalClient>"));
        assertTrue(body.contains("</s:Envelope>"));
    }

    @Test
    void soapActionHeaderIsQuotedTypeHashAction() {
        assertEquals("\"urn:schemas-upnp-org:service:WANIPConnection:1#AddPortMapping\"",
                UpnpIgd.soapAction("urn:schemas-upnp-org:service:WANIPConnection:1", "AddPortMapping"));
    }

    @Test
    void parsesExternalIpFromSoapResponse() {
        String resp = "<?xml version=\"1.0\"?><s:Envelope><s:Body>"
                + "<u:GetExternalIPAddressResponse><NewExternalIPAddress>203.0.113.9</NewExternalIPAddress>"
                + "</u:GetExternalIPAddressResponse></s:Body></s:Envelope>";
        assertEquals("203.0.113.9", UpnpIgd.parseExternalIp(resp));
        assertEquals(null, UpnpIgd.parseExternalIp("<s:Envelope></s:Envelope>"));
    }

    @Test
    void soapArgumentsAreXmlEscaped() {
        String body = UpnpIgd.soapBody("svc", "X", List.<String[]>of(new String[]{"Desc", "a & b <c>"}));
        assertTrue(body.contains("<Desc>a &amp; b &lt;c&gt;</Desc>"));
    }
}
