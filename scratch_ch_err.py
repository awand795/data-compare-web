import urllib.request
import urllib.parse
def q(sql):
    req = urllib.request.Request("http://war.darkosuite.com:8123/?query=" + urllib.parse.quote(sql))
    req.add_header("X-ClickHouse-User", "darkosync")
    req.add_header("X-ClickHouse-Key", "darkoSync9292")
    try:
        print(urllib.request.urlopen(req).read().decode('utf-8').strip())
    except Exception as e:
        print(e)
q("SELECT event_time, exception, query FROM system.query_log WHERE type != 'QueryStart' AND exception != '' ORDER BY event_time DESC LIMIT 5 FORMAT TSV")
