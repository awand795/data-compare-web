import urllib.request

def q(sql):
    req = urllib.request.Request("http://war.darkosuite.com:8123/?query=" + urllib.parse.quote(sql))
    req.add_header("X-ClickHouse-User", "darkosync")
    req.add_header("X-ClickHouse-Key", "darkoSync9292")
    try:
        print(sql, "=>", urllib.request.urlopen(req).read().decode('utf-8').strip())
    except Exception as e:
        print(sql, "=>", e)

import urllib.parse
q("SELECT COUNT(*) FROM default.`cdc_demo-erp_sch_erp_inventory_trd_penerimaan_lain` FINAL WHERE is_deleted=0")
q("SELECT COUNT(*) FROM default.`cdc_demo-erp_sch_erp_inventory_trh_penerimaan_lain` FINAL WHERE is_deleted=0")
q("SELECT COUNT(*) FROM default.dwh_penerimaan_lain_v7 FINAL WHERE is_deleted=0")
q("SELECT hrg_pokok FROM default.`cdc_demo-erp_sch_erp_inventory_trd_penerimaan_lain` LIMIT 5")
