package net.floodlightcontroller.dynamicserviceallocator;

import org.projectfloodlight.openflow.types.IPv4Address;

public class ClientDescriptor extends HostDescriptor{

    /**
     * Initialize a client descriptor with its IP and MAC addresses.
     * @param ipAddress the IP address of the server.
     */
    public ClientDescriptor(IPv4Address ipAddress) {
        super(ipAddress);
    }

}
