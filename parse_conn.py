import json
data = json.load(open('connections.json'))
for c in data:
    print(c['name'], '->', c['id'], c.get('type'))
