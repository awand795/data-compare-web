import urllib.request, urllib.parse

def query_ch(query):
    req = urllib.request.Request('http://war.darkosuite.com:8123/?' + urllib.parse.urlencode({'query': query}))
    req.add_header('X-ClickHouse-User', 'darkosync')
    req.add_header('X-ClickHouse-Key', 'darkoSync9292')
    try:
        res = urllib.request.urlopen(req).read().decode('utf-8').strip()
        print("Response:", res)
    except Exception as e:
        print("Error executing query:", query)
        print(e)

query_ch("SHOW DATABASES")
query_ch("SHOW TABLES")
