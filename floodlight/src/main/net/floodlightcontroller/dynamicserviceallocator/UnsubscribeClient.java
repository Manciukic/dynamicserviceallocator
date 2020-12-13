package net.floodlightcontroller.dynamicserviceallocator;

import java.io.IOException;
import java.util.Map;

import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.restlet.data.Status;

/**
 * Unsubscribes a client from the Dynamic Service Allocator.
 */
public class UnsubscribeClient extends ServerResource {

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
			
			if (dsa.unsubscribe(client)){
				return new String("OK");
			} else {
				setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return new String("Client was not subscribed");
			}

		} catch (IOException e) {
			e.printStackTrace();
			setStatus(Status.SERVER_ERROR_INTERNAL);
			return new String("Unknown error occurred");
		}
	}
}
