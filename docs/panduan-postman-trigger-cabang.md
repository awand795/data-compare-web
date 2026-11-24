# Panduan End-to-End Integrasi Real-Time Telegram Bot (DarkoSync + Postman + PostgreSQL Trigger)

Dokumen ini disusun sebagai referensi lengkap untuk **Tech Lead & Tim Engineering** mulai dari **pembuatan Bot Telegram**, **pendaftaran di DarkoSync via Postman**, **setup Watcher `PG_LISTEN_NOTIFY`**, hingga **pemasangan Trigger PostgreSQL** (kompatibel penuh dengan **Amazon RDS for PostgreSQL** tanpa plugin/extension tambahan dan tanpa mengubah aplikasi ERP).

> **File Pendamping:** Import [darkosync-telegram-watcher.postman_collection.json](./darkosync-telegram-watcher.postman_collection.json) ke Postman agar semua endpoint di panduan ini siap klik.

---

## Ringkasan Arsitektur (`PG_LISTEN_NOTIFY`)

* **Real-Time (< 1 detik)**: Begitu transaksi `INSERT` di PostgreSQL `COMMIT`, `pg_notify(...)` langsung mengirim sinyal ke DarkoSync.
* **Aman untuk Amazon RDS PostgreSQL**: `pg_notify` dan `LISTEN/NOTIFY` adalah fitur bawaan native PostgreSQL — **tidak butuh extension** (`pg_net`/`pgsql-http`).
* **Nol Risiko ke Transaksi ERP**: Trigger tidak melakukan HTTP call dari dalam DB, sehingga tidak akan memperlambat atau membuat timeout transaksi ERP.

---

## Bagian A — Persiapan dari Nol (Buat Bot, Kontak & Token API)

Gunakan `{{BASE_URL}}` sesuai alamat DarkoSync (misal `http://94.237.69.119:5173` atau domain HTTPS Anda).

### Langkah 1: Buat Bot di Telegram (`@BotFather`)
1. Buka Telegram, cari **`@BotFather`**.
2. Kirim perintah `/newbot`, lalu ikuti instruksi:
   * Masukkan **Nama Bot** (contoh: `Darko Report`).
   * Masukkan **Username Bot** berakhiran `bot` (contoh: `darkoreportbot`).
3. Salin **Bot Token** yang diberikan oleh BotFather (format: `8830922721:AAH0qcQAO6DgKW8-ZR8-s4UV8ykDdDrzPhg`).

---

### Langkah 2: Test & Daftarkan Bot ke DarkoSync (Postman)

1. **Test Koneksi Bot Token**:
   * `POST {{BASE_URL}}/api/telegram/bots/test`
   * Body JSON:
     ```json
     {
       "botToken": "8830922721:AAH0qcQAO6DgKW8-ZR8-s4UV8ykDdDrzPhg"
     }
     ```
2. **Simpan / Daftarkan Bot**:
   * `POST {{BASE_URL}}/api/telegram/bots`
   * Body JSON:
     ```json
     {
       "name": "Darko Report",
       "botToken": "8830922721:AAH0qcQAO6DgKW8-ZR8-s4UV8ykDdDrzPhg",
       "pollingActive": true
     }
     ```
   * Catat **`id`** bot dari response (misal `49659711-baef-4cb9-a5aa-3bb8edfd5d76`).
3. **Pastikan Polling Bot Aktif** (agar user yang `/start` otomatis tercatat sebagai Kontak):
   * `PUT {{BASE_URL}}/api/telegram/bots/{botId}/polling`
   * Body JSON: `{"active": true}`

---

### Langkah 3: Daftarkan Kontak Penerima (`chatId` & Nomor HP)

Bot Telegram mengirim pesan ke **`chatId`**, bukan langsung ke nomor HP. Ada 2 cara mendaftarkan kontak:

* **Cara Otomatis (Direkomendasikan)**:
  1. Buka bot Anda di Telegram (misal `@darkoreportbot`), klik **Start** (`/start`) dan bagikan kontak / nomor HP.
  2. Cek hasilnya di Postman: `GET {{BASE_URL}}/api/telegram/contacts`.
* **Cara Manual via Postman**:
  * `POST {{BASE_URL}}/api/telegram/contacts`
  * Body JSON:
    ```json
    {
      "botId": "49659711-baef-4cb9-a5aa-3bb8edfd5d76",
      "chatId": "5514717368",
      "phoneNumber": "6282362851796",
      "firstName": "Awanda"
    }
    ```
  * Catat **`id`** kontak dari response (misal `6eeb8cb9-95ce-4b5a-9297-7b393edc4957`).

---

### Langkah 4: Generate External API Token & Cek Connection ID Database

1. **Generate / Cek External API Token**:
   * Cek token aktif: `GET {{BASE_URL}}/api/telegram/external/tokens`
   * Atau generate token baru: `POST {{BASE_URL}}/api/telegram/external/tokens/generate` dengan body `{"name": "Postman Token"}`.
   * Simpan token-nya ke variable `{{API_TOKEN}}` di Postman (contoh: `dks_live_c69e313835394d81b0e18cdbb5bb0603`).
2. **Cek Connection ID Database Sumber**:
   * `GET {{BASE_URL}}/api/connections`
   * Cari koneksi database target (contoh `DEMO-ERP` / `dba_erp_demo` → `id`: `1783329332719`).

---

## Bagian B — Contoh 1: Tabel Biasa + Custom Message

**Kasus Nyata (`dba_erp_demo` → `sch_erp_hr.mhs_cabang`):**
Setiap ada cabang baru di-`INSERT`, langsung kirim pesan custom `"halo ini nama cabang baru yang di tambah <nama_cabang> (Kode: <kode_cabang>)"` ke kontak `082362851796`.

### 1. Buat Watcher via Postman
* `POST {{BASE_URL}}/api/v1/telegram/external/watchers`
* Headers:
  * `Authorization: Bearer {{API_TOKEN}}`
  * `Content-Type: application/json`
* Body JSON:
  ```json
  {
    "name": "Notif Cabang Baru (mhs_cabang)",
    "botId": "49659711-baef-4cb9-a5aa-3bb8edfd5d76",
    "connectionId": "1783329332719",
    "watcherType": "PG_LISTEN_NOTIFY",
    "pgChannelName": "tg_cabang_baru",
    "sqlQuery": "",
    "templateMessage": "halo ini nama cabang baru yang di tambah {{nama_cabang}} (Kode: {{kode_cabang}})",
    "recipientMode": "MASTER",
    "masterRecipientIds": "6eeb8cb9-95ce-4b5a-9297-7b393edc4957",
    "active": true
  }
  ```

### 2. Pasang Trigger `pg_notify` di PostgreSQL (`dba_erp_demo`)
```sql
CREATE OR REPLACE FUNCTION sch_erp_hr.fn_notify_mhs_cabang_tg()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('tg_cabang_baru', row_to_json(NEW)::text);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_notify_mhs_cabang_tg ON sch_erp_hr.mhs_cabang;

CREATE TRIGGER trg_notify_mhs_cabang_tg
AFTER INSERT ON sch_erp_hr.mhs_cabang
FOR EACH ROW
EXECUTE FUNCTION sch_erp_hr.fn_notify_mhs_cabang_tg();
```

#### Variasi: Custom Message Langsung di SQL Trigger (`PL/pgSQL`)
Jika format pesan butuh logika SQL (`IF/ELSE`, `CASE`, format angka `to_char`), rakit pesannya di function trigger lalu di Watcher set `templateMessage: "{{pesan}}"`:
```sql
CREATE OR REPLACE FUNCTION sch_erp_hr.fn_notify_cabang_custom_sql()
RETURNS trigger AS $$
DECLARE
  v_pesan text;
BEGIN
  v_pesan := format(
    E'<b>🏢 Cabang Baru Terdaftar!</b>\nKode: <code>%s</code>\nNama: <b>%s</b>\nKota: %s',
    NEW.kode_cabang,
    NEW.nama_cabang,
    COALESCE(NEW.kota, '-')
  );

  PERFORM pg_notify(
    'tg_cabang_baru',
    json_build_object(
      'user_hp', '082362851796',
      'pesan', v_pesan
    )::text
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

---

## Bagian C — Contoh 2: Data dari `VIEW` yang Sudah Berisi `user_hp` & `pesan`

**Kasus Nyata (`sch_erp_system.view_all_notifikasi`):**
`VIEW` sudah menghitung kolom **`user_hp`** (penerima dinamis per baris) dan **`pesan`** (isi notifikasi siap kirim).

Karena PostgreSQL tidak mengizinkan trigger `AFTER INSERT` dipasang langsung di atas `VIEW`, trigger `pg_notify` dipasang pada **tabel dasar** sumber data `VIEW` tersebut, lalu DarkoSync otomatis menjalankan `sqlQuery` lookup ke `VIEW` memakai key (`:id` / `:no_bukti`) dari trigger.

### 1. Buat Watcher via Postman (Dynamic Recipient + Lookup `VIEW`)
* `POST {{BASE_URL}}/api/v1/telegram/external/watchers`
* Headers:
  * `Authorization: Bearer {{API_TOKEN}}`
  * `Content-Type: application/json`
* Body JSON:
  ```json
  {
    "name": "Notif Dinamis View Notifikasi",
    "botId": "49659711-baef-4cb9-a5aa-3bb8edfd5d76",
    "connectionId": "1783329332719",
    "watcherType": "PG_LISTEN_NOTIFY",
    "pgChannelName": "tg_view_notif",
    "sqlQuery": "SELECT user_hp, pesan FROM sch_erp_system.view_all_notifikasi WHERE id = :id",
    "templateMessage": "{{pesan}}",
    "recipientMode": "DYNAMIC_COLUMN",
    "phoneColumnName": "user_hp",
    "active": true
  }
  ```
  * **`recipientMode: "DYNAMIC_COLUMN"`** + **`phoneColumnName: "user_hp"`**: DarkoSync membaca kolom `user_hp` tiap baris hasil `VIEW`, menormalisasi format `08...`/`628...`/`+628...`, mencocokkannya dengan tabel Kontak Telegram (`chatId`), lalu mengirim isi `{{pesan}}` ke user tersebut.

### 2. Pasang Trigger `pg_notify` di Tabel Dasar Sumber `VIEW`
```sql
CREATE OR REPLACE FUNCTION sch_erp_system.fn_notify_view_tg()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify(
    'tg_view_notif',
    json_build_object('id', NEW.id)::text
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_notify_view_tg ON sch_erp_system.tabel_sumber_notifikasi;

CREATE TRIGGER trg_notify_view_tg
AFTER INSERT ON sch_erp_system.tabel_sumber_notifikasi
FOR EACH ROW
EXECUTE FUNCTION sch_erp_system.fn_notify_view_tg();
```
*(Jika key lookup di `VIEW` Anda memakai `no_bukti`, ganti `json_build_object('no_bukti', NEW.no_bukti)` dan `WHERE no_bukti = :no_bukti`.)*

---

## Bagian D — Testing, Monitoring & Rollback

### 1. Simulasi Cepat dari SQL Editor (Tanpa Insert Data Asli)
```sql
-- Test Contoh 1 (Tabel Biasa)
SELECT pg_notify('tg_cabang_baru', '{"kode_cabang":"C999","nama_cabang":"CABANG TEST"}');

-- Test Contoh 2 (VIEW Lookup dengan ID asli di tabel)
SELECT pg_notify('tg_view_notif', '{"id": 123}');
```

### 2. Cek Trigger Terpasang di PostgreSQL
```sql
SELECT tgname, tgenabled
FROM pg_trigger t
JOIN pg_class c ON t.tgrelid = c.oid
JOIN pg_namespace n ON c.relnamespace = n.oid
WHERE n.nspname = 'sch_erp_hr' AND c.relname = 'mhs_cabang' AND NOT t.tgisinternal;
```

### 3. Cek Status & Log via Postman / UI
* **Postman**: `GET {{BASE_URL}}/api/v1/telegram/external/watchers` (cek `totalSentCount`, `lastStatus`, `lastMessage`).
* **UI DarkoSync**: Menu **Telegram Bot → Real-Time Watchers & Triggers** dan **Riwayat Log**.

### 4. Nonaktifkan / Hapus Trigger (Rollback)
* **Toggle Off via Postman**: `PUT {{BASE_URL}}/api/telegram/watchers/{watcherId}/toggle`
* **Hapus Trigger di PostgreSQL**:
  ```sql
  DROP TRIGGER IF EXISTS trg_notify_mhs_cabang_tg ON sch_erp_hr.mhs_cabang;
  ```
