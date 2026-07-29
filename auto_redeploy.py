import urllib.request
import urllib.parse
import json
import psycopg2

API_URL = "http://war.darkosuite.com:5300/api"
DB_HOST = "war.darkosuite.com"
DB_PORT = 8832
DB_NAME = "data_setting_sync"
DB_USER = "postgres"
DB_PASSWORD = "dataAnalyticW2024"

print("Connecting to DB...")
try:
    conn = psycopg2.connect(host=DB_HOST, port=DB_PORT, dbname=DB_NAME, user=DB_USER, password=DB_PASSWORD)
    cur = conn.cursor()
    cur.execute("SELECT deploy_id, query, source_connection_id, target_table, target_connection_id, target_database FROM sch_sync.data_warehouse_pipelines")
    rows = cur.fetchall()
except Exception as e:
    print(f"Error querying DB: {e}")
    rows = []

if not rows:
    print("No pipelines found in DB.")
    exit(1)

print(f"Found {len(rows)} pipelines to deploy.")

for row in rows:
    deploy_id, query, src_id, tgt_table, tgt_id, tgt_db = row
    
    deploy_payload = {
        "pipelineName": deploy_id,
        "sourceConnection": {"id": str(src_id)},
        "targetConnection": {"id": str(tgt_id)},
        "query": query,
        "targetTable": tgt_table,
    }
    if tgt_db:
        deploy_payload["targetDatabase"] = tgt_db

    print(f"Deploying {deploy_id} (table: {tgt_table})...")
    req = urllib.request.Request(
        f"{API_URL}/dwh/deploy",
        data=json.dumps(deploy_payload).encode('utf-8'),
        headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req) as response:
            res = response.read().decode('utf-8')
            print(f"Success: {deploy_id}")
    except urllib.error.HTTPError as e:
        err = e.read().decode('utf-8')
        print(f"Failed {deploy_id}: {e.code} - {err}")
    except Exception as e:
        print(f"Failed {deploy_id}: {e}")

print("Done.")
