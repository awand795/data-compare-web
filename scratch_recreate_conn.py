import urllib.request
import json
import time

old_c = "source-demo-erp-1783657760045"
req = urllib.request.Request("http://localhost:8083/connectors/" + old_c + "/config")
config = json.loads(urllib.request.urlopen(req).read().decode('utf-8'))

config['decimal.handling.mode'] = 'double'
config['slot.name'] = 'source_demo_erp_v8'

new_c = "source-demo-erp-v8"
payload = {
    "name": new_c,
    "config": config
}

# Delete old
try:
    req_del = urllib.request.Request("http://localhost:8083/connectors/" + old_c, method="DELETE")
    urllib.request.urlopen(req_del)
    print("Deleted old")
except Exception as e:
    print("Delete failed:", e)

time.sleep(2)

# Create new
req_post = urllib.request.Request("http://localhost:8083/connectors", method="POST")
req_post.add_header('Content-Type', 'application/json')
req_post.data = json.dumps(payload).encode('utf-8')

print("Created new:", urllib.request.urlopen(req_post).read().decode('utf-8'))
