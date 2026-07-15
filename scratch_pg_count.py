import psycopg2

conn = psycopg2.connect(dbname='dba_erp_demo', user='postgres', host='122.248.253.73', port=8832, password='techRiderDevelop')
c = conn.cursor()

c.execute("SELECT COUNT(*) FROM sch_erp_inventory.trh_penerimaan_lain WHERE no_tran = '' OR no_tran IS NULL")
print("Postgres empty no_tran in trh:", c.fetchone()[0])

c.execute("SELECT COUNT(*) FROM sch_erp_inventory.trd_penerimaan_lain WHERE no_tran = '' OR no_tran IS NULL")
print("Postgres empty no_tran in trd:", c.fetchone()[0])

conn.close()
