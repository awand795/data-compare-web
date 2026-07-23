import urllib.request
import urllib.parse
import json
import time
import sys

API_URL = "http://war.darkosuite.com:5300/api"

print("Fetching connections...")
req = urllib.request.Request(f"{API_URL}/connections")
with urllib.request.urlopen(req) as response:
    connections = json.loads(response.read().decode())

source_conn_id = None
target_conn_id = None

for conn in connections:
    if "demo" in conn.get("name", "").lower():
        source_conn_id = conn.get("id")
    if "clickhouse" in conn.get("name", "").lower():
        target_conn_id = conn.get("id")

print(f"Source ID: {source_conn_id}")
print(f"Target ID: {target_conn_id}")

if not source_conn_id or not target_conn_id:
    print("Could not find required connections.")
    sys.exit(1)

deploy_payload = {
    "sourceConnection": {"id": source_conn_id},
    "targetConnection": {"id": target_conn_id},
    "query": """-- Define the data to sync via Debezium                                                                                                  
    SELECT x.kode_perusahaan, x.kode_cabang, x.nama_cabang, x.alamat,                                                                        
           x.kota, x.propinsi, x.kode_pos, x.telepon, x.fax, x.email1, x.email2,                                                             
           x.input_by, x.input_dt, x.update_by, x.update_dt, x.kode_kelurahan, x.latitude,                                                   
           x.longitude, x.induk_cabang, CURRENT_TIMESTAMP AS insert_dt                                                                       
      FROM sch_erp_hr.mhs_cabang x;""",
    "targetDatabase": "default",
    "targetTable": "ms_cabang",
    "primaryKeys": "kode_perusahaan,kode_cabang"
}

print("Deploying pipeline...")
req = urllib.request.Request(
    f"{API_URL}/dwh/deploy",
    data=json.dumps(deploy_payload).encode('utf-8'),
    headers={"Content-Type": "application/json"}
)

try:
    with urllib.request.urlopen(req) as response:
        for line in response:
            line_str = line.decode('utf-8').strip()
            if line_str:
                print(line_str, flush=True)
except Exception as e:
    print(f"Error: {e}")
