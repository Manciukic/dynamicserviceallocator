# Setup instructions for GNS3 emulator

## Goal
Setup GNS3 and Floodlight for testing the Virtual IP module.

## Prerequisites

1. GNS3 installed on a VirtualBox VM or directly on your PC. In case of VM,
    it must be connected to a host-only network (in the following we'll assume
    `vboxnet0`).
2. [Floodlight's VM](https://floodlight.atlassian.net/wiki/spaces/floodlightcontroller/pages/8650780/Floodlight+VM) 
    on VirtualBox, connected to the same host-only network as GNS3 (if any).
3. [Open vSwitch with management interface GNS3 appliance](https://www.gns3.com/marketplace/appliances/open-vswitch-with-management-interface)
4. Server Docker image on the local repository (or inside GNS3 VM)
5. Client Docker image on the local repository (or inside GNS3 VM)

You can also use any guest image as client and server if you want to just try 
out the setup.

## Instructions

### Floodlight
TODO

### GNS3
1. Create a new project
2. Create 3 OVS switches using the aforementioned appliance
3. Connect switch 1 to 2 and 3 using any interface you like except `eth0`, which
    will be used to connect to the Floodlight controller. Do not connect 
    switches 2 and 3 together.
4. Create a new Ethernet Switch and connect all 3 OVS switches to it. This will 
    be used to connect to Floodlight.
5. Create a new Cloud and add `enp3s0` (in case GNS3 is in a VM) or `vboxnet0`
    (in case GNS3 is running on your PC) to the available interfaces). Connect 
    the Ethernet Switch to this new interface of the Cloud.
    Tip: you may need to enable "Show special Ethernet interfaces".
6. Configure a static IP address for the OVS switches on `eth0`. This address 
    needs to be in the same subnet as Floodlight's (in my case `10.0.0.0/24`).
    To do so, right-click on the switch, "Configure" and edit "Network 
    configuration". Un-comment lines related to `eth0` and replace the IP 
    address. E.g.:

```
auto eth0
iface eth0 inet static
	address 10.0.0.201
	netmask 255.255.255.0
	gateway 10.0.0.1
```

7. Start the OVS switches.
8. Connect to the console of every OVS switch and configure the controller with 
    the command `ovs-vsctl set-controller br0 tcp:10.0.0.8:6653` (replace IP 
    address and port to Floodlight's). 
    Tip: you can check the controller connection status with the command
    `ovs-vsctl list controller`.
9. Create the client appliance by going to Edit>Preferences>Docker>Docker containers
    and creating a new appliance. Choose the client image and give it 2 adapters.
    Choose `TODO` as start-up command.
    One will be used to connect to OVS switches and the other one to the 
    Ethernet switch to the controller.
10. Create one or more clients and connect `eth0` to OVS switch 1 and 
    `eth1` to the Ethernet switch (so that they can directly contact the 
    Floodlight controller to use its REST APIs).
11. Configure each client network with a static IP on both interfaces. 
    Use a new subnet for `eth0` (e.g. `10.0.1.0/24`) and the same subnet as 
    Floodlight's for `eth1`. Configure the gateway only in `eth1` as done 
    earlier. E.g.:

```
auto eth0
iface eth0 inet static
	address 10.0.1.1
	netmask 255.255.255.0
    
auto eth1
iface eth1 inet static
	address 10.0.0.211
	netmask 255.255.255.0
    gateway 10.0.0.1
```

12. Repeat steps 9 to 11 for the servers but connect them to OVS switches 
    2 and 3. Use `TODO` as startup command when creating the server appliance.
    As a result, clients and servers should all be on the same subnet.

    