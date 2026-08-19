import requests
import json
import sys

KAFKA_CONNECT_URL = "http://localhost:8083/connectors"

def restore_connectors(backup_file):
    try:
        with open(backup_file, 'r') as f:
            configs = json.load(f)
    except Exception as e:
        print(f"Error reading backup file: {e}")
        sys.exit(1)

    print(f"Loaded {len(configs)} connectors from backup.")
    
    success_count = 0
    for name, config in configs.items():
        payload = {
            "name": name,
            "config": config
        }
        print(f"Restoring {name}...")
        resp = requests.post(KAFKA_CONNECT_URL, json=payload)
        
        if resp.status_code in [200, 201]:
            print(f"  -> SUCCESS")
            success_count += 1
        elif resp.status_code == 409:
            print(f"  -> ALREADY EXISTS (Updating instead...)")
            resp = requests.put(f"{KAFKA_CONNECT_URL}/{name}/config", json=config)
            if resp.status_code in [200, 201]:
                print(f"  -> UPDATED SUCCESSFULLY")
                success_count += 1
            else:
                print(f"  -> FAILED UPDATE: {resp.status_code} {resp.text}")
        else:
            print(f"  -> FAILED: {resp.status_code} {resp.text}")
            
    print(f"\nRestore complete. Successfully restored {success_count} / {len(configs)} connectors.")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 restore_connectors.py <backup.json>")
        sys.exit(1)
    restore_connectors(sys.argv[1])
