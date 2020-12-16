package net.floodlightcontroller.dynamicserviceallocator;

import java.util.concurrent.atomic.AtomicInteger;

import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;

/**
 * This class represents a descriptor of a physical server. Thus, it indicates its IP and MAC addresses.
 */
public class ServerDescriptor extends HostDescriptor{

    /**
     * In a server descriptor we also need its mac address
     */
    private final MacAddress macAddress;

    /**
     * This attribute is redundant. It is used to optimize the search of the least busy server
     * and it must be kept updated manually using subscribe() and unsubscribe() methods.
     */
    private final AtomicInteger subscribers = new AtomicInteger(0);

    private final int port;

    /**
     * Initialize a server descriptor with its IP, MAC addresses and port.
     * @param ipAddress the IP address of the server.
     * @param macAddress the MACAddress of the server.
     * @param port the port on which the server is listening
     */
    public ServerDescriptor(IPv4Address ipAddress, MacAddress macAddress, int port) {
       super(ipAddress);
       this.macAddress = macAddress;
       this.port = port;
    }

    /**
     * Keep count of subscribers count by signaling a new subscription to this server.
     */
    public void subscribe() {
        subscribers.incrementAndGet();
    }

    /**
     * Keep count of subscribers count by signaling an unsubscription to this server.
     */
    public void unsubscribe() {
       subscribers.decrementAndGet();
    }

    /**
     * Get the number of currently subscribed clients.
     * @return the number of subscribed clients.
     */
    public int getSubscribers() {
        return subscribers.get();
    }

    /**
     * Get the port on which the server is listening.
     * @return the server port.
     */
    public int getPort() {
        return port;
    }

    /**
     * Get the server MAC address.
     * @return the server MAC address
     */
    public MacAddress getMacAddress() {
        return macAddress;
    }

    /**
     * Check if two descriptors represent the same server.
     * @param second the second descriptor
     * @return true if they are equal.
     */
    public boolean equals(ServerDescriptor second) {
        return second.getMacAddress().equals(getMacAddress()) ||
                second.getIPAddress().equals(getIPAddress());
    }

}
