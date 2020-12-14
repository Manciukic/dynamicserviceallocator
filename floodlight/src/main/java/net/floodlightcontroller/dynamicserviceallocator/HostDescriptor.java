package net.floodlightcontroller.dynamicserviceallocator;

import org.projectfloodlight.openflow.types.IPv4Address;

/**
 * This abstract class represents a generic host by its IP and MAC addresses.
 */
public abstract class HostDescriptor {

    private final IPv4Address ipAddress;

    /**
     * Initialize the descriptor with the host IP and MAC addresses.
     * @param ipAddress the IP address of the host.
     */
    public HostDescriptor(IPv4Address ipAddress) {
        this.ipAddress = ipAddress;
    }

    /**
     * Get the IP address of this host instance.
     * @return the IP address of this host.
     */
    public IPv4Address getIPAddress() {
        return ipAddress;
    }

    /**
     * Convert the descriptor in String format. Useful to generate unique IDs and debug.
     * @return the string converted descriptor.
     */
    @Override
    public String toString() {
       return getIPAddress().toString();
    }
}
