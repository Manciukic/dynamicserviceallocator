package net.floodlightcontroller.dynamicserviceallocator;

import org.restlet.Context;
import org.restlet.routing.Router;

import net.floodlightcontroller.restserver.RestletRoutable;

public class DynServAllocatorWebRoutable implements RestletRoutable {
	/**
	 * Create the Restletrouter and bind to the proper resources.
	 * 
	 * @return
	 */
	@Override
	public Router getRestlet(Context context) {
		Router router = new Router(context);

		// Subscribe a client
		router.attach("/client/subscribe", SubscribeClient.class);
		router.attach("/client/unsubscribe", UnsubscribeClient.class);
		router.attach("/server/subscribe", SubscribeServer.class);
		router.attach("/server/unsubscribe", UnsubscribeServer.class);
		return router;
	}

	@Override
	public String basePath() {
		// The root path for the resources
		return "/dsa";
	}
}