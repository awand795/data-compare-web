import os
import urllib.request
import json

DOCKER_HOST_IP = "172.21.0.1"
debezium_api = "http://localhost:8083/connectors"

targets = [
    {
        "connector": "source-p003_mkn_erp-shared",
        "port": 53621,
        "password": "MknJakarta"
    },
    {
        "connector": "source-p001_ssi_erp-shared",
        "port": 53557,
        "password": "SsiJakarta"
    },
    {
        "connector": "source-p011_bpi_erp-shared",
        "port": 55301,
        "password": "SsiJakarta"
    }
]

for t in targets:
    c_name = t["connector"]
    cfg_url = f"{debezium_api}/{c_name}/config"
    try:
        c_req = urllib.request.Request(cfg_url)
        c_res = urllib.request.urlopen(c_req)
        cfg = json.loads(c_res.read().decode('utf-8'))
        
        cfg["database.hostname"] = DOCKER_HOST_IP
        cfg["database.port"] = str(t["port"])
        cfg["database.password"] = t["password"]
        
        put_req = urllib.request.Request(cfg_url, method="PUT")
        put_req.add_header('Content-Type', 'application/json')
        put_req.data = json.dumps(cfg).encode('utf-8')
        put_res = urllib.request.urlopen(put_req)
        print(f"Updated {c_name}: {put_res.status}")
        
    except Exception as e:
        print(f"Error updating {c_name}: {e}")

print("Restarting connectors...")
for t in targets:
    try:
        r_req = urllib.request.Request(f"{debezium_api}/{t['connector']}/restart?includeTasks=true", method="POST")
        r_res = urllib.request.urlopen(r_req)
        print(f"Restarted {t['connector']}: {r_res.status}")
    except Exception as e:
        print(f"Error restarting {t['connector']}: {e}")
