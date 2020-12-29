# Setup instructions for GNS3 emulator

## Goal
Setup GNS3 and Floodlight for testing the Virtual IP module.

## Prerequisites

1. GNS3 installed on a VirtualBox VM or directly on your PC. In case of VM,
    it must be connected to a host-only network (in the following we'll assume
    `vboxnet0`).
2. [Floodlight's VM](https://floodlight.atlassian.net/wiki/spaces/floodlightcontroller/pages/8650780/Floodlight+VM) 
    on VirtualBox, connected to the same host-only network as GNS3 (if any).
3. OVS Docker image on the local repository (or inside GNS3 VM)
4. Web server Docker image on the local repository (or inside GNS3 VM)
5. Web client Docker image on the local repository (or inside GNS3 VM)

You can also use any guest image as client and server if you want to just try 
out the setup.

## Setup instructions

### Floodlight
TODO

### GNS3 ready-to-use project
A ready-to-use GNS3 project implemented using the tutorial in the next section is provided 
in the local repository. For the sake of simplicity, it requires Floodlight running 
on the same host running GNS3. To make it work, follow these simple steps:

1. Open the project with GNS3.
2. Run the following commands on the host running both GNS3 and Floodlight:
```
ip addr add 192.168.1.1/24 dev gns3-tap0
ip link set gns3-tap0 up
ip route add 192.168.0.0/24 via 192.168.1.1
```


### GNS3
1. Create a new project
2. Create 4 OVS switches using the aforementioned appliance
3. Connect switch 1 to 2, 3 and 4 using any interface you like. Do not connect 
    switches 2, 3 and 4 together (leaf and spine topology).
4. Create a new Cloud and add `gns3-tap0` (in case floodlight is on the same VM as GNS3), `enp3s0`
   (in case GNS3 is in a VM) or `vboxnet0`
   (in case GNS3 is running on your PC) to the available interfaces). Connect switch 1 (spine switch)
   to this new interface on the Cloud.
    Tip: you may need to enable "Show special Ethernet interfaces".
5. Configure `LOOPBACK_ADDR` environment variable for the OVS switches. This address 
    needs to be in the same subnet as Floodlight's (in my case `192.168.1.0/24`).
    To do so, right-click on the switch, "Configure" and edit Environment variables field. E.g.:

```
LOOPBACK_ADDR=192.168.1.3/24
```
6.  Configure `IS_GATEWAY` environment variable only for OVS switch 1 (spine switch). This address
    needs to be in the same subnet as servers and clients (in my case `192.168.0.0/24`).
    To do so, right-click on the switch, "Configure" and edit Environment variables field. E.g.:
```
LOOPBACK_ADDR=192.168.1.2/24
IS_GATEWAY=192.168.0.1/24
```    

7. Start the OVS switches.
8. Connect to the console of every OVS switch and configure the controller with 
    the command `ovs-vsctl set-controller br0 tcp:192.168.1.1:6653` (replace IP 
    address and port to Floodlight's). 
    Tip: you can check the controller connection status with the command
    `ovs-vsctl list controller`.
9. Create the client appliance by going to Edit>Preferences>Docker>Docker containers
    and creating a new appliance. Choose the Web client image and give it 1 adapter.
10. Create one or more clients and connect `eth0` to OVS switch 1.
11. Configure each client network with a static IP on both interfaces. 
    Use a new subnet for `eth0` (e.g. `192.168.0.0/24`, the same subnet used fot IS_GATEWAY environment 
    variable on OVS switch 1).

```
auto eth0
iface eth0 inet static
	address 192.168.0.2
	netmask 255.255.255.0
	gateway 192.168.0.1 #IS_GATEWAY on switch 1
```
12. Set the address given to `eth0` also to `IP_ADDR` environment variable on the same Web client. Set also
    `VIRTUAL_IP` environment variable to the IP address of the virtual web service (in my case `9.9.9.9`).
    Then, set also `CONTROLLER_ADDR` environment variable to Floodlight's IP address. E.g.:
    
```
VIRTUAL_IP=9.9.9.9
IP_ADDR=192.168.0.7
CONTROLLER_ADDR=192.168.1.1
```

13. Repeat step 9 to 11 for the Web servers, but connect them to switch 2, 3 or 4 (any leaf switch).
    Also, statically set the MAC address for servers since it will be needed by Floodlight. E.g.:

```
auto eth0
iface eth0 inet static
	address 192.168.0.2
	netmask 255.255.255.0
	hwaddress ether 00:00:00:00:00:02
	gateway 192.168.0.1 #IS_GATEWAY on switch 1
```

14. Set the address given to `eth0` also to `IP_ADDR` environment variable as done with clients.
    Set also `MAC_ADDR` to the hardware address given to `eth0`. Set also `SERVICE_PORT` to 80. E.g.:

```
IP_ADDR=192.168.0.3
MAC_ADDR=00:00:00:00:00:03
SERVICE_PORT=80
```
15. Ensure that the host running Floodlight has a route to clients and servers subnet
    (in my case 192.168.0.0/24) using the other side of the Cloud interface on GNS3.

## User manual
Web clients run a CLI which lets users subscribe and perform requests on the virtual service.
Here is shown the help menu of this interface:

```
Welcome to the DSA client service. Here is a list of the available commands:

sub -> subscribe to the service
unsub -> unsubscribe from the service
get -> get the web page from the DSA server
quit -> quit this client
help -> to see this menu

To use the 'get' command, the user should first subscribe, otherwise the DSA will return an error message.

```

Web servers are simple alpine containers running an apache httpd daemon. Since DSA supports also servers
subscriptions, their consoles come with `sub.sh` and `unsub.sh` commands to subscribe and unsubscribe
to the DSA servers pool.

