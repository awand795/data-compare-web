import psycopg2

try:
    conn = psycopg2.connect(host='122.248.253.73', port=5433, database='dba_erp_demo', user='darkosync', password='darkoSync9292')
    c = conn.cursor()
    c.execute("SELECT column_name, data_type, numeric_precision, numeric_scale FROM information_schema.columns WHERE table_schema='sch_erp_inventory' AND table_name='trd_penerimaan_lain'")
    for row in c.fetchall():
        print(row)
except Exception as e:
    print(e)
