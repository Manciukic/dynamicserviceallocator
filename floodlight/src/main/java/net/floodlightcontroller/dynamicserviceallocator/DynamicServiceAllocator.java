/**
 *
 */
package net.floodlightcontroller.dynamicserviceallocator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowDelete;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.dhcpserver.DHCPBinding;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.ICMP;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.util.FlowModUtils;

public class DynamicServiceAllocator implements IOFMessageListener, IFloodlightModule, IDynServAllocatorREST {
	protected static final Logger log = LoggerFactory.getLogger(DynamicServiceAllocator.class);

	protected IRestApiService restApiService;
	protected IFloodlightProviderService floodlightProvider;
	protected IOFSwitchService switchService;

	public static final long APP_ID = 97L;

	// IP and MAC address for our logical load balancer
	private final static IPv4Address SERVICE_ALLOCATOR_IP = IPv4Address.of("9.9.9.9");
	private final static MacAddress SERVICE_ALLOCATOR_MAC = MacAddress.of("00:00:00:00:00:fe");

	// Rule timeouts
	private final static short IDLE_TIMEOUT = 10; // drop entries if not used for 10 seconds
	// hard timeout will be set depending on expiration

	/*
	 * (non-Javadoc)
	 * 
	 * @see net.floodlightcontroller.core.IOFMessageListener#receive(net.
	 * floodlightcontroller.core.IOFSwitch,
	 * org.projectfloodlight.openflow.protocol.OFMessage,
	 * net.floodlightcontroller.core.FloodlightContext)
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw, OFMessage msg,
			FloodlightContext cntx) {

		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		IPacket pkt = eth.getPayload();

		// Cast to Packet-In
		OFPacketIn pi = (OFPacketIn) msg;

		// Dissect Packet included in Packet-In
		if (eth.isBroadcast() || eth.isMulticast()) {
			if (pkt instanceof ARP) {

				log.debug("Seen ARP packet");

				ARP arpRequest = (ARP) eth.getPayload();

				if (arpRequest.getTargetProtocolAddress().compareTo(SERVICE_ALLOCATOR_IP) == 0) {

					log.debug("Need to handle this ARP");
					// Process ARP request
					handleARPRequest(sw, pi, cntx);

					// Interrupt the chain
					return Command.STOP;
				}
			}
		} else {
			if (pkt instanceof IPv4 || false) {

				log.debug(
						"Ho visto un pacchetto con questo IP di destinazione: " + ((IPv4) pkt).getDestinationAddress());

				if (((IPv4) pkt).getDestinationAddress().compareTo(SERVICE_ALLOCATOR_IP) == 0) {

					log.debug("Processing IPv4 packet");

					handleIPPacket(sw, pi, cntx);

					// Interrupt the chain
					return Command.STOP;
				}
			}
		}

		// Continue the chain
		return Command.CONTINUE;

	}

	/**
	 * Handle IP packet that generated the Packet IN: assign server to client and
	 * manage virtual IP.
	 *
	 * @param sw   switch that generated the packet IN
	 * @param pi   generated packet IN
	 * @param cntx
	 */
	private void handleIPPacket(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {

		// Double check that the payload is IPv4
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		if (!(eth.getEtherType() == EthType.IPv4))
			return;

		// Cast the IP packet
		IPv4 ipv4 = (IPv4) eth.getPayload();

		// Extract MAC, IP addresses and initialize a new Client Descriptor

		MacAddress clientMAC = eth.getSourceMACAddress();
		IPv4Address clientIP = ipv4.getSourceAddress();

		ClientDescriptor clientDes = new ClientDescriptor(clientIP);// , clientMAC);

		SubscriptionWrapper sub;

		// Check if client is subscribed if not, sends back an error to the client
		// Return the Descriptor of the server that will handle all requests received
		// from that specific client
		if ((sub = SubscriptionManager.getSubscription(clientDes.toString())) == null) {
			sendUnsubscribedError(sw, pi, cntx);
			return;
		}

		sub.addAttachedSwitch(sw.getId());
		ServerDescriptor serverDes = sub.getServer();

		// Create a flow table modification message to add a rule
		OFFlowAdd.Builder fmb = sw.getOFFactory().buildFlowAdd();

		int hardTimeout = (int) Math.ceil((sub.getExpirationTime().getTime() - (new Date()).getTime()) / 1000.0);
		int idleTimeout = Math.min(hardTimeout, IDLE_TIMEOUT);

		fmb.setIdleTimeout(idleTimeout);
		fmb.setHardTimeout(hardTimeout);
		fmb.setBufferId(OFBufferId.NO_BUFFER);
		fmb.setCookie(AppCookie.makeCookie(APP_ID, clientIP.getInt()));
		fmb.setPriority(FlowModUtils.PRIORITY_MAX);

		// Create the match structure
		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4).setExact(MatchField.IPV4_DST, SERVICE_ALLOCATOR_IP)
				.setExact(MatchField.IPV4_SRC, clientIP);

		OFActions actions = sw.getOFFactory().actions();
		// Create the actions (Change DST mac and IP addresses and set the out-port)
		ArrayList<OFAction> actionList = new ArrayList<OFAction>();

		OFOxms oxms = sw.getOFFactory().oxms();

		OFActionSetField setDlDst = actions.buildSetField()
				.setField(oxms.buildEthDst().setValue(serverDes.getMacAddress()).build()).build();
		actionList.add(setDlDst);

		OFActionSetField setNwDst = actions.buildSetField()
				.setField(oxms.buildIpv4Dst().setValue(serverDes.getIPAddress()).build()).build();
		actionList.add(setNwDst);

		OFActionOutput output = actions.buildOutput().setMaxLen(0xFFffFFff).setPort(OFPort.TABLE).build();
		actionList.add(output);

		fmb.setActions(actionList);
		fmb.setMatch(mb.build());

		log.debug("Installed Forward route!");

		sw.write(fmb.build());

		// Reverse Rule to change the source address and mask the action of the
		// controller

		// Create a flow table modification message to add a rule
		OFFlowAdd.Builder fmbRev = sw.getOFFactory().buildFlowAdd();

		fmbRev.setIdleTimeout(idleTimeout);
		fmbRev.setHardTimeout(hardTimeout);
		fmbRev.setBufferId(OFBufferId.NO_BUFFER);
		fmbRev.setCookie(AppCookie.makeCookie(APP_ID, clientIP.getInt()));
		fmbRev.setPriority(FlowModUtils.PRIORITY_MAX);

		Match.Builder mbRev = sw.getOFFactory().buildMatch();
		mbRev.setExact(MatchField.ETH_TYPE, EthType.IPv4).setExact(MatchField.IPV4_SRC, serverDes.getIPAddress())
				.setExact(MatchField.ETH_SRC, serverDes.getMacAddress()).setExact(MatchField.IPV4_DST, clientIP);

		ArrayList<OFAction> actionListRev = new ArrayList<OFAction>();

		OFActionSetField setDlDstRev = actions.buildSetField()
				.setField(oxms.buildEthSrc().setValue(SERVICE_ALLOCATOR_MAC).build()).build();
		actionListRev.add(setDlDstRev);

		OFActionSetField setNwDstRev = actions.buildSetField()
				.setField(oxms.buildIpv4Src().setValue(SERVICE_ALLOCATOR_IP).build()).build();
		actionListRev.add(setNwDstRev);

		OFActionOutput outputRev = actions.buildOutput().setMaxLen(0xFFffFFff).setPort(OFPort.TABLE).build();
		actionListRev.add(outputRev);

		fmbRev.setActions(actionListRev);
		fmbRev.setMatch(mbRev.build());

		sw.write(fmbRev.build());

		// If we do not apply the same action to the packet we have received and we send
		// it back the first packet will be lost

		// Create the Packet-Out and set basic data for it (buffer id and in port)
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		pob.setBufferId(pi.getBufferId());
		pob.setInPort(OFPort.ANY);

		// Assign the action
		pob.setActions(actionList);

		// Packet might be buffered in the switch or encapsulated in Packet-In
		// If the packet is encapsulated in Packet-In sent it back
		if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
			// Packet-In buffer-id is none, the packet is encapsulated -> send it back
			byte[] packetData = pi.getData();
			pob.setData(packetData);
		}

		log.debug("Installed Reverse route!");

		sw.write(pob.build());
	}

	/**
	 * Delete all flows related to clientAddr on given switch.
	 * 
	 * @param sw   switch that generated the packet IN
	 * @param pi   generated packet IN
	 * @param cntx
	 */
	private void deleteIPFlows(IOFSwitch sw, IPv4Address clientAddr) {

		// Create a flow table modification message to delete all rules with given cookie

		OFFlowDelete.Builder fmb = sw.getOFFactory().buildFlowDelete();
		fmb.setCookie(AppCookie.makeCookie(APP_ID, clientAddr.getInt()));
		fmb.setCookieMask(U64.of(0xffffffffffffffffL));

		log.debug("Uninstalled routes!");

		sw.write(fmb.build());
	}

	/**
	 * Handle ARP requests to the Virtual interface.
	 * 
	 * @param sw   switch that generated the packet IN
	 * @param pi   generated packet IN
	 * @param cntx
	 */
	private void handleARPRequest(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {

		// Double check that the payload is ARP
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		if (!(eth.getPayload() instanceof ARP))
			return;

		// Cast the ARP request
		ARP arpRequest = (ARP) eth.getPayload();

		// Generate ARP reply
		IPacket arpReply = new Ethernet().setSourceMACAddress(SERVICE_ALLOCATOR_MAC)
				.setDestinationMACAddress(eth.getSourceMACAddress()).setEtherType(EthType.ARP)
				.setPriorityCode(eth.getPriorityCode())
				.setPayload(new ARP().setHardwareType(ARP.HW_TYPE_ETHERNET).setProtocolType(ARP.PROTO_TYPE_IP)
						.setHardwareAddressLength((byte) 6).setProtocolAddressLength((byte) 4).setOpCode(ARP.OP_REPLY)
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

		log.debug("Sending out ARP reply");

		sw.write(pob.build());

	}

	public SubscriptionWrapper subscribe(String clientId) {
		return SubscriptionManager.subscribe(clientId);
	}

	public boolean unsubscribe(String clientId) {
		SubscriptionWrapper subwrap = SubscriptionManager.unsubscribe(clientId);

		if (subwrap == null)
			return false;

		IPv4Address clientAddr = IPv4Address.of(clientId);

		for (DatapathId swId : subwrap.getAttachedSwitches()) {
			IOFSwitch sw = switchService.getActiveSwitch(swId);
			if (sw == null)
				continue;
			deleteIPFlows(sw, clientAddr);
		}

		return true;
	}

	public void sendUnsubscribedError(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {

		log.info("A client just tried to get the service without subscribing!");

		// Double check that the payload is IPV4
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		if (!(eth.getPayload() instanceof IPv4))
			return;

		IPv4 ip_payload = (IPv4) eth.getPayload();

		// Generate ICMP destination unreachable reply
		IPacket icmp_reply = new Ethernet().setSourceMACAddress(SERVICE_ALLOCATOR_MAC)
				.setDestinationMACAddress(eth.getSourceMACAddress()).setEtherType(EthType.IPv4)
				.setPriorityCode(eth.getPriorityCode())
				.setPayload(new IPv4().setSourceAddress(SERVICE_ALLOCATOR_IP)
						.setDestinationAddress(ip_payload.getSourceAddress()).setProtocol(IpProtocol.ICMP)
						.setTtl((byte) 64)
						.setPayload(new ICMP().setIcmpType(ICMP.DESTINATION_UNREACHABLE).setIcmpCode((byte) 1) // host
																												// unreachable
								/*
								 * ICMP Destination Unreachable must have as payload the full IPv4 header of the
								 * original packet plus at least the first 64 bits of its payload
								 */
								.setPayload(ip_payload)));

		// build pkt out
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		pob.setBufferId(OFBufferId.NO_BUFFER);
		pob.setInPort(OFPort.ANY);

		// set output action
		OFActionOutput.Builder actionBuilder = sw.getOFFactory().actions().buildOutput();
		OFPort inPort = pi.getMatch().get(MatchField.IN_PORT);
		actionBuilder.setPort(inPort);
		pob.setActions(Collections.singletonList((OFAction) actionBuilder.build()));

		// set pkt data
		pob.setData(icmp_reply.serialize());

		sw.write(pob.build());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see net.floodlightcontroller.core.IListener#getName()
	 */
	@Override
	public String getName() {
		return DynamicServiceAllocator.class.getSimpleName();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * net.floodlightcontroller.core.IListener#isCallbackOrderingPrereq(java.lang.
	 * Object, java.lang.String)
	 */
	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * net.floodlightcontroller.core.IListener#isCallbackOrderingPostreq(java.lang.
	 * Object, java.lang.String)
	 */
	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * net.floodlightcontroller.core.module.IFloodlightModule#getModuleServices()
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IDynServAllocatorREST.class);
		return l;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see net.floodlightcontroller.core.module.IFloodlightModule#getServiceImpls()
	 */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		m.put(IDynServAllocatorREST.class, this);
		return m;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * net.floodlightcontroller.core.module.IFloodlightModule#getModuleDependencies(
	 * )
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(IRestApiService.class);
		l.add(IOFSwitchService.class);
		return l;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see net.floodlightcontroller.core.module.IFloodlightModule#init(net.
	 * floodlightcontroller.core.module.FloodlightModuleContext)
	 */
	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		SubscriptionManager.init(50, true);
		restApiService = context.getServiceImpl(IRestApiService.class);
		// Create an empty MacAddresses set
		switchService = context.getServiceImpl(IOFSwitchService.class);

		AppCookie.registerApp(APP_ID, DynamicServiceAllocator.class.getSimpleName());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see net.floodlightcontroller.core.module.IFloodlightModule#startUp(net.
	 * floodlightcontroller.core.module.FloodlightModuleContext)
	 */
	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		restApiService.addRestletRoutable(new DynServAllocatorWebRoutable());
	}

	@Override
	public boolean addServer(ServerDescriptor newServer) {
		return SubscriptionManager.addServer(newServer);
	}

	@Override
	public boolean removeServer(ServerDescriptor oldServer) {
		Collection<Map.Entry<String, SubscriptionWrapper>> affectedSubs = SubscriptionManager.removeServer(oldServer);

		if (affectedSubs == null)
			return false;

		for (Map.Entry<String, SubscriptionWrapper> sub : affectedSubs) {
			IPv4Address clientAddr = IPv4Address.of(sub.getKey());

			for (DatapathId swId : sub.getValue().getAttachedSwitches()) {
				IOFSwitch sw = switchService.getActiveSwitch(swId);
				if (sw == null)
					continue;
				deleteIPFlows(sw, clientAddr);
			}
		}

		return true;
	}

}
