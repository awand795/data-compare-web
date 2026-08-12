#!/usr/bin/env python3
import subprocess
import shlex
import csv
from io import StringIO
import time
import requests
import sys
import re

def log(msg):
    ts = time.strftime('%Y-%m-%d %H:%M:%S')
    print(f"[{ts}] {msg}", flush=True)

CH_URL = "http://localhost:8123/"
CH_AUTH = ('default', 'click!EnergyData@202608')

def run_ch_query(query):
    r = requests.post(CH_URL, params={'query': query}, auth=CH_AUTH)
    r.raise_for_status()
    return r.text.strip()

# Branches mapping
branches = {
    'P001': ('ssidarkoerpdb.cr1lxyris7tn.ap-southeast-1.rds.amazonaws.com', 8832, 'SsiJakarta', 'ssidarkoerp'),
    'P003': ('mkndarkoerpdb.cr1lxyris7tn.ap-southeast-1.rds.amazonaws.com', 8832, 'MknJakarta', 'mkndarkoerp'),
    'P011': ('127.0.0.1', 40977, 'SsiJakarta', 'bpidarkoerp')
}

log("Starting Auto-Reconciliation Process...")

# Fetch all pipelines from data_setting_sync
cmd_pipeline = 'PGPASSWORD="postgre!PowerData@202608" psql -h 127.0.0.1 -p 8832 -U postgres -d data_setting_sync --csv -c "SELECT target_table, query FROM sch_sync.data_warehouse_pipelines;"'
try:
    out_pipeline = subprocess.check_output(cmd_pipeline, shell=True).decode('utf-8')
except Exception as e:
    log(f"Failed to fetch pipelines: {e}")
    sys.exit(1)

reader = csv.reader(StringIO(out_pipeline))
next(reader) # skip header

pipelines = {}
for row in reader:
    if len(row) >= 2:
        tbl, q = row[0].strip(), row[1].strip()
        if tbl and q:
            pipelines[tbl] = q

log(f"Loaded {len(pipelines)} pipelines to reconcile.")

for tbl, q in pipelines.items():
    try:
        # Get Primary Key for table from ClickHouse
        cmd_pk = f"curl -s -u 'default:click!EnergyData@202608' \"http://localhost:8123/?query=SELECT%20primary_key%20FROM%20system.tables%20WHERE%20database='dw_erp'%20AND%20name='{tbl}'%20FORMAT%20TSV\""
        pks_str = subprocess.check_output(cmd_pk, shell=True).decode('utf-8').strip()
        pks = [p.strip() for p in pks_str.split(',') if p.strip() and p.strip() != 'kode_perusahaan']
        
        if not pks:
            continue

        pk_concat_pg = "concat(" + ", '|', ".join([f"COALESCE(regexp_replace(sub.{k}::text, '\\.0+$', ''), '')" for k in pks]) + ")"
        pk_concat_ch = "concat(" + ", '|', ".join([f"COALESCE({k}, '')" for k in pks]) + ")"

        for pt, (host, port, pwd, db) in branches.items():
            try:
                # 1. Fetch PG Keys using exact pipeline query as subquery
                pg_sql = f"SELECT {pk_concat_pg} FROM ({q}) sub"
                cmd_pg = f"PGPASSWORD='{pwd}' psql -h {host} -p {port} -U dbadmin -d {db} -t -A -c {shlex.quote(pg_sql)}"
                pg_out = subprocess.check_output(cmd_pg, shell=True, stderr=subprocess.DEVNULL).decode('utf-8').strip()
                pg_keys = set([k for k in pg_out.split('\n') if k])

                # 2. Fetch CH Keys
                ch_sql = f"SELECT {pk_concat_ch} FROM dw_erp.{tbl} FINAL WHERE is_deleted=0 AND kode_perusahaan='{pt}'"
                ch_keys = set([k for k in run_ch_query(ch_sql).split('\n') if k])

                missing_in_ch = list(pg_keys - ch_keys)

                if missing_in_ch:
                    log(f"[{pt}] {tbl}: Found {len(missing_in_ch)} missing/ghost records! Healing...")
                    for i in range(0, len(missing_in_ch), 100):
                        chunk = missing_in_ch[i:i+100]

                        # Fetch current max versions from CH for chunk to ensure new version = max_version + 1
                        ch_where_conds = []
                        for k in chunk:
                            parts = k.split('|')
                            conds = [f"{pks[j]}='{parts[j].replace(chr(39), chr(39)*2)}'" for j in range(len(pks))]
                            ch_where_conds.append(f"({' AND '.join(conds)})")
                        
                        max_ver_sql = f"SELECT {pk_concat_ch}, max(version) FROM dw_erp.{tbl} WHERE kode_perusahaan='{pt}' AND (" + " OR ".join(ch_where_conds) + f") GROUP BY {pk_concat_ch}"
                        max_ver_map = {}
                        highest_seen_ver = 0
                        try:
                            max_ver_out = run_ch_query(max_ver_sql)
                            for mv_line in max_ver_out.split('\n'):
                                if '\t' in mv_line:
                                    k_val, v_val = mv_line.split('\t')
                                    v_num = int(v_val.strip())
                                    max_ver_map[k_val.strip()] = v_num
                                    if v_num > highest_seen_ver:
                                        highest_seen_ver = v_num
                        except Exception:
                            pass

                        # Wipe out ghost records in CH
                        for k in chunk:
                            parts = k.split('|')
                            conds = [f"{pks[j]}='{parts[j].replace(chr(39), chr(39)*2)}'" for j in range(len(pks))]
                            del_sql = f"ALTER TABLE dw_erp.{tbl} DELETE WHERE kode_perusahaan='{pt}' AND {' AND '.join(conds)}"
                            run_ch_query(del_sql)

                        time.sleep(1) # Wait for ClickHouse mutation

                        # Fetch exact missing rows from PG
                        where_conds = []
                        for k in chunk:
                            parts = k.split('|')
                            conds = [f"regexp_replace(sub.{pks[j]}::text, '\\.0+$', '')='{parts[j].replace(chr(39), chr(39)*2)}'" for j in range(len(pks))]
                            where_conds.append(f"({' AND '.join(conds)})")

                        fetch_sql = f"SELECT sub.* FROM ({q}) sub WHERE (" + " OR ".join(where_conds) + ")"
                        fetch_cmd = f"PGPASSWORD='{pwd}' psql -h {host} -p {port} -U dbadmin -d {db} --csv -c {shlex.quote(fetch_sql)}"
                        out_csv = subprocess.check_output(fetch_cmd, shell=True, stderr=subprocess.DEVNULL).decode('utf-8').strip()

                        if out_csv:
                            # Re-inject missing rows into ClickHouse
                            r_csv = csv.reader(StringIO(out_csv))
                            headers = next(r_csv)
                            
                            rows_to_insert = []
                            for row in r_csv:
                                d = dict(zip(headers, row))
                                row_k = '|'.join([re.sub(r'\.0+$', '', d.get(pk_col, '').strip()) for pk_col in pks])
                                prev_ver = max_ver_map.get(row_k, highest_seen_ver)
                                if prev_ver == 0:
                                    prev_ver = 1000 # fallback baseline
                                d['version'] = str(prev_ver + 1)
                                d['is_deleted'] = '0'
                                rows_to_insert.append(d)

                            if rows_to_insert:
                                insert_headers = list(rows_to_insert[0].keys())
                                out_io = StringIO()
                                w = csv.DictWriter(out_io, fieldnames=insert_headers)
                                w.writeheader()
                                w.writerows(rows_to_insert)
                                
                                insert_url = f"{CH_URL}?query=INSERT INTO dw_erp.{tbl} FORMAT CSVWithNames"
                                res_ins = requests.post(insert_url, data=out_io.getvalue().encode('utf-8'), auth=CH_AUTH)
                                res_ins.raise_for_status()

                    log(f"[{pt}] {tbl}: Healing completed.")
            except Exception as branch_e:
                # Silently skip if branch db is unreachable or table query fails for specific branch
                pass

    except Exception as e:
        log(f"Error processing {tbl}: {str(e)}")

log("Auto-Reconciliation Process Completed.")
