import psycopg2
import base64

try:
    conn_settings = psycopg2.connect(
        host="war.darkosuite.com",
        port=8832,
        database="data_setting_sync",
        user="postgres",
        password="dataAnalyticW2024"
    )
    cursor_settings = conn_settings.cursor()
    cursor_settings.execute("SELECT name, username, password FROM connections WHERE id = '1783561676755';")
    row = cursor_settings.fetchone()
    if row:
        pw = row[2]
        if pw:
            try:
                pw = base64.b64decode(pw).decode('utf-8')
            except:
                pass
        print(f"Name: {row[0]}, User: {row[1]}, Pass: {pw}")
except Exception as e:
    print(e)
