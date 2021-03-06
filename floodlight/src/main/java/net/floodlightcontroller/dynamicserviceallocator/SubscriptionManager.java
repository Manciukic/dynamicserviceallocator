package net.floodlightcontroller.dynamicserviceallocator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.projectfloodlight.openflow.types.DatapathId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements a manager that provides a subscription service to the
 * other modules. In particular it accepts clients subscriptions and
 * automatically associates them to the least busy server. It also handles
 * unsubscription and subscription timeout.
 */
public class SubscriptionManager {
	
	protected static final Logger log = LoggerFactory.getLogger(SubscriptionManager.class);

	private static final List<ServerDescriptor> servers = new ArrayList<>();

	/**
	 * Parameter indicating the default lease time (seconds) for a subscription
	 */
	private static final int defaultLeaseTime = 90;

	/**
	 * This attribute maps a client identifier (obtained using toString() method of
	 * a ClientDescriptor) to the server it is subscribed to. It makes the search of
	 * the paired server extremely efficient. It has to be concurrent because
	 * expired subscriptions are removed by a parallel task (launchCleansingTask())
	 */
	private static final Map<String, SubscriptionWrapper> subscriptions = new ConcurrentHashMap<>();

	/**
	 * If set in init() method, the service will run in verbose mode (useful for
	 * debug purposes)
	 */
	private static boolean verboseMode = false;

	/**
	 * This attribute keeps a reference to the scheduler of the periodic
	 * subscription cleansing task
	 */
	private static final ScheduledExecutorService periodicExecutor = Executors.newScheduledThreadPool(1);;

	/**
	 * Run a set of operations to be performed in the initialization phase.
	 * 
	 * @param cleansingPeriod the period in seconds to schedule the task that
	 *                        deletes expired subscriptions
	 * @param verbose         if true the service will run in verbose mode (useful
	 *                        for debug)
	 */
	public static void init(int cleansingPeriod, boolean verbose) {

		verboseMode = verbose;

		/*
		 * //Uncomment this to use pre-set servers IPv4Address[] ips = {
		 * IPv4Address.of("192.168.0.3"), IPv4Address.of("192.168.0.8") }; MacAddress[]
		 * macs = { MacAddress.of("00:00:00:00:00:03"),
		 * MacAddress.of("00:00:00:00:00:08") };
		 * 
		 * addServer(new ServerDescriptor(ips[0], macs[0], 80)); addServer(new
		 * ServerDescriptor(ips[1], macs[1], 80));
		 * 
		 */

		if (verboseMode) {
			log.info("Subscription Service has started. Initial servers are ->");
			printServerState();
		}

		initPeriodicCleansingTask(cleansingPeriod);
		if (verboseMode) {
			log.info("Initialized cleansing scheduler with period = " + cleansingPeriod + " seconds");
		}
	}

	/**
	 * Time at which the last expired subscriptions check has been performed
	 */
	private static final Date lastCleansingTime = new Date();

	/**
	 * Subscribe a client to the least busy server.
	 * 
	 * @param clientID the string identifier of a client
	 * @return An object containing the server to which the client has been
	 *         subscribed and the lease duration
	 */
	public static SubscriptionWrapper subscribe(String clientID) {
		if (isSubscribed(clientID))
			unsubscribe(clientID);

		ServerDescriptor chosenOne = getLeastBusy();
		// Probably no servers are available
		if (chosenOne == null) {
			return null;
		}
		chosenOne.subscribe();
		log.info("aggiungo client");
		SubscriptionWrapper sub = new SubscriptionWrapper(chosenOne, defaultLeaseTime);
		subscriptions.put(clientID, sub);

		if (verboseMode) {
			log.debug("A client has been subscribed ->\n"
					+ "   Client: " + clientID + "\n"
					+ "   Assigned Server: " + chosenOne.toString()  + "\n"
					+ "   Lease time: " + sub.getLeaseTime() + " seconds");
		}

		return sub;
	}

	/**
	 * Unsubscribe a client from the service.
	 * 
	 * @param clientID the client to be unsubscribed.
	 */
	public static SubscriptionWrapper unsubscribe(String clientID) {
		if (subscriptions.get(clientID) == null)
			return null;
		SubscriptionWrapper removedSub = subscriptions.remove(clientID);
		ServerDescriptor chosenOne = removedSub.getServer();
		chosenOne.unsubscribe();

		if (verboseMode) {
			log.debug("A client has been unsubscribed ->\n"
					+ "   Client: " + clientID);
		}
		return removedSub;
	}

	/**
	 * Check whether a client is subscribed to the service or not.
	 * 
	 * @param clientID The client to check.
	 * @return True if the client is correctly subscribed.
	 */
	public static boolean isSubscribed(String clientID) {
		return subscriptions.get(clientID) != null;
	}

	public static Collection<DatapathId> getAttachedSwitches(String clientID) {
		if (subscriptions.get(clientID) != null)
			return subscriptions.get(clientID).getAttachedSwitches();
		else
			return null;
	}

	/**
	 * Get the server which a client is subscribed to.
	 * 
	 * @param clientID the subscribed client whose server is returned.
	 * @return The server paired with the specified client. Return null if the
	 *         client is not subscribed.
	 */
	public static SubscriptionWrapper getSubscription(String clientID) {
		SubscriptionWrapper sub = subscriptions.get(clientID);
		if (sub != null && sub.getExpirationTime().after(new Date())) {
			return sub;
		} else {
			return null;
		}
	}

	/**
	 * Launch a parallel task to delete expired subscriptions (on demand version)
	 */
	public static void launchCleansingTask() {

		if (verboseMode)
			log.debug("SubscriptionManager is removing expired subscriptions (on demand)...");

		// We use a default executor service
		ExecutorService executorService = Executors.newFixedThreadPool(1);

		Future<?> result = executorService.submit(SubscriptionManager::cleanSubscriptions);

		try {

			result.get();
			executorService.shutdown();

			// no need to wait for termination, since the only task has already been
			// completed.
			// executorService.awaitTermination(1, TimeUnit.MINUTES);

		} catch (Exception e) {
			if (e instanceof InterruptedException)
				Thread.currentThread().interrupt();
			else
				e.printStackTrace();
		}

		lastCleansingTime.setTime(System.currentTimeMillis());

	}

	/**
	 * Initialize and start up the executor service that schedules the cleansing
	 * task with a certain period
	 * 
	 * @param period the period in seconds to schedule the cleansing of expired
	 *               subscriptions
	 */
	private static void initPeriodicCleansingTask(int period) {

		if (period <= 0)
			return;

		if (verboseMode)
			log.debug("Initializing the periodic subscription cleansing execution...");

		Runnable cleansingRunnable = () -> {
			if (verboseMode)
				log.debug("SubscriptionManager is removing expired subscriptions (periodic)...");
			cleanSubscriptions();
			lastCleansingTime.setTime(System.currentTimeMillis());
		};

		periodicExecutor.scheduleAtFixedRate(cleansingRunnable, period, period, TimeUnit.SECONDS);

	}

	/**
	 * Utility function used as the body of cleansing task
	 */
	private static void cleanSubscriptions() {
		subscriptions.entrySet().removeIf((entry) -> {
			boolean ret = entry.getValue().isExpired();
			if (ret) {
				// since this task is parallel, the following line is why
				// subscribers attribute in ServerDescriptor is atomic
				entry.getValue().getServer().unsubscribe();
				if (verboseMode) {
					log.debug("Expired subscription removed:\n"
							+ "   Client: " + entry.getKey() + "\n"
							+ "   Server: " + entry.getValue().getServer().toString());
				}
			}
			return ret;
		});
	}

	/**
	 * Retrieve the server with the lowest load and return it.
	 * 
	 * @return A descriptor of the lowest load server
	 */
	private static ServerDescriptor getLeastBusy() {

		Optional<ServerDescriptor> temp = servers.stream()
				.min(Comparator.comparingInt(ServerDescriptor::getSubscribers));
		return temp.orElse(null);

	}

	/**
	 * Print a readable snapshot of all subscriptions. It can be used for debugging.
	 */
	public static void printSnapshot() {
		int i = 0;
		StringBuilder builder = new StringBuilder();
		builder.append("Subscriptions list ->\n");
		for (Map.Entry<String, SubscriptionWrapper> entry : subscriptions.entrySet()) {
			builder.append("Subscription ")
				.append(i)
				.append(":\n   Client: ")
				.append(entry.getKey())
				.append("\n   Server: ")
				.append(entry.getValue().getServer().toString())
				.append("\n   Lease Time: ")
				.append(entry.getValue().getLeaseTime())
				.append("\n   Expiration Time: ")
				.append(entry.getValue().getExpirationTime().toString())
				.append("\n\n");
			++i;
		}
		log.debug(builder.toString());
	}

	/**
	 * Print a readable description of the service servers with their current load.
	 * It can be used for debugging.
	 */
	public static void printServerState() {
		int i = 0;

		StringBuilder builder = new StringBuilder();
		builder.append("The service has ")
			.append(servers.size())
			.append(" active servers.\nServer list ->\n");
		for (ServerDescriptor server : servers) {
			builder.append("Server ")
				.append(i)
				.append(":\n    ")
				.append(server.toString())
				.append("\n   Current Load: ")
				.append(server.getSubscribers())
				.append("\n\n");
			++i;
		}
		log.debug(builder.toString());
	}

	/**
	 * Check if a server can be added to the pool without causing consistency
	 * issues.
	 * 
	 * @param descriptor the descriptor of the candidate server.
	 * @return true if the new server can be added without issues.
	 */
	private static boolean checkServerConsistency(ServerDescriptor descriptor) {

		for (ServerDescriptor server : servers) {
			if (server.getIPAddress().equals(descriptor.getIPAddress())
					|| server.getMacAddress().equals(descriptor.getMacAddress()))
				return false;
		}

		return true;
	}

	/**
	 * Add a new server to the pool.
	 * 
	 * @param newServer the descriptor of the new server.
	 * @return true if the server was added, false if a server with the same address
	 *         is already present.
	 */
	public static boolean addServer(ServerDescriptor newServer) {
		if (!checkServerConsistency(newServer))
			return false;

		if (verboseMode) {
			log.debug("A server has been added to the pool: " + newServer.getIPAddress().toString());
		}

		servers.add(newServer);

		return true;
	}

	/**
	 * Remove a server from the pool.
	 * 
	 * @param oldServer the descriptor of the server to be removed.
	 * @return collection of affected subscriptions if server is found, null
	 *         otherwise
	 */
	public static Collection<Map.Entry<String, SubscriptionWrapper>> removeServer(ServerDescriptor oldServer) {
		Iterator<ServerDescriptor> i = servers.iterator();
		boolean foundIt = false;
		while (i.hasNext()) {
			if (i.next().equals(oldServer)) {

				i.remove();

				if (verboseMode) {
					log.debug("A server has been removed from the pool: " + oldServer.getIPAddress().toString());
				}

				foundIt = true;
				break;
			}
		}

		if (foundIt) {
			// move all subscriptions of that server to another server
			for (Map.Entry<String, SubscriptionWrapper> sub : subscriptions.entrySet()) {
				if (sub.getValue().getServer().equals(oldServer)) {
					ServerDescriptor chosenOne = getLeastBusy();
					sub.getValue().setServer(chosenOne);
					chosenOne.subscribe();
					oldServer.unsubscribe();
				}
			}

			// pass down list of subscriptions to handle client redirection
			return subscriptions.entrySet();
		}

		return null;
	}

}
