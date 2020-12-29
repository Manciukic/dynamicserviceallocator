package net.floodlightcontroller.dynamicserviceallocator;

import java.sql.Timestamp;

/**
 * This class is used to represent a subscription of a specific client, which has a paired server
 * and a lease time.
 */
public class SubscriptionWrapper {

    private ServerDescriptor server;
    private final int leaseTime;
    private final Timestamp expirationTime;

    /**
     * Initialize a SubscriptionWrapper with a server and a lease time of validity (in seconds)
     * @param server The server of the subscription
     * @param leaseTime Number of seconds of validity of the subscription
     */
    public SubscriptionWrapper(ServerDescriptor server, int leaseTime) {
        this.server = server;
        this.leaseTime = leaseTime;
        this.expirationTime = new Timestamp(System.currentTimeMillis() + leaseTime*1000L);
    }

    //this method will be package-private since other packages won't need to access the server of a subscription
    ServerDescriptor getServer() {
        return server;
    }

    //this method will be package-private since other packages won't need to access the server of a subscription
    void setServer(ServerDescriptor server) {
        this.server = server;
    }

    public int getLeaseTime() {
        return leaseTime;
    }

    public boolean isExpired() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return now.after(expirationTime);
    }

    public Timestamp getExpirationTime() {
        return expirationTime;
    }



}