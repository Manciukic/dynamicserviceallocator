package net.floodlightcontroller.dynamicserviceallocator;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;

import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;

/**
 * This class implements a manager that provides a subscription service to the other modules.
 * In particular it accepts clients subscriptions and automatically associates them to the least busy server.
 * It also handles unsubscription and subscription timeout.
 */
public class SubscriptionManager {

    private static final List<ServerDescriptor> servers = new ArrayList<>();

    /**
     * Parameter indicating the default lease time (seconds) for a subscription
     */
    private static final int defaultLeaseTime = 9;

    /**
     * This attribute maps a client identifier (obtained using toString() method of a ClientDescriptor)
     * to the server it is subscribed to. It makes the search of the paired server extremely efficient.
     * It has to be concurrent because expired subscriptions are removed by a parallel task (launchCleansingTask())
     */
    private static final Map<String, SubscriptionWrapper> subscriptions = new ConcurrentHashMap<>();

    /**
     * If set in init() method, the service will run in verbose mode (useful for debug purposes)
     */
    private static boolean verboseMode = false;

    /**
     * This attribute keeps a reference to the scheduler of the periodic subscription cleansing task
     */
    private static final ScheduledExecutorService periodicExecutor = Executors.newScheduledThreadPool(1);;

    /**
     * Run a set of operations to be performed in the initialization phase.
     * @param cleansingPeriod the period in seconds to schedule the task that deletes expired subscriptions
     * @param verbose if true the service will run in verbose mode (useful for debug)
     */
    public static void init(int cleansingPeriod, boolean verbose) {

        verboseMode = verbose;

        
        //Uncomment this to use pre-set servers
        IPv4Address[] ips = {
        		IPv4Address.of("192.168.0.2"),
                IPv4Address.of("192.168.0.3"),
                IPv4Address.of("192.168.0.4")
        };
        MacAddress[] macs = {
        		MacAddress.of("00:00:00:00:00:02"),
                MacAddress.of("00:00:00:00:00:03"),
                MacAddress.of("00:00:00:00:00:04")
        };

        servers.add(new ServerDescriptor(ips[0], macs[0], 5201));
        servers.add(new ServerDescriptor(ips[1], macs[1], 5201));

        if(verboseMode) {
            System.out.println("Subscription Service has started. Initial servers are ->");
            printServerState();
        }

        initPeriodicCleansingTask(cleansingPeriod);
        if(verboseMode) {
           System.out.println("Initialized cleansing scheduler with period = " + cleansingPeriod + " seconds");
        }
    }

    /**
     * Time at which the last expired subscriptions check has been performed
     */
    private static final Timestamp lastCleansingTime = new Timestamp(System.currentTimeMillis());

    /**
     * Subscribe a client to the least busy server.
     * @param clientID the string identifier of a client
     * @return An object containing the server to which the client has been subscribed and the lease duration
     */
    public static SubscriptionWrapper subscribe(String clientID) {
    	if(isSubscribed(clientID))
    	    unsubscribe(clientID);
    	
        ServerDescriptor chosenOne = getLeastBusy();
        chosenOne.subscribe();
        SubscriptionWrapper sub = new SubscriptionWrapper(chosenOne, defaultLeaseTime);
        subscriptions.put(clientID, sub);

        if(verboseMode) {
            System.out.println();
            System.out.println("A client has been subscribed ->");
            System.out.println("   Client: " + clientID);
            System.out.println("   Assigned Server: " + chosenOne.toString());
            System.out.println("   Lease time: " + sub.getLeaseTime() + " seconds");
            System.out.println();
        }

        return sub;
    }

    /**
     * Unsubscribe a client from the service.
     * @param clientID the client to be unsubscribed.
     */
    public static boolean unsubscribe(String clientID) {
    	if(subscriptions.get(clientID) == null) 
    		return false;
        ServerDescriptor chosenOne = subscriptions.remove(clientID).getServer();
        chosenOne.unsubscribe();

        if(verboseMode) {
            System.out.println();
            System.out.println("A client has been unsubscribed ->");
            System.out.println("   Client: " + clientID);
            System.out.println();
        }
        return true;
    }

    /**
     * Check whether a client is subscribed to the service or not.
     * @param clientID The client to check.
     * @return True if the client is correctly subscribed.
     */
    public static boolean isSubscribed(String clientID) {
        return subscriptions.get(clientID) != null;
    }

    /**
     * Get the server which a client is subscribed to.
     * @param clientID the subscribed client whose server is returned.
     * @return The server paired with the specified client. Return null if the client is not subscribed.
     */
    public static ServerDescriptor getSubscriptionServer(String clientID) {
    	SubscriptionWrapper sub = subscriptions.get(clientID);
        return (sub == null)? null:sub.getServer();
    }

    /**
     * Launch a parallel task to delete expired subscriptions (on demand version)
     */
    public static void launchCleansingTask() {

        if(verboseMode)
            System.out.println("SubscriptionManager is removing expired subscriptions (on demand)...");

        //We use a default executor service
        ExecutorService executorService =
                Executors.newFixedThreadPool(1);

        Future<?> result = executorService.submit(
                SubscriptionManager::cleanSubscriptions
        );

        try {

            result.get();
            executorService.shutdown();

            //no need to wait for termination, since the only task has already been completed.
            //executorService.awaitTermination(1, TimeUnit.MINUTES);

        } catch (Exception e) {
            if (e instanceof InterruptedException)
                Thread.currentThread().interrupt();
            else
                e.printStackTrace();
        }

        lastCleansingTime.setTime(System.currentTimeMillis());

    }

    /**
     * Initialize and start up the executor service that schedules the cleansing task with a certain period
     * @param period the period in seconds to schedule the cleansing of expired subscriptions
     */
    private static void initPeriodicCleansingTask(int period) {

        if(period <= 0) return;

        if(verboseMode)
            System.out.println("Initializing the periodic subscription cleansing execution...");


        Runnable cleansingRunnable =
                () -> {
                    if(verboseMode)
                        System.out.println("SubscriptionManager is removing expired subscriptions (periodic)...");
                    cleanSubscriptions();
                    lastCleansingTime.setTime(System.currentTimeMillis());
                };

        periodicExecutor.scheduleAtFixedRate(cleansingRunnable, period, period, TimeUnit.SECONDS);

    }

    /**
     * Utility function used as the body of cleansing task
     */
    private static void cleanSubscriptions() {
        subscriptions.entrySet().removeIf(
                (entry) -> {
                    boolean ret = entry.getValue().isExpired();
                    if(ret) {
                        //since this task is parallel, the following line is why
                        //subscribers attribute in ServerDescriptor is atomic
                        entry.getValue().getServer().unsubscribe();
                        if(verboseMode) {
                            System.out.println(); //DEBUG
                            System.out.println("Expired subscription removed:"); //DEBUG
                            System.out.println("   Client: " + entry.getKey()); //DEBUG
                            System.out.println("   Server: " + entry.getValue().getServer().toString()); //DEBUG
                            System.out.println(); //DEBUG
                        }
                    }
                    return ret;
                }
        );
    }


    /**
     * Retrieve the server with the lowest load and return it.
     * @return A descriptor of the lowest load server
     */
    private static ServerDescriptor getLeastBusy() {

        Optional<ServerDescriptor> temp =  servers.stream().min
                                            (
                                                Comparator.comparingInt(ServerDescriptor::getSubscribers)
                                            );
        return temp.orElse(null);

    }

    /**
     * Print a readable snapshot of all subscriptions. It can be used for debugging.
     */
    public static void printSnapshot() {
        int i = 0;
        System.out.println();
        System.out.println("Subscriptions list ->");
        for(Map.Entry<String, SubscriptionWrapper> entry : subscriptions.entrySet()) {
            System.out.println();
            System.out.println("Subscription " + i + ":");
            System.out.println("   Client: " + entry.getKey());
            System.out.println("   Server: " + entry.getValue().getServer().toString());
            System.out.println("   Lease Time: " + entry.getValue().getLeaseTime());
            System.out.println("   Expiration Time: " + entry.getValue().getExpirationTime().toString());
            ++i;
        }
    }

    /**
     * Print a readable description of the service servers with their current load. It can be used for debugging.
     */
    public static void printServerState() {
        int i = 0;

        System.out.println();
        System.out.println("The service has " + servers.size() + " active servers.");
        System.out.println();
        System.out.println("Server list ->");
        for(ServerDescriptor server: servers) {
            System.out.println();
            System.out.println("Server " + i + ":");
            System.out.println("   " + server.toString());
            System.out.println("   Current Load: " + server.getSubscribers());
            ++i;
        }
    }

    /**
     * Check if a server can be added to the pool without causing consistency issues.
     * @param descriptor the descriptor of the candidate server.
     * @return true if the new server can be added without issues.
     */
    private boolean checkServerConsistency(ServerDescriptor descriptor) {

        for(ServerDescriptor server : servers) {
            if(server.getIPAddress().equals(descriptor.getIPAddress()) ||
                    server.getMacAddress().equals(descriptor.getMacAddress()))
                return false;
        }

        return true;
    }

    /**
     * Add a new server to the pool.
     * @param newServer the descriptor of the new server.
     * @return true if the server was added, false if a server with the same address is already present.
     */
    public boolean addServer(ServerDescriptor newServer) {
        if(!checkServerConsistency(newServer))
            return false;

        servers.add(newServer);

        return true;
    }

    /**
     * Remove a server from the pool.
     * @param oldServer the descriptor of the server to be removed.
     * @return true if the server was removed. False if it does not exist.
     */
    public boolean removeServer(ServerDescriptor oldServer) {
        Iterator<ServerDescriptor> i = servers.iterator();
        while(i.hasNext()) {
            if(i.next().equals(oldServer)) {
                i.remove();
                return true;
            }
        }

        return false;
    }



}
