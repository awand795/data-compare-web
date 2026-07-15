import urllib.request
import urllib.parse

def q(sql, is_modify=False):
    if is_modify:
        req = urllib.request.Request("http://war.darkosuite.com:8123/", data=sql.encode('utf-8'))
    else:
        req = urllib.request.Request("http://war.darkosuite.com:8123/?query=" + urllib.parse.quote(sql))
    
    req.add_header("X-ClickHouse-User", "darkosync")
    req.add_header("X-ClickHouse-Key", "darkoSync9292")
    try:
        print(sql + " => " + urllib.request.urlopen(req).read().decode('utf-8').strip())
    except urllib.error.HTTPError as e:
        print(sql + " => Error: " + e.read().decode('utf-8').strip())

# Create a dummy view and query with FINAL
q("CREATE OR REPLACE VIEW default.test_dummy_view AS SELECT 1 AS id", is_modify=True)
q("SELECT * FROM default.test_dummy_view FINAL")
q("DROP VIEW default.test_dummy_view", is_modify=True)
