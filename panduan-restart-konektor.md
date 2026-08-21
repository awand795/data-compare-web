# Panduan Mengatasi Source Konektor Gagal Setelah Server Restart

Ketika server utama (Linux) mengalami *restart* (mati total dan hidup kembali), ada jeda waktu di mana aplikasi *backend* Java belum siap, tetapi Kafka Connect (Debezium) sudah langsung mencoba menyambung ke database.

Akibatnya, Debezium tertinggal dan masih menggunakan konfigurasi *port* SSH lama, sedangkan *backend* Java (yang bertugas mengurus `autossh`) membuatkan *port* baru untuk koneksi *tunnel* ke database (SSI, MKN, BPI). Inilah yang menyebabkan status konektor menjadi `FAILED` atau *WAL slot* menjadi *inactive*.

Berikut adalah langkah-langkah mudah untuk menormalkannya kembali:

---

### 1. Cara Mengecek Port Autossh yang Sedang Aktif

Untuk melihat *port* berapa saja yang saat ini sedang aktif digunakan oleh `autossh` untuk menyambung ke *database* jarak jauh, jalankan perintah ini di terminal SSH server:

```bash
ps aux | grep autossh
```

**Contoh Hasil Output:**
```text
root      ... autossh ... -L 0.0.0.0:41648:ssidarkoerpdb...
root      ... autossh ... -L 0.0.0.0:38689:mkndarkoerpdb...
root      ... autossh ... -L 0.0.0.0:41019:ssidarkoerpdb...
```
Dari contoh di atas, kita bisa melihat bahwa *port* lokal yang sedang terbuka adalah `41648` untuk SSI, `38689` untuk MKN, dan `41019` untuk BPI.

---

### 2. Cara Otomatis Mengganti Port Konektor (Tanpa Hapus-Buat)

Alih-alih menghapus konektor dan membuatnya ulang (yang berisiko mereset riwayat pembacaan), kita bisa menggunakan **REST API Kafka Connect** untuk memperbarui (*update*) konfigurasinya secara instan (*on-the-fly*).

Agar tidak perlu mencocokkan *port* satu per satu secara manual, saya sudah membuatkan *script* otomatis. *Script* ini akan membaca *database internal*, menghitung *port* deterministik yang benar, dan langsung menembakkannya ke API Kafka Connect.

**Langkah Eksekusi:**

1. Buat file script Python di server (misal di `/home/awanda/`):
   ```bash
   nano /home/awanda/fix_debezium_ports.py
   ```
2. Isi file tersebut dengan kode berikut:
   ```python
   import urllib.request
   import urllib.parse
   import json
   import psycopg2
   import re

   def java_string_hashcode(s):
       h = 0
       for c in s:
           h = (31 * h + ord(c)) & 0xFFFFFFFF
       return ((h + 0x80000000) & 0xFFFFFFFF) - 0x80000000

   def get_deterministic_port(conn_id):
       return 33000 + (abs(java_string_hashcode(str(conn_id))) % 10000)

   print('Connecting to DB...')
   conn = psycopg2.connect('postgresql://postgres:postgre!PowerData%40202608@127.0.0.1:8832/data_setting_sync')
   cur = conn.cursor()
   cur.execute('SELECT id, name FROM sch_sync.connections WHERE use_ssh = true')
   connections = cur.fetchall()

   conn_map = {}
   for cid, name in connections:
       cbase = re.sub(r'[^a-zA-Z0-9_]', '_', name).lower()
       conn_map[cbase] = cid

   print('Fetching Connectors...')
   req = urllib.request.Request('http://localhost:8083/connectors')
   with urllib.request.urlopen(req) as response:
       connectors = json.loads(response.read().decode('utf-8'))

   for c in connectors:
       if c.startswith('source-') and c.endswith('-shared'):
           base_name = c[7:-7]
           if base_name in conn_map:
               cid = conn_map[base_name]
               correct_port = str(get_deterministic_port(cid))
               
               req = urllib.request.Request('http://localhost:8083/connectors/' + c + '/config')
               with urllib.request.urlopen(req) as response:
                   cfg = json.loads(response.read().decode('utf-8'))
               
               old_port = cfg.get('database.port')
               if old_port != correct_port:
                   print('Updating ' + c + ' port from ' + str(old_port) + ' to ' + correct_port)
                   cfg['database.port'] = correct_port
                   
                   put_req = urllib.request.Request('http://localhost:8083/connectors/' + c + '/config', data=json.dumps(cfg).encode('utf-8'), method='PUT')
                   put_req.add_header('Content-Type', 'application/json')
                   with urllib.request.urlopen(put_req) as put_res:
                       print('  -> Response Code: ' + str(put_res.status))
               else:
                   print(c + ' is already correct on port ' + correct_port)
   ```
3. Setelah disimpan, jalankan *script* tersebut dengan perintah:
   ```bash
   python3 /home/awanda/fix_debezium_ports.py
   ```

*Script* tersebut akan otomatis memperbaiki *port* yang salah. Segera setelah *script* selesai berjalan, Debezium akan otomatis me-*restart* dirinya sendiri (tanpa mematikan Kafka Connect) dan langsung menyambung kembali ke PostgreSQL tanpa ada satu pun baris data yang hilang!
