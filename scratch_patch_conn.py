import urllib.request
import json

c = "source-demo-erp-1783657760045"
req = urllib.request.Request("http://localhost:8083/connectors/" + c + "/config")
config = json.loads(urllib.request.urlopen(req).read().decode('utf-8'))

config['decimal.handling.mode'] = 'double'

req_put = urllib.request.Request("http://localhost:8083/connectors/" + c + "/config", method="PUT")
req_put.add_header('Content-Type', 'application/json')
req_put.data = json.dumps(config).encode('utf-8')

print(urllib.request.urlopen(req_put).read().decode('utf-8'))
