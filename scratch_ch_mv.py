import urllib.request, urllib.parse

def query_ch(query):
    req = urllib.request.Request('http://war.darkosuite.com:8123/?' + urllib.parse.urlencode({'query': query}))
    req.add_header('X-ClickHouse-User', 'darkosync')
    req.add_header('X-ClickHouse-Key', 'darkoSync9292')
    try:
        print(urllib.request.urlopen(req).read().decode('utf-8').strip())
    except Exception as e:
        print(e)

query_ch("SHOW CREATE TABLE `cdc_demo-erp_sch_erp_inventory_trh_penerimaan_lain`")
