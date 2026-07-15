import urllib.request, urllib.parse, json

data = {
    "sourceConnection": {"id": "36f6d0f5-5626-4447-b8db-4e782d4be0c2"},
    "targetConnection": {"id": "2d3c907b-8919-45d3-82bd-1393699b66ba"},
    "pipelineName": "dwh_penerimaan_lain_v4",
    "targetTable": "dwh_penerimaan_lain_v4",
    "sqlQuery": "SELECT a.seq, a.no_tran, b.tgl_tran, b.no_memo, b.kode_cabang, b.kode_gudang, b.kode_jenis_terima, a.kode_barang, a.qty_terima, a.hrg_pokok, a.input_by, a.input_dt, a.update_by, a.update_dt, a.catatan FROM sch_erp_inventory.trd_penerimaan_lain a LEFT JOIN sch_erp_inventory.trh_penerimaan_lain b ON a.no_tran = b.no_tran"
}

req = urllib.request.Request('http://localhost:8081/api/dwh/deploy', data=json.dumps(data).encode('utf-8'))
req.add_header('Content-Type', 'application/json')
try:
    response = urllib.request.urlopen(req)
    while True:
        line = response.readline()
        if not line:
            break
        print(line.decode('utf-8').strip())
except Exception as e:
    print(e)
