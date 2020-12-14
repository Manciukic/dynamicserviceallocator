package net.floodlightcontroller.dynamicserviceallocator;

import java.io.IOException;
import java.util.Map;

import org.restlet.data.Status;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Subscribes a client to the Dynamic Service Allocator.
 * Once subscribed, the client will be assigned a server to handle its 
 * requests.
 */
public class SubscribeClient extends ServerResource {

	@Post("json")
	public String subscribe(String fmJson) {
	    
        // Check if the payload is provided
        if(fmJson == null){
        	setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
            return new String("Empty payload");
        }
		
		// Parse the JSON input
		
		ObjectMapper mapper = new ObjectMapper();
		try {
			
			JsonNode root = mapper.readTree(fmJson);
			
			JsonNode addrNode = root.get("client_address");

			if (addrNode == null){
				setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return new String("No 'client_address' field provided");
			}
			
			// Get the client IP
			String client = addrNode.asText();
			
			IDynServAllocatorREST dsa = (IDynServAllocatorREST) getContext().getAttributes().get(IDynServAllocatorREST.class.getCanonicalName());
			dsa.subscribe(client);

			return new String("OK");

		} catch (IOException e) {
			e.printStackTrace();
			setStatus(Status.SERVER_ERROR_INTERNAL);
			return new String("Unknown error occurred");
		}
	}
}
