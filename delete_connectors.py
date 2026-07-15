import urllib.request
import json

try:
    resp = urllib.request.urlopen("http://localhost:8083/connectors")
    connectors = json.loads(resp.read().decode('utf-8'))
    for c in connectors:
        req = urllib.request.Request("http://localhost:8083/connectors/" + urllib.parse.quote(c), method="DELETE")
        try:
            urllib.request.urlopen(req)
            print("Deleted", c)
        except Exception as e:
            print("Failed to delete", c, e)
except Exception as e:
    print(e)
