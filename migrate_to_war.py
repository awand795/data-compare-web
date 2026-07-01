import psycopg2
import psycopg2.extras

source_url = "postgres://avnadmin:YOUR_AIVEN_PASSWORD@***REMOVED***:25789/defaultdb?sslmode=require"
target_url = "postgres://postgres:YOUR_DARKOSUITE_PASSWORD@***REMOVED***:8832/data_setting_sync"

schema_file = r"backend/src/main/resources/schema.sql"

tables_to_migrate = [
    "connections",
    "notification_channels",
    "schedules",
    "schedule_results",
    "schedule_result_rows",
    "templates"
]

def migrate():
    print("Connecting to source (Aiven)...")
    src_conn = psycopg2.connect(source_url)
    print("Connecting to target (***REMOVED***)...")
    tgt_conn = psycopg2.connect(target_url)
    
    tgt_cur = tgt_conn.cursor()
    
    print("Executing schema.sql on target...")
    with open(schema_file, 'r', encoding='utf-8') as f:
        schema_sql = f.read()
    
    # We ignore errors for ALTER TABLE just in case columns exist
    for statement in schema_sql.split(';'):
        stmt = statement.strip()
        if not stmt: continue
        try:
            tgt_cur.execute(stmt)
        except Exception as e:
            tgt_conn.rollback()
            print(f"Skipped statement: {stmt[:50]}... due to {e}")
        else:
            tgt_conn.commit()
    
    src_cur = src_conn.cursor()
    
    for table in tables_to_migrate:
        print(f"\nMigrating table: {table}")
        
        # Get column names from source
        src_cur.execute(f"SELECT * FROM {table} LIMIT 0")
        if src_cur.description is None:
            print(f"Table {table} not found in source.")
            continue
            
        columns = [desc[0] for desc in src_cur.description]
        col_str = ", ".join(columns)
        
        # Read all rows
        src_cur.execute(f"SELECT {col_str} FROM {table}")
        rows = src_cur.fetchall()
        print(f"Found {len(rows)} rows.")
        
        if len(rows) > 0:
            # Delete existing rows in target to prevent duplicate key errors
            tgt_cur.execute(f"TRUNCATE TABLE {table} CASCADE")
            
            insert_query = f"INSERT INTO {table} ({col_str}) VALUES %s"
            try:
                psycopg2.extras.execute_values(tgt_cur, insert_query, rows)
                tgt_conn.commit()
                print("Inserted successfully.")
            except Exception as e:
                tgt_conn.rollback()
                print(f"Failed to insert into {table}: {e}")

    # Fix sequences if there are serial columns
    try:
        tgt_cur.execute("SELECT setval('schedule_result_rows_id_seq', (SELECT MAX(id) FROM schedule_result_rows))")
        tgt_conn.commit()
    except Exception:
        tgt_conn.rollback()

    tgt_cur.close()
    src_cur.close()
    tgt_conn.close()
    src_conn.close()
    print("\nMigration completed!")

if __name__ == "__main__":
    migrate()
