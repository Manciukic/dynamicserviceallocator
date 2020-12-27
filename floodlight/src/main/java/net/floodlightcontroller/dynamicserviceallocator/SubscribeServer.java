package net.floodlightcontroller.dynamicserviceallocator;

import java.io.IOException;
import java.util.Map;

import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
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
public class SubscribeServer extends ServerResource {

	@Post("json")
	public String subscribe(String fmJson) {
	    
        // Check if the payload is provided
        if(fmJson == null){
        	setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
            return new String("Empty payload");
        }
		
		// Parse the JSON input
		System.out.println("ci provo");
		ObjectMapper mapper = new ObjectMapper();
		try {
			
			JsonNode root = mapper.readTree(fmJson);
			
			JsonNode addrNode = root.get("server_address");
			JsonNode macaddrNode = root.get("server_macaddress");
			JsonNode port = root.get("service_port");

			if (addrNode == null || macaddrNode == null){
				setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return new String("No 'server_address', 'server_macaddress' or 'service_port' fields provided");
			}
			
			// Get Server IP
			IPv4Address serverIP = IPv4Address.of(addrNode.asText());
			MacAddress serverMAC = MacAddress.of(macaddrNode.asText());
			int servicePort = port.asInt();
			ServerDescriptor servDes = new ServerDescriptor(serverIP, serverMAC, servicePort);
			
			
			IDynServAllocatorREST dsa = (IDynServAllocatorREST) getContext().getAttributes().get(IDynServAllocatorREST.class.getCanonicalName());
			dsa.addServer(servDes);

			return new String("OK");

		} catch (IOException e) {
			e.printStackTrace();
			setStatus(Status.SERVER_ERROR_INTERNAL);
			return new String("Unknown error occurred");
		}
	}
}