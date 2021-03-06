package net.floodlightcontroller.dynamicserviceallocator;

import net.floodlightcontroller.core.module.IFloodlightService;

/**
 * Service interface for the module This interface will be use to interact with
 * other modules. Export here all the methods of the class that are likely used
 * by other modules.
 */
public interface IDynServAllocatorREST extends IFloodlightService {
	public SubscriptionWrapper subscribe(String client);

	public boolean unsubscribe(String client);

	public boolean addServer(ServerDescriptor newServer);

	public boolean removeServer(ServerDescriptor oldServer);
}
