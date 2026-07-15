import urllib.request, urllib.parse

def execute_ch(query):
    req = urllib.request.Request('http://war.darkosuite.com:8123/', data=query.encode('utf-8'))
    req.add_header('X-ClickHouse-User', 'darkosync')
    req.add_header('X-ClickHouse-Key', 'darkoSync9292')
    try:
        urllib.request.urlopen(req).read()
        print("Success:", query)
    except Exception as e:
        print("Failed:", query, e)

execute_ch("TRUNCATE TABLE `cdc_demo-erp_sch_erp_inventory_trd_penerimaan_lain`")
execute_ch("TRUNCATE TABLE `cdc_demo-erp_sch_erp_inventory_trh_penerimaan_lain`")
execute_ch("TRUNCATE TABLE `dwh_penerimaan_lain_v4`")
