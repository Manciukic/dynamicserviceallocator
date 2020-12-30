import requests
import os

controller_ip = os.environ['CONTROLLER_ADDR']

this_ip = os.environ['IP_ADDR']

virtual_ip = os.environ['VIRTUAL_IP']

url = 'http://' + controller_ip + ':8080/dsa/client/'

def subscribe():
    headers = {'Content-Type': 'application/json'}
    payload = '{"client_address": "' + this_ip + '"}'
    try:
        return requests.post(url + "subscribe", data=payload, headers=headers).json()
    except Exception as e:
        return {'successful_subscription': 'no', 'description': str(e)}

def unsubscribe():
    headers = {'Content-Type': 'application/json', 'Accept-Charset': 'UTF-8'}
    payload = '{"client_address": "' + this_ip + '"}'
    try:
        return requests.post(url + "unsubscribe", data=payload, headers=headers).text
    except Exception as e:
        return str(e)

def get_service():
    try:
        response = requests.get("http://"+virtual_ip)
    except requests.ConnectionError:
        return "Error: Destination host unreachable"
    else:
        return response.text

def printHelp():
    print("Welcome to the DSA client service. Here is a list of the available commands:")
    print()
    print("sub -> subscribe to the service")
    print("unsub -> unsubscribe from the service")
    print("get -> get the web page from the DSA server")
    print("quit -> quit this client")
    print("help -> to see this menu")
    print()
    print("To use the \'get\' command, the user should first subscribe, otherwise the DSA will return an error message.")
    print()

while True:
    cmd = input("> ")
    if cmd=="quit":
        break
    elif cmd=="sub":
        response = subscribe()
        if response['successful_subscription'] == 'yes':
            print(f"Correctly subscribed for {response['lease_time']} seconds!")
        else:
            print('Error: ' + response['description'])
    elif cmd=="unsub":
        if unsubscribe() == "OK":
            print("Correctly unsubscribed from the service!")
        else:
            print("No need to unsubscribe if there is no active subscription!")
    elif cmd=="get":
        print(get_service())
    elif cmd=="help":
        printHelp()
    else:
        print("Please type a valid command. Use \'help\' in case of necessity")
    print()
