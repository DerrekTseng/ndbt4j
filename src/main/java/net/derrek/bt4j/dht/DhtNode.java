package net.derrek.bt4j.dht;

import java.net.InetSocketAddress;

/** 路由表中的一個遠端 DHT 節點。 */
public record DhtNode(NodeId id, InetSocketAddress address) {
}
