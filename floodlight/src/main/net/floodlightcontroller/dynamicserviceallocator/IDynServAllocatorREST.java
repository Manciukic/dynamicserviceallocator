package net.floodlightcontroller.dynamicserviceallocator;

import java.util.Map;

import net.floodlightcontroller.dynamicserviceallocator.ClientDescriptor;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.SwitchMessagePair;
import net.floodlightcontroller.util.ConcurrentCircularBuffer;

/**
 * Service interface for the module
 * This interface will be use to interact with other modules. Export here all 
 * the methods of the class that are likely used by other modules.
 */
public interface IDynServAllocatorREST extends IFloodlightService {
	public SubscriptionWrapper subscribe(String client);
	public boolean unsubscribe(String client);
}
