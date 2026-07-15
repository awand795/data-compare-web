import urllib.request
import json

print("Fetching connectors from localhost:8083...")
req = urllib.request.urlopen("http://localhost:8083/connectors?expand=status")
data = json.loads(req.read())

to_delete = []

# Logic: Group by prefix (ignoring the timestamp ID at the end)
# We will keep only the RUNNING ones, or the newest one if multiple are running.
# Actually, let's just show the status first.
for name, info in data.items():
    state = info.get("status", {}).get("connector", {}).get("state", "UNKNOWN")
    print(f"Connector: {name} | State: {state}")
    
    # Let's delete FAILED connectors or unassigned ones.
    if state in ["FAILED", "UNASSIGNED"]:
        to_delete.append(name)

print(f"\nFound {len(to_delete)} connectors that are FAILED or UNASSIGNED.")
for name in to_delete:
    print(f"Deleting {name}...")
    req = urllib.request.Request(f"http://localhost:8083/connectors/{name}", method="DELETE")
    try:
        urllib.request.urlopen(req)
        print(f"Deleted {name} successfully.")
    except Exception as e:
        print(f"Failed to delete {name}: {e}")
