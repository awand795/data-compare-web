import urllib.request
import json

print("Fetching connectors from localhost:8083...")
req = urllib.request.urlopen("http://localhost:8083/connectors?expand=status")
data = json.loads(req.read())

groups = {}
for name, info in data.items():
    # Extract base name and timestamp
    # Format usually is source-demo-erp-1783672268021
    parts = name.rsplit('-', 1)
    if len(parts) == 2 and parts[1].isdigit():
        base_name = parts[0]
        ts = int(parts[1])
    else:
        base_name = name
        ts = 0
        
    if base_name not in groups:
        groups[base_name] = []
    
    # Also check if tasks are failed
    tasks = info.get("status", {}).get("tasks", [])
    has_failed_task = any(t.get("state") == "FAILED" for t in tasks)
    
    groups[base_name].append((ts, name, has_failed_task))

to_delete = []
to_keep = []

for base, items in groups.items():
    # Sort items by: first those that don't have failed tasks, then by timestamp descending
    # This means the newest non-failed one will be at the top!
    items.sort(key=lambda x: (not x[2], x[0]), reverse=True)
    
    kept = items[0]
    to_keep.append(kept[1])
    
    for item in items[1:]:
        to_delete.append(item[1])

print(f"\n--- KEEPING LATEST/HEALTHY ({len(to_keep)}) ---")
for k in to_keep:
    print(k)

print(f"\n--- DELETING DUPLICATES ({len(to_delete)}) ---")
for name in to_delete:
    print(f"Deleting {name}...")
    req = urllib.request.Request(f"http://localhost:8083/connectors/{name}", method="DELETE")
    try:
        urllib.request.urlopen(req)
        print(f"Deleted {name} successfully.")
    except Exception as e:
        print(f"Failed to delete {name}: {e}")
