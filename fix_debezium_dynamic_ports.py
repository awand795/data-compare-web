import urllib.request
import json
import time
import subprocess
import re

backend_api = "http://localhost:5300/api"
debezium_api = "http://localhost:8083/connectors"

targets = [
    {
        "conn_name": "P003-MKN-ERP",
        "connector": "source-p003_mkn_erp-shared",
        "password": "MknJakarta",
        "remote_host": "mkndarkoerpdb"
    },
    {
        "conn_name": "P001-SSI-ERP",
        "connector": "source-p001_ssi_erp-shared",
        "password": "SsiJakarta",
        "remote_host": "ssidarkoerpdb"
    },
    {
        "conn_name": "P011-BPI-ERP",
        "connector": "source-p011_bpi_erp-shared",
        "password": "SsiJakarta",
        "remote_host": "ssidarkoerpdb"
    }
]

print("1. Fetching connections from backend...")
req = urllib.request.Request(f"{backend_api}/connections")
res = urllib.request.urlopen(req)
connections = json.loads(res.read().decode('utf-8'))
conn_map = {c.get("name"): c for c in connections}

for t in targets:
    conn_name = t["conn_name"]
    conn = conn_map.get(conn_name)
    if not conn:
        print(f"Skipping {conn_name}: not found in backend.")
        continue
    
    print(f"Triggering test-connection for {conn_name}...")
    t_req = urllib.request.Request(f"{backend_api}/test-connection", method="POST")
    t_req.add_header('Content-Type', 'application/json')
    t_req.data = json.dumps(conn).encode('utf-8')
    try:
        t_res = urllib.request.urlopen(t_req, timeout=15)
        print(f"Test-connection result: {t_res.status}")
    except Exception as e:
        print(f"Exception triggering test-connection: {e}")

time.sleep(2)

print("\n2. Extracting assigned dynamic ports from Docker logs...")
cid_cmd = "docker ps -q -f name=darkosync_backend.1"
cid_res = subprocess.run(cid_cmd, shell=True, capture_output=True, text=True)
cid = cid_res.stdout.strip()
if not cid:
    print("Backend container not found!")
    exit(1)

log_cmd = f"docker logs --tail 500 {cid}"
log_res = subprocess.run(log_cmd, shell=True, capture_output=True, text=True)
logs = log_res.stdout + log_res.stderr

assigned_ports = {}
pattern = re.compile(r"SSH tunnel established: localhost:(\d+) → ([a-zA-Z0-9]+)")
for line in reversed(logs.splitlines()):
    match = pattern.search(line)
    if match:
        port = match.group(1)
        remote = match.group(2)
        if remote not in assigned_ports:
            assigned_ports[remote] = port

for t in targets:
    r_host = t["remote_host"]
    if r_host in assigned_ports:
        t["port"] = assigned_ports[r_host]
        print(f"Found dynamic port {t['port']} for {r_host}")

print("\n3. Updating Debezium Connectors with sslmode=disable...")
for t in targets:
    c_name = t["connector"]
    if "port" not in t:
        continue
        
    cfg_url = f"{debezium_api}/{c_name}/config"
    try:
        c_req = urllib.request.Request(cfg_url)
        c_res = urllib.request.urlopen(c_req)
        cfg = json.loads(c_res.read().decode('utf-8'))
        
        cfg["database.hostname"] = "tasks.backend"
        cfg["database.port"] = str(t["port"])
        cfg["database.password"] = t["password"]
        cfg["database.sslmode"] = "disable"  # CRITICAL FIX for MTU/hangs
        
        put_req = urllib.request.Request(cfg_url, method="PUT")
        put_req.add_header('Content-Type', 'application/json')
        put_req.data = json.dumps(cfg).encode('utf-8')
        put_res = urllib.request.urlopen(put_req, timeout=10)
        print(f"Updated {c_name} to tasks.backend:{t['port']}: {put_res.status}")
        
    except Exception as e:
        print(f"Error updating {c_name}: {e}")

time.sleep(2)
print("\n4. Restarting connectors...")
for t in targets:
    if "port" not in t:
        continue
    try:
        r_req = urllib.request.Request(f"{debezium_api}/{t['connector']}/restart?includeTasks=true", method="POST")
        r_res = urllib.request.urlopen(r_req, timeout=10)
        print(f"Restarted {t['connector']}: {r_res.status}")
    except Exception as e:
        print(f"Error restarting {t['connector']}: {e}")
