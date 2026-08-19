import requests
import json
import re

KAFKA_CONNECT_URL = "http://localhost:8083/connectors"

def get_all_connectors():
    resp = requests.get(KAFKA_CONNECT_URL)
    return resp.json()

def get_connector_config(name):
    resp = requests.get(f"{KAFKA_CONNECT_URL}/{name}")
    return resp.json().get('config', {})

def main():
    connectors = get_all_connectors()
    sink_groups = {}
    
    # Group sink connectors by their base name (target table)
    # Name format: sink-clickhouse-table_name-1786377745503
    pattern = re.compile(r"^(sink-clickhouse-.*)-(\d+)$")
    
    for c in connectors:
        match = pattern.match(c)
        if match:
            base_name = match.group(1)
            timestamp = int(match.group(2))
            if base_name not in sink_groups:
                sink_groups[base_name] = []
            sink_groups[base_name].append((c, timestamp))
            
    # Sort groups by timestamp descending
    kept_configs = {}
    deleted_count = 0
    
    for base_name, group in sink_groups.items():
        group.sort(key=lambda x: x[1], reverse=True)
        # Keep the newest one
        newest_name = group[0][0]
        config = get_connector_config(newest_name)
        kept_configs[newest_name] = config
        
        # Delete the older ones
        for c, ts in group[1:]:
            print(f"Deleting duplicate connector: {c} (keeping {newest_name})")
            resp = requests.delete(f"{KAFKA_CONNECT_URL}/{c}")
            if resp.status_code in [200, 204]:
                deleted_count += 1
            else:
                print(f"Failed to delete {c}: {resp.status_code} {resp.text}")
                
    # Save the kept configs to a JSON file for the MD artifact
    with open('/tmp/kept_connectors_backup.json', 'w') as f:
        json.dump(kept_configs, f, indent=2)
        
    print(f"\nTotal duplicates deleted: {deleted_count}")
    print(f"Unique tables actively syncing: {len(kept_configs)}")

if __name__ == "__main__":
    main()
