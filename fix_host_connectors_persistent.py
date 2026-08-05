import os
import subprocess
import urllib.request
import json
import time

DOCKER_HOST_IP = "172.21.0.1"
debezium_api = "http://localhost:8083/connectors"

targets = [
    {
        "conn_name": "P003-MKN-ERP",
        "connector": "source-p003_mkn_erp-shared",
        "port": 53621,
        "password": "MknJakarta",
        "remote_host": "mkndarkoerpdb.cr1lxyris7tn.ap-southeast-1.rds.amazonaws.com",
        "remote_port": 8832
    },
    {
        "conn_name": "P001-SSI-ERP",
        "connector": "source-p001_ssi_erp-shared",
        "port": 53557,
        "password": "SsiJakarta",
        "remote_host": "ssidarkoerpdb.cr1lxyris7tn.ap-southeast-1.rds.amazonaws.com",
        "remote_port": 8832
    },
    {
        "conn_name": "P011-BPI-ERP",
        "connector": "source-p011_bpi_erp-shared",
        "port": 55301,
        "password": "SsiJakarta",
        "remote_host": "bpidarkoerpdb.cr1lxyris7tn.ap-southeast-1.rds.amazonaws.com",
        "remote_port": 8832
    }
]

print("Setting up persistent SSH tunnels on the Docker Host...")

for t in targets:
    port = t["port"]
    remote_host = t["remote_host"]
    remote_port = t["remote_port"]
    
    print(f"Opening SSH tunnel for {t['conn_name']} on port {port}...")
    cmd = f"ssh -i /home/awanda/tunnel_key.pem -o StrictHostKeyChecking=no -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -fNTL 0.0.0.0:{port}:{remote_host}:{remote_port} antoni@52.77.168.121 -p 2233 > /dev/null 2>&1"
    subprocess.Popen(cmd, shell=True)

time.sleep(3)

print("\nUpdating Debezium Connectors to point to Docker Host (172.21.0.1)...")
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
