/**
 * 
 */
package net.floodlightcontroller.dynamicserviceallocator;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.action.*;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.*;
import org.projectfloodlight.openflow.util.HexString;

import net.floodlightcontroller.core.*;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.forwarding.Forwarding;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.util.FlowModUtils;

/**
 * @author student
 *
 */
public class DynamicServiceAllocator implements IOFMessageListener, IFloodlightModule, IDynServAllocatorREST {
	protected IRestApiService restApiService;
	protected IFloodlightProviderService floodlightProvider;
	
	// IP and MAC address for our logical load balancer
	private final static IPv4Address SERVICE_ALLOCATOR_IP = IPv4Address.of("192.168.0.1");
	private final static MacAddress SERVICE_ALLOCATOR_MAC =  MacAddress.of("00:00:00:00:00:fe");
	
	// Rule timeouts
	private final static short IDLE_TIMEOUT = 10; // in seconds
	private final static short HARD_TIMEOUT = 20; // every 20 seconds drop the entry

	static String[] serverIP = {
		"192.168.0.3",
		"192.168.0.7"
	};
	
	static String[] serverMAC = {
		"00:00:00:00:00:03",
		"32:1b:51:e3:a0:95"
	};
		
	// Counter
	static int counter = 0;
	
	// Set of MacAddresses seen
	protected Set macAddresses;

	
	/* (non-Javadoc)
	 * @see net.floodlightcontroller.core.IOFMessageListener#receive(net.floodlightcontroller.core.IOFSwitch, org.projectfloodlight.openflow.protocol.OFMessage, net.floodlightcontroller.core.FloodlightContext)
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw, OFMessage msg,
			FloodlightContext cntx) {
			
			Ethernet eth = IFloodlightProviderService.bcStore.get(cntx,
                IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
			
			IPacket pkt = eth.getPayload();

			// Print the source MAC address
			/*Long sourceMACHash = Ethernet.toLong(eth.getSourceMACAddress().getBytes());
			System.out.printf("MAC Address: {%s} seen on switch: {%s}\n",
				HexString.toHexString(sourceMACHash),
				sw.getId());*/
			
			// Cast to Packet-In
			OFPacketIn pi = (OFPacketIn) msg;

	        // Dissect Packet included in Packet-In
			if (eth.isBroadcast() || eth.isMulticast()) {
				if (pkt instanceof ARP) {
					
					System.out.printf("Processing ARP request\n");
					
					ARP arpRequest = (ARP) eth.getPayload();
					
					if( arpRequest.getTargetProtocolAddress().compareTo(SERVICE_ALLOCATOR_IP) == 0 ){
					
						System.out.println("sono ennnntraoto");
						// Process ARP request
						handleARPRequest(sw, pi, cntx);
						
						// Interrupt the chain
						return Command.STOP;
					}
				}
			} else {
				if (pkt instanceof IPv4 || false) {
					
					System.out.println("Ho visto un pacchetto con questo IP di destinazione: " + ((IPv4) pkt).getDestinationAddress());
					
					if(((IPv4) pkt).getDestinationAddress().compareTo(SERVICE_ALLOCATOR_IP) == 0) {
					
						
						System.out.printf("Processing IPv4 packet\n");
						
						handleIPPacket(sw, pi, cntx);
							
						// Interrupt the chain
						return Command.STOP;
					}
				}
			}
			
			System.out.println("Eseguo forwarding!");
			
			// Continue the chain
			return Command.CONTINUE;

	}
	
	private void handleIPPacket(IOFSwitch sw, OFPacketIn pi,
			FloodlightContext cntx) {
		
		// Double check that the payload is IPv4
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx,
				IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		
		if (! (eth.getEtherType() == EthType.IPv4))
			return;
		
		System.out.println("e un pacchetto ipv4!");
		// Cast the IP packet
		IPv4 ipv4 = (IPv4) eth.getPayload();
		
		// Extract MAC, IP addresses and initialize a new Client Descriptor

		MacAddress clientMAC = eth.getSourceMACAddress();
		IPv4Address clientIP = ipv4.getSourceAddress();
		
		ClientDescriptor clientDes = new ClientDescriptor(clientIP);//, clientMAC);
		IPv4Address prova = IPv4Address.of("192.168.126.0");
		System.out.println("indirizzo: " + prova.toString());
		
		ServerDescriptor serverDes;
		
		// Check if client is subscribed if not, sends back an error to the client
		// Return the Descriptor of the server that will handle all requests received from that specific client
		if((serverDes = SubscriptionManager.getSubscriptionServer(clientDes.toString())) == null) {
			sendUnsubscribedError(sw, pi);
			return;
		}

		installRules(sw, clientIP, serverDes, pi);	
	}
	
	private List<OFAction> buildForwardFlowMod(OFFlowMod.Builder fmb, IOFSwitch sw, IPv4Address clientAddr, 
									ServerDescriptor serverDes) {
        fmb.setIdleTimeout(IDLE_TIMEOUT);
        fmb.setHardTimeout(HARD_TIMEOUT);
        fmb.setBufferId(OFBufferId.NO_BUFFER);
        fmb.setOutPort(OFPort.ANY);
        fmb.setCookie(U64.of(0));
        fmb.setPriority(FlowModUtils.PRIORITY_MAX);

        // Create the match structure  
        Match.Builder mb = sw.getOFFactory().buildMatch();
        mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
        	.setExact(MatchField.IPV4_DST, SERVICE_ALLOCATOR_IP)
        	.setExact(MatchField.ETH_DST, SERVICE_ALLOCATOR_MAC);
        
        
        // Create the actions (Change DST mac and IP addresses and set the out-port)
        ArrayList<OFAction> actionList = new ArrayList<OFAction>();
        
        if (!(fmb instanceof OFFlowDelete.Builder)){
        	OFActions actions = sw.getOFFactory().actions();
	        OFOxms oxms = sw.getOFFactory().oxms();
	
	        OFActionSetField setDlDst = actions.buildSetField()
	        	    .setField(
	        	        oxms.buildEthDst()
	        	        .setValue(serverDes.getMacAddress())
	        	        .build()
	        	    )
	        	    .build();
	        actionList.add(setDlDst);
	
	        OFActionSetField setNwDst = actions.buildSetField()
	        	    .setField(
	        	        oxms.buildIpv4Dst()
	        	        .setValue(serverDes.getIPAddress())
	        	        .build()
	        	    ).build();
	        actionList.add(setNwDst);
	        
	        OFActionOutput output = actions.buildOutput()
	        	    .setMaxLen(0xFFffFFff)
	        	    .setPort(OFPort.TABLE)
	        	    .build();
	        actionList.add(output);
	        
	        
	        fmb.setActions(actionList);
	        fmb.setMatch(mb.build());
        }
        
        return actionList;
	}
	
	private List<OFAction> buildBackwardFlowMod(OFFlowMod.Builder fmb, IOFSwitch sw, IPv4Address clientAddr, 
												ServerDescriptor serverDes) {
		// Create a flow table modification message to add a rule
 		fmb.setIdleTimeout(IDLE_TIMEOUT);
 		fmb.setHardTimeout(HARD_TIMEOUT);
 		fmb.setBufferId(OFBufferId.NO_BUFFER);
 		fmb.setOutPort(OFPort.ANY);
 		fmb.setCookie(U64.of(0));
 		fmb.setPriority(FlowModUtils.PRIORITY_MAX);

         Match.Builder mbRev = sw.getOFFactory().buildMatch();
         mbRev.setExact(MatchField.ETH_TYPE, EthType.IPv4)
	         .setExact(MatchField.IPV4_SRC, serverDes.getIPAddress())
	         .setExact(MatchField.ETH_SRC, serverDes.getMacAddress());
             fmbRev.setActions(actionListRev);
             fmbRev.setMatch(mbRev.build());
             
         ArrayList<OFAction> actionListRev = new ArrayList<OFAction>();
         
         if (!(fmb instanceof OFFlowDelete.Builder)){         
        	 OFActions actions = sw.getOFFactory().actions();
	         OFOxms oxms = sw.getOFFactory().oxms();
	         
	         OFActionSetField setDlDstRev = actions.buildSetField()
	         	    .setField(
	         	        oxms.buildEthSrc()
	         	        .setValue(SERVICE_ALLOCATOR_MAC)
	         	        .build()
	         	    )
	         	    .build();
	         actionListRev.add(setDlDstRev);
	
	         OFActionSetField setNwDstRev = actions.buildSetField()
	         	    .setField(
	         	        oxms.buildIpv4Src()
	         	        .setValue(SERVICE_ALLOCATOR_IP)
	         	        .build()
	         	    ).build();
	         actionListRev.add(setNwDstRev);
	         
	         OFActionOutput outputRev = actions.buildOutput()
	         	    .setMaxLen(0xFFffFFff)
	         	    .setPort(OFPort.TABLE)
	         	    .build();
	         actionListRev.add(outputRev);
	         
	         fmb.setActions(actionListRev);
	         fmb.setMatch(mbRev.build());
         }
         
         return actionListRev;
 	}
	
	private void installRules(IOFSwitch sw, IPv4Address clientAddr, ServerDescriptor serverDes, OFPacketIn pi) {
		
		String serverMac = serverDes.getMacAddress().toString();//serverMAC[0];
		String serverIp = serverDes.getIPAddress().toString();//serverIP[0];
		
		System.out.println(serverIp + "     " + serverMac);
		
		// Create a flow table modification message to add a rule
		
		OFFlowAdd.Builder fmb = sw.getOFFactory().buildFlowAdd();
		List<OFAction> pcktoutActions = buildForwardFlowMod(fmb, sw, clientAddr, serverDes);

        System.out.println("Install Forward route!");
        
        sw.write(fmb.build());
        
     // Reverse Rule to change the source address and mask the action of the controller
        
        OFFlowAdd.Builder fmbRev = sw.getOFFactory().buildFlowAdd();
		buildBackwardFlowMod(fmbRev, sw, clientAddr, serverDes);

        System.out.println("Install Backward route!");
        
        sw.write(fmbRev.build());

         // If we do not apply the same action to the packet we have received and we send it back the first packet will be lost
        
        if (pi != null) {
	 		// Create the Packet-Out and set basic data for it (buffer id and in port)
	 		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
	 		pob.setBufferId(pi.getBufferId());
	 		pob.setInPort(OFPort.ANY);
	     		
	 		// Assign the action
	 		pob.setActions(pcktoutActions);
			
			// Packet might be buffered in the switch or encapsulated in Packet-In 
			// If the packet is encapsulated in Packet-In sent it back
			if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
				// Packet-In buffer-id is none, the packet is encapsulated -> send it back
	            byte[] packetData = pi.getData();
	            pob.setData(packetData);
			} 

			sw.write(pob.build());
		} 					
	}
	
	private void handleARPRequest(IOFSwitch sw, OFPacketIn pi,
			FloodlightContext cntx) {

		// Double check that the payload is ARP
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx,
				IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		
		if (! (eth.getPayload() instanceof ARP))
			return;
		
		// Cast the ARP request
		ARP arpRequest = (ARP) eth.getPayload();
				
		// Generate ARP reply
		IPacket arpReply = new Ethernet()
			.setSourceMACAddress(SERVICE_ALLOCATOR_MAC)
			.setDestinationMACAddress(eth.getSourceMACAddress())
			.setEtherType(EthType.ARP)
			.setPriorityCode(eth.getPriorityCode())
			.setPayload(
				new ARP()
				.setHardwareType(ARP.HW_TYPE_ETHERNET)
				.setProtocolType(ARP.PROTO_TYPE_IP)
				.setHardwareAddressLength((byte) 6)
				.setProtocolAddressLength((byte) 4)
				.setOpCode(ARP.OP_REPLY)
				.setSenderHardwareAddress(SERVICE_ALLOCATOR_MAC) // Set my MAC address
				.setSenderProtocolAddress(SERVICE_ALLOCATOR_IP) // Set my IP address
				.setTargetHardwareAddress(arpRequest.getSenderHardwareAddress())
				.setTargetProtocolAddress(arpRequest.getSenderProtocolAddress()));
		
		// Create the Packet-Out and set basic data for it (buffer id and in port)
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		pob.setBufferId(OFBufferId.NO_BUFFER);
		pob.setInPort(OFPort.ANY);
		
		// Create action -> send the packet back from the source port
		OFActionOutput.Builder actionBuilder = sw.getOFFactory().actions().buildOutput();
		// The method to retrieve the InPort depends on the protocol version 
		OFPort inPort = pi.getMatch().get(MatchField.IN_PORT);
		actionBuilder.setPort(inPort); 
		
		// Assign the action
		pob.setActions(Collections.singletonList((OFAction) actionBuilder.build()));
		
		// Set the ARP reply as packet data 
		byte[] packetData = arpReply.serialize();
		pob.setData(packetData);
		
		System.out.printf("Sending out ARP reply\n");
		
		sw.write(pob.build());
		
	}
	
	public SubscriptionWrapper subscribe(String clientId) {
		return SubscriptionManager.subscribe(clientId);
	}
	
	public boolean unsubscribe(String clientId) {
		return SubscriptionManager.unsubscribe(clientId);
	}
	
	public void sendUnsubscribedError(IOFSwitch sw, OFPacketIn pi) {
		//TODO
	}
	
	/* (non-Javadoc)
	 * @see net.floodlightcontroller.core.IListener#getName()
	 */
	@Override
	public String getName() {
		return DynamicServiceAllocator.class.getSimpleName();
	}

	/* (non-Javadoc)
	 * @see net.floodlightcontroller.core.IListener#isCallbackOrderingPrereq(java.lang.Object, java.lang.String)
	 */
	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	/* (non-Javadoc)
	 * @see net.floodlightcontroller.core.IListener#isCallbackOrderingPostreq(java.lang.Object, java.lang.String)
	 */
	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	/* (non-Javadoc)
	 * @see net.floodlightcontroller.core.module.IFloodlightModule#getModuleServices()
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IDynServAllocatorREST.class);
		return l;
	}

	/* (non-Javadoc)
	 * @see net.floodlightcontroller.core.module.IFloodlightModule#getServiceImpls()
	 */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		m.put(IDynServAllocatorREST.class, this);
		return m;
	}
	 
	/* (non-Javadoc)
	 * @see net.floodlightcontroller.core.module.IFloodlightModule#getModuleDependencies()
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(IRestApiService.class);
	    return l;
	}

	/* (non-Javadoc)
	 * @see net.floodlightcontroller.core.module.IFloodlightModule#init(net.floodlightcontroller.core.module.FloodlightModuleContext)
	 */
	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		SubscriptionManager.init(50, true);
		restApiService = context.getServiceImpl(IRestApiService.class);
		// Create an empty MacAddresses set
	    macAddresses = new ConcurrentSkipListSet<Long>();
	}

	/* (non-Javadoc)
	 * @see net.floodlightcontroller.core.module.IFloodlightModule#startUp(net.floodlightcontroller.core.module.FloodlightModuleContext)
	 */
	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		restApiService.addRestletRoutable(new DynServAllocatorWebRoutable());
	}

}

