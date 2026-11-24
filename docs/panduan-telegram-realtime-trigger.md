# Panduan End-to-End Integrasi Real-Time Telegram Bot (DarkoSync + Postman + PostgreSQL Trigger)

Dokumen ini adalah panduan teknis umum untuk **Tech Lead & Tim Engineering** dalam mengintegrasikan notifikasi Telegram real-time langsung dari database PostgreSQL (termasuk **Amazon RDS for PostgreSQL**) melalui **DarkoSync** menggunakan **`PG_LISTEN_NOTIFY`**, **tanpa mengubah kode aplikasi** dan **tanpa install plugin/extension tambahan** di PostgreSQL.

> **File Pendamping:** Import [darkosync-telegram-watcher.postman_collection.json](./darkosync-telegram-watcher.postman_collection.json) ke Postman agar semua endpoint di panduan ini siap digunakan.

---

## Ringkasan Arsitektur (`PG_LISTEN_NOTIFY`)

* **Real-Time (< 1 detik)**: Begitu transaksi `INSERT` di PostgreSQL `COMMIT`, `pg_notify(...)` langsung mengirim sinyal ke DarkoSync.
* **Aman untuk Amazon RDS PostgreSQL**: `pg_notify` dan `LISTEN/NOTIFY` adalah fitur bawaan native PostgreSQL — **tidak butuh extension** (`pg_net` / `pgsql-http`).
* **Nol Risiko ke Transaksi Database**: Trigger tidak melakukan HTTP call dari dalam database, sehingga tidak akan memperlambat atau membuat timeout transaksi aplikasi.

---

## Bagian A — Persiapan dari Nol (Buat Bot, Kontak & Token API)

Gunakan `{{BASE_URL}}` sesuai alamat server DarkoSync (misal `https://darkosync.domain-anda.com`).

### Langkah 1: Buat Bot di Telegram (`@BotFather`)
1. Buka Telegram, cari **`@BotFather`**.
2. Kirim perintah `/newbot`, lalu ikuti instruksi:
   * Masukkan **Nama Bot** (contoh: `ERP Notification Bot`).
   * Masukkan **Username Bot** berakhiran `bot` (contoh: `erp_notif_bot`).
3. Salin **Bot Token** yang diberikan oleh BotFather (format: `1234567890:ABCdefGHIjklMNOpqrsTUVwxyz`).

---

### Langkah 2: Test & Daftarkan Bot ke DarkoSync (Postman)

1. **Test Koneksi Bot Token**:
   * `POST {{BASE_URL}}/api/telegram/bots/test`
   * Body JSON:
     ```json
     {
       "botToken": "<BOT_TOKEN_DARI_BOTFATHER>"
     }
     ```
2. **Simpan / Daftarkan Bot**:
   * `POST {{BASE_URL}}/api/telegram/bots`
   * Body JSON:
     ```json
     {
       "name": "ERP Notification Bot",
       "botToken": "<BOT_TOKEN_DARI_BOTFATHER>",
       "pollingActive": true
     }
     ```
   * Catat **`id`** bot dari response (`<BOT_ID>`).
3. **Pastikan Polling Bot Aktif** (agar user yang `/start` otomatis tercatat sebagai Kontak):
   * `PUT {{BASE_URL}}/api/telegram/bots/<BOT_ID>/polling`
   * Body JSON: `{"active": true}`

---

### Langkah 3: Daftarkan Kontak Penerima (`chatId` & Nomor HP)

Bot Telegram mengirim pesan ke **`chatId`**, bukan langsung ke nomor HP. Ada 2 cara mendaftarkan kontak:

* **Cara Otomatis (Direkomendasikan)**:
  1. User tujuan membuka bot di Telegram, klik **Start** (`/start`), dan membagikan kontak/nomor HP.
  2. Cek hasilnya di Postman: `GET {{BASE_URL}}/api/telegram/contacts`.
* **Cara Manual via Postman**:
  * `POST {{BASE_URL}}/api/telegram/contacts`
  * Body JSON:
    ```json
    {
      "botId": "<BOT_ID>",
      "chatId": "<TELEGRAM_CHAT_ID>",
      "phoneNumber": "6281234567890",
      "firstName": "Nama Penerima"
    }
    ```
  * Catat **`id`** kontak dari response (`<CONTACT_ID>`).

---

### Langkah 4: Generate External API Token & Cek Connection ID Database

1. **Generate / Cek External API Token**:
   * Cek token aktif: `GET {{BASE_URL}}/api/telegram/external/tokens`
   * Atau generate token baru: `POST {{BASE_URL}}/api/telegram/external/tokens/generate` dengan body `{"name": "Integration Token"}`.
   * Simpan token ke variable `{{API_TOKEN}}` di Postman.
2. **Cek Connection ID Database Sumber**:
   * `GET {{BASE_URL}}/api/connections`
   * Cari koneksi database target dan catat nilai **`id`**-nya (`<CONNECTION_ID>`).

---

## Bagian B — Contoh 1: Tabel Biasa + Custom Message

Digunakan ketika Anda memantau langsung sebuah **tabel transaksi/master** dan ingin merakit format pesan custom setiap ada baris baru yang di-`INSERT`.

### 1. Buat Watcher via Postman
* `POST {{BASE_URL}}/api/v1/telegram/external/watchers`
* Headers:
  * `Authorization: Bearer {{API_TOKEN}}`
  * `Content-Type: application/json`
* Body JSON:
  ```json
  {
    "name": "Notif Transaksi Baru",
    "botId": "<BOT_ID>",
    "connectionId": "<CONNECTION_ID>",
    "watcherType": "PG_LISTEN_NOTIFY",
    "pgChannelName": "tg_transaksi_baru",
    "sqlQuery": "",
    "templateMessage": "Data baru masuk!\nNo: {{no_bukti}}\nNama: {{nama}}\nTotal: {{total}}",
    "recipientMode": "MASTER",
    "masterRecipientIds": "<CONTACT_ID>",
    "active": true
  }
  ```
> Karena `sqlQuery` dikosongkan, DarkoSync langsung membaca seluruh kolom dari `row_to_json(NEW)` untuk mengganti placeholder `{{nama_kolom}}` pada `templateMessage`.

### 2. Pasang Trigger `pg_notify` di PostgreSQL
```sql
CREATE OR REPLACE FUNCTION public.fn_notify_transaksi_tg()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('tg_transaksi_baru', row_to_json(NEW)::text);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_notify_transaksi_tg ON public.nama_tabel;

CREATE TRIGGER trg_notify_transaksi_tg
AFTER INSERT ON public.nama_tabel
FOR EACH ROW
EXECUTE FUNCTION public.fn_notify_transaksi_tg();
```

#### Variasi: Custom Message Langsung di SQL Trigger (`PL/pgSQL`)
Jika format pesan butuh logika SQL (`IF/ELSE`, `CASE`, format angka `to_char`), rakit pesannya di dalam function trigger lalu di Watcher set `templateMessage: "{{pesan}}"`:
```sql
CREATE OR REPLACE FUNCTION public.fn_notify_custom_sql_tg()
RETURNS trigger AS $$
DECLARE
  v_pesan text;
BEGIN
  v_pesan := format(
    E'<b>🔔 Data Baru Terdaftar</b>\nNo: <code>%s</code>\nNama: <b>%s</b>\nNominal: Rp %s',
    NEW.no_bukti,
    NEW.nama,
    to_char(COALESCE(NEW.total, 0), 'FM999,999,999')
  );

  PERFORM pg_notify(
    'tg_transaksi_baru',
    json_build_object(
      'user_hp', NEW.user_hp,
      'pesan', v_pesan
    )::text
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

---

## Bagian C — Contoh 2: Data dari `VIEW` yang Sudah Berisi `user_hp` & `pesan`

Digunakan ketika Anda sudah memiliki **`VIEW`** (misalnya `public.view_all_notifikasi`) yang menghasilkan kolom **`user_hp`** (penerima dinamis per baris) dan **`pesan`** (isi notifikasi siap kirim).

Karena PostgreSQL tidak mengizinkan trigger `AFTER INSERT` dipasang langsung di atas `VIEW`, trigger `pg_notify` dipasang pada **tabel dasar** sumber data `VIEW` tersebut, lalu DarkoSync otomatis menjalankan `sqlQuery` lookup ke `VIEW` memakai key (`:id` / `:kode`) dari trigger.

### 1. Buat Watcher via Postman (Dynamic Recipient + Lookup `VIEW`)
* `POST {{BASE_URL}}/api/v1/telegram/external/watchers`
* Headers:
  * `Authorization: Bearer {{API_TOKEN}}`
  * `Content-Type: application/json`
* Body JSON:
  ```json
  {
    "name": "Notif Dinamis dari VIEW",
    "botId": "<BOT_ID>",
    "connectionId": "<CONNECTION_ID>",
    "watcherType": "PG_LISTEN_NOTIFY",
    "pgChannelName": "tg_view_notif",
    "sqlQuery": "SELECT user_hp, pesan FROM public.view_all_notifikasi WHERE id = :id",
    "templateMessage": "{{pesan}}",
    "recipientMode": "DYNAMIC_COLUMN",
    "phoneColumnName": "user_hp",
    "active": true
  }
  ```
  * **`recipientMode: "DYNAMIC_COLUMN"`** + **`phoneColumnName: "user_hp"`**: DarkoSync membaca kolom `user_hp` pada setiap baris hasil `VIEW`, menormalisasi format nomor HP (`08...` / `628...` / `+628...`), mencocokkannya dengan tabel Kontak Telegram (`chatId`), lalu mengirim isi `{{pesan}}` ke user tersebut.

### 2. Pasang Trigger `pg_notify` di Tabel Dasar Sumber `VIEW`
```sql
CREATE OR REPLACE FUNCTION public.fn_notify_view_tg()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify(
    'tg_view_notif',
    json_build_object('id', NEW.id)::text
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_notify_view_tg ON public.tabel_sumber_notifikasi;

CREATE TRIGGER trg_notify_view_tg
AFTER INSERT ON public.tabel_sumber_notifikasi
FOR EACH ROW
EXECUTE FUNCTION public.fn_notify_view_tg();
```
*(Jika key lookup di `VIEW` menggunakan kolom lain seperti `no_bukti`, ubah `json_build_object('no_bukti', NEW.no_bukti)` dan `WHERE no_bukti = :no_bukti`.)*

---

## Bagian D — Testing, Monitoring & Rollback

### 1. Simulasi Cepat dari SQL Editor (Tanpa Insert Data Asli)
```sql
-- Test Contoh 1 (Tabel Biasa)
SELECT pg_notify('tg_transaksi_baru', '{"no_bukti":"TRX-001","nama":"Contoh Data","total":150000}');

-- Test Contoh 2 (VIEW Lookup dengan ID yang ada di tabel/VIEW)
SELECT pg_notify('tg_view_notif', '{"id": 123}');
```

### 2. Cek Trigger Terpasang di PostgreSQL
```sql
SELECT tgname, tgenabled
FROM pg_trigger t
JOIN pg_class c ON t.tgrelid = c.oid
JOIN pg_namespace n ON c.relnamespace = n.oid
WHERE n.nspname = 'public' AND c.relname = 'nama_tabel' AND NOT t.tgisinternal;
```

### 3. Cek Status & Log via Postman / UI
* **Postman**: `GET {{BASE_URL}}/api/v1/telegram/external/watchers` (cek `totalSentCount`, `lastStatus`, `lastMessage`).
* **UI DarkoSync**: Menu **Telegram Bot → Real-Time Watchers & Triggers** dan **Riwayat Log**.

### 4. Nonaktifkan / Hapus Trigger (Rollback)
* **Toggle Off via Postman**: `PUT {{BASE_URL}}/api/telegram/watchers/<WATCHER_ID>/toggle`
* **Hapus Trigger di PostgreSQL**:
  ```sql
  DROP TRIGGER IF EXISTS trg_notify_transaksi_tg ON public.nama_tabel;
  ```
