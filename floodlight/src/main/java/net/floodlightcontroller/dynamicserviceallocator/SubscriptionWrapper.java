package net.floodlightcontroller.dynamicserviceallocator;

import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import org.projectfloodlight.openflow.types.DatapathId;

/**
 * This class is used to represent a subscription of a specific client, which
 * has a paired server and a lease time.
 */
public class SubscriptionWrapper {

	private ServerDescriptor server;
	private final int leaseTime;
	private final Date expirationTime;
	private Set<DatapathId> attachedSwitches = new HashSet<>();

	/**
	 * Initialize a SubscriptionWrapper with a server and a lease time of validity
	 * (in seconds)
	 * 
	 * @param server    The server of the subscription
	 * @param leaseTime Number of seconds of validity of the subscription
	 */
	public SubscriptionWrapper(ServerDescriptor server, int leaseTime) {
		this.server = server;
		this.leaseTime = leaseTime;
		this.expirationTime = new Date(System.currentTimeMillis() + leaseTime * 1000L);
	}

	// this method will be package-private since other packages won't need to access
	// the server of a subscription
	ServerDescriptor getServer() {
		return server;
	}

	// this method will be package-private since other packages won't need to access
	// the server of a subscription
	void setServer(ServerDescriptor server) {
		this.server = server;
	}

	public int getLeaseTime() {
		return leaseTime;
	}

	public boolean isExpired() {
		Date now = new Date();
		return now.after(expirationTime);
	}

	public Date getExpirationTime() {
		return expirationTime;
	}

	public void addAttachedSwitch(DatapathId switchId) {
		attachedSwitches.add(switchId);
	}

	public Collection<DatapathId> getAttachedSwitches() {
		return attachedSwitches;
	}

}