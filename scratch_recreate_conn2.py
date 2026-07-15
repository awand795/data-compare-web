import urllib.request
import json
import time
import urllib.error

new_c = "source-demo-erp-v8"
try:
    with open('req.json', 'r') as f:
        config = json.loads(f.read())
except:
    config = {
        "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
        "transforms.unwrap.delete.handling.mode": "rewrite",
        "slot.name": "source_demo_erp_v8",
        "tasks.max": "1",
        "transforms": "route,unwrap,rename",
        "transforms.rename.type": "org.apache.kafka.connect.transforms.ReplaceField$Value",
        "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
        "topic.prefix": "source-demo-erp-1783657760045",
        "transforms.route.regex": "([^\\.]+)\\.([^\\.]+)\\.([^\\.]+)",
        "transforms.unwrap.drop.tombstones": "false",
        "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
        "value.converter": "org.apache.kafka.connect.json.JsonConverter",
        "transforms.route.replacement": "cdc_demo-erp_$2_$3",
        "key.converter": "org.apache.kafka.connect.json.JsonConverter",
        "database.user": "postgres",
        "database.dbname": "dba_erp_demo",
        "transforms.rename.renames": "__deleted:is_deleted,__ts_ms:version",
        "database.server.name": "source-demo-erp-1783657760045",
        "plugin.name": "pgoutput",
        "database.port": "8832",
        "key.converter.schemas.enable": "false",
        "database.hostname": "122.248.253.73",
        "database.password": "techRiderDevelop",
        "value.converter.schemas.enable": "false",
        "transforms.unwrap.add.fields": "ts_ms",
        "table.include.list": "sch_erp_inventory.trd_penerimaan_lain,sch_erp_inventory.trh_penerimaan_lain",
        "decimal.handling.mode": "double"
    }

payload = {
    "name": new_c,
    "config": config
}

try:
    req_post = urllib.request.Request("http://localhost:8083/connectors", method="POST")
    req_post.add_header('Content-Type', 'application/json')
    req_post.data = json.dumps(payload).encode('utf-8')
    print("Created new:", urllib.request.urlopen(req_post).read().decode('utf-8'))
except urllib.error.HTTPError as e:
    print("HTTP Error:", e.code, e.read().decode('utf-8'))
