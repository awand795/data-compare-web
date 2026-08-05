import psycopg2
from sshtunnel import SSHTunnelForwarder
import urllib.request
import time

debezium_api = "http://localhost:8083/connectors"
c_name = "source-p011_bpi_erp-shared"

print("1. Pausing BPI Debezium connector...")
try:
    r = urllib.request.Request(f"{debezium_api}/{c_name}/pause", method="PUT")
    print("Pause status:", urllib.request.urlopen(r).status)
except Exception as e:
    print("Pause error:", e)

time.sleep(2)

print("\n2. Connecting to BPI RDS via SSH Bastion...")
ssh_host = '52.77.168.121'
ssh_port = 2233
ssh_user = 'antoni'
ssh_pkey = '/home/awanda/tunnel_key.pem'

bpi_db_host = 'ssidarkoerpdb.cr1lxyris7tn.ap-southeast-1.rds.amazonaws.com'
bpi_db_port = 8832

try:
    with SSHTunnelForwarder(
        (ssh_host, ssh_port),
        ssh_username=ssh_user,
        ssh_pkey=ssh_pkey,
        remote_bind_address=(bpi_db_host, bpi_db_port)
    ) as tunnel:
        print(f"SSH Tunnel opened on local port {tunnel.local_bind_port}")
        conn = psycopg2.connect(
            host='127.0.0.1',
            port=tunnel.local_bind_port,
            dbname='bpidarkoerp',
            user='dbadmin',
            password='SsiJakarta',
            connect_timeout=15
        )
        conn.autocommit = True
        cur = conn.cursor()

        print("Terminating blocking walsender / slot_p011_bpi_erp_shared PIDs...")
        cur.execute("SELECT pid FROM pg_stat_activity WHERE query LIKE '%slot_p011_bpi_erp_shared%' AND pid != pg_backend_pid();")
        pids = [r[0] for r in cur.fetchall()]
        print(f"Found {len(pids)} PIDs:", pids)
        for p in pids:
            try:
                cur.execute(f"SELECT pg_terminate_backend({p})")
            except Exception as ex:
                print(f"PID {p} terminate note:", ex)

        cur.close()
        conn.close()
        print("Waiting 5s for full disconnect...")
        time.sleep(5)

        print("Opening fresh connection for slot reset & CHECKPOINT...")
        conn2 = psycopg2.connect(
            host='127.0.0.1',
            port=tunnel.local_bind_port,
            dbname='bpidarkoerp',
            user='dbadmin',
            password='SsiJakarta',
            connect_timeout=15
        )
        conn2.autocommit = True
        cur2 = conn2.cursor()

        try:
            cur2.execute("SELECT pg_drop_replication_slot('slot_p011_bpi_erp_shared')")
            print("Successfully dropped slot_p011_bpi_erp_shared!")
        except Exception as e:
            print("Drop slot note:", e)

        try:
            cur2.execute("SELECT pg_create_logical_replication_slot('slot_p011_bpi_erp_shared', 'pgoutput')")
            print("Successfully recreated slot_p011_bpi_erp_shared!")
        except Exception as e:
            print("Create slot note:", e)

        print("Triggering PostgreSQL CHECKPOINT...")
        cur2.execute("CHECKPOINT;")
        print("CHECKPOINT completed successfully!")

        cur2.close()
        conn2.close()

except Exception as e:
    print("Direct SSH / DB error:", e)

time.sleep(2)

print("\n3. Resuming BPI Debezium connector...")
try:
    r = urllib.request.Request(f"{debezium_api}/{c_name}/resume", method="PUT")
    print("Resume status:", urllib.request.urlopen(r).status)
except Exception as e:
    print("Resume error:", e)
