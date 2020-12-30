curl --header "Content-Type: application/json" --request POST --data "{\"server_address\": \"$IP_ADDR\", \"server_macaddress\": \"$MAC_ADDR\", \"service_port\": \"$SERVICE_PORT\"}" http://192.168.1.1:8080/dsa/server/unsubscribe


