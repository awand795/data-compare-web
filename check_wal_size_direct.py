import psycopg2
from sshtunnel import SSHTunnelForwarder

targets = [
    {
        'name': 'MKN',
        'db_host': 'mkndarkoerpdb.cr1lxyris7tn.ap-southeast-1.rds.amazonaws.com',
        'db_port': 8832,
        'db_name': 'mkndarkoerp',
        'db_user': 'dbadmin',
        'db_pass': 'MknJakarta'
    },
    {
        'name': 'SSI',
        'db_host': 'ssidarkoerpdb.cr1lxyris7tn.ap-southeast-1.rds.amazonaws.com',
        'db_port': 8832,
        'db_name': 'ssidarkoerp',
        'db_user': 'dbadmin',
        'db_pass': 'SsiJakarta'
    },
    {
        'name': 'BPI',
        'db_host': 'bpidarkoerpdb.cr1lxyris7tn.ap-southeast-1.rds.amazonaws.com',
        'db_port': 8832,
        'db_name': 'bpidarkoerp',
        'db_user': 'dbadmin',
        'db_pass': 'SsiJakarta'
    }
]

ssh_host = '52.77.168.121'
ssh_port = 2233
ssh_user = 'antoni'
ssh_pkey = '/home/awanda/tunnel_key.pem'

query = """
SELECT slot_name, active, restart_lsn, confirmed_flush_lsn, 
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained_wal
FROM pg_replication_slots;
"""

for t in targets:
    print(f"\\n--- Checking WAL size for {t['name']} ---")
    try:
        with SSHTunnelForwarder(
            (ssh_host, ssh_port),
            ssh_username=ssh_user,
            ssh_pkey=ssh_pkey,
            remote_bind_address=(t['db_host'], t['db_port'])
        ) as tunnel:
            conn = psycopg2.connect(
                host='127.0.0.1',
                port=tunnel.local_bind_port,
                dbname=t['db_name'],
                user=t['db_user'],
                password=t['db_pass'],
                connect_timeout=10
            )
            cur = conn.cursor()
            cur.execute(query)
            rows = cur.fetchall()
            
            if not rows:
                print("No replication slots found.")
            for row in rows:
                print(f"Slot: {row[0]}")
                print(f"  Active: {row[1]}")
                print(f"  Restart LSN: {row[2]}")
                print(f"  Confirmed Flush LSN: {row[3]}")
                print(f"  Retained WAL: {row[4]}")
            
            cur.close()
            conn.close()
    except Exception as e:
        print(f"Error checking {t['name']}: {e}")
