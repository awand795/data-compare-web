# Panduan Lengkap Integrasi Real-Time Telegram Bot (Postman, Watcher API & PostgreSQL Trigger)

Dokumen ini disusun sebagai panduan teknis lengkap untuk **Tech Lead & Tim Engineering** dalam mengintegrasikan notifikasi Telegram real-time langsung dari database PostgreSQL (termasuk **Amazon RDS for PostgreSQL**) melalui **DarkoSync**, **tanpa mengubah kode aplikasi ERP**.

---

## 1. Ringkasan Arsitektur & Kompatibilitas Amazon RDS

| Mekanisme di DarkoSync | Cara Kerja | Butuh Plugin RDS? | Risiko ke Transaksi ERP | Cocok Untuk |
| :--- | :--- | :--- | :--- | :--- |
| **`PG_LISTEN_NOTIFY`** *(Direkomendasikan)* | Trigger PostgreSQL menjalankan `pg_notify(...)` setelah `COMMIT`; DarkoSync mendengar sinyal secara real-time (< 1 detik). | **Tidak** (fitur native PostgreSQL) | **Nol** (non-blocking, tidak ada HTTP call dari dalam DB) | Tabel biasa maupun `VIEW` (via lookup key) |
| **`POLLING_WATERMARK`** | DarkoSync mengecek baris baru tiap `N` detik memakai index (`WHERE id > :last_watermark`). | **Tidak** (semua engine DB) | **Sangat rendah** (Index Range Scan) | Tabel/`VIEW` tanpa izin buat trigger |
| **`WEBHOOK_TRIGGER`** | Aplikasi luar / AWS Lambda / `pg_net` menembak HTTP `POST` ke API DarkoSync. | `aws_lambda` (di RDS) / `pg_net` (non-RDS) | Tergantung caller async | Trigger dari service luar / API / Lambda |

---

## 2. Referensi API Postman (Setup & Manajemen Watcher)

Gunakan `{{BASE_URL}}` sesuai alamat server DarkoSync (misal `http://94.237.69.119:5173` atau domain HTTPS Anda).

### A. Persiapan Data Master via Postman

1. **Cek Daftar Bot Telegram**:
   * `GET {{BASE_URL}}/api/v1/telegram/external/bots`
   * Header: `Authorization: Bearer <TELEGRAM_EXTERNAL_TOKEN>`
   * Catat `id` bot (contoh `Darko Report`: `49659711-baef-4cb9-a5aa-3bb8edfd5d76`).
2. **Cek Daftar Kontak Telegram** (untuk penerima `MASTER` atau memastikan nomor `user_hp` sudah punya `chatId`):
   * `GET {{BASE_URL}}/api/telegram/contacts`
   * Contoh kontak `Awanda` (`082362851796` / `6282362851796`): `id` = `6eeb8cb9-95ce-4b5a-9297-7b393edc4957`, `chatId` = `5514717368`.
   * *Catatan*: Agar bot bisa mengirim ke `user_hp`, user terkait harus pernah mengirim `/start` ke bot atau terdaftar di tabel Kontak DarkoSync (format `08...`, `628...`, dan `+628...` otomatis dinormalisasi).
3. **Cek Daftar Koneksi Database**:
   * `GET {{BASE_URL}}/api/connections`
   * Contoh koneksi `DEMO-ERP` (`dba_erp_demo`): `id` = `1783329332719`.

### B. Daftar Endpoint Manajemen Watcher

* **List Watcher (External Token)**: `GET {{BASE_URL}}/api/v1/telegram/external/watchers`
* **Buat Watcher (External Token)**: `POST {{BASE_URL}}/api/v1/telegram/external/watchers`
* **Trigger Webhook Manual (External Token)**: `POST {{BASE_URL}}/api/v1/telegram/external/trigger/{watcherId}`
* **Edit Watcher (Dashboard API)**: `PUT {{BASE_URL}}/api/telegram/watchers/{watcherId}`
* **Aktif / Nonaktifkan Watcher**: `PUT {{BASE_URL}}/api/telegram/watchers/{watcherId}/toggle`
* **Jalankan Polling Manual**: `POST {{BASE_URL}}/api/telegram/watchers/{watcherId}/run-now`
* **Reset Watermark**: `POST {{BASE_URL}}/api/telegram/watchers/{watcherId}/reset-watermark`
* **Hapus Watcher**: `DELETE {{BASE_URL}}/api/telegram/watchers/{watcherId}`

---

## 3. Skenario 1 — Tabel Biasa + Custom Message di DarkoSync

**Studi kasus nyata (`dba_erp_demo` → `sch_erp_hr.mhs_cabang`):**
Setiap ada cabang baru di-`INSERT`, kirim pesan custom ke `082362851796`.

### Langkah 1 (Postman) — Buat Watcher
`POST {{BASE_URL}}/api/v1/telegram/external/watchers`
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
> Karena `sqlQuery` dikosongkan, DarkoSync langsung memakai seluruh kolom dari `row_to_json(NEW)` untuk mengisi `{{nama_cabang}}`, `{{kode_cabang}}`, `{{kota}}`, dll.

### Langkah 2 (PostgreSQL) — Pasang Trigger `pg_notify`
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

---

## 4. Skenario 2 — Data dari `VIEW` yang Sudah Berisi Kolom `user_hp` & `pesan`

Jika Anda sudah memiliki `VIEW` (misalnya `sch_erp_system.view_all_notifikasi`) yang kolomnya sudah menghasilkan **`user_hp`** (penerima dinamis) dan **`pesan`** (isi pesan siap kirim):

### Cara 2A: Real-Time via `PG_LISTEN_NOTIFY` + Lookup `VIEW` (Instan < 1 Detik)
Karena `VIEW` tidak bisa dipasangi trigger secara langsung, trigger dipasang di **tabel dasar**, cukup mengirim key baris baru (`id` / `no_bukti`), lalu DarkoSync otomatis query ke `VIEW` Anda!

1. **Postman — Buat Watcher dengan Lookup `VIEW` & Penerima Dinamis (`user_hp`)**:
   `POST {{BASE_URL}}/api/v1/telegram/external/watchers`
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
   * `templateMessage` diisi `{{pesan}}` agar isi pesan persis mengikuti kolom `pesan` dari `VIEW`.
   * Anda tetap bisa memodifikasi/membungkusnya jika mau, misalnya: `<b>Info ERP:</b>\n{{pesan}}`.

2. **PostgreSQL — Pasang Trigger di Tabel Dasar**:
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

### Cara 2B: Tanpa Trigger Sama Sekali via `POLLING_WATERMARK` langsung ke `VIEW`
Cocok jika tidak ingin membuat trigger di database sumber dan `VIEW` memiliki kolom pengurut yang ter-index (`id` atau `created_at`):

```json
{
  "name": "Polling View Notifikasi (5s)",
  "botId": "49659711-baef-4cb9-a5aa-3bb8edfd5d76",
  "connectionId": "1783329332719",
  "watcherType": "POLLING_WATERMARK",
  "sqlQuery": "SELECT id, user_hp, pesan FROM sch_erp_system.view_all_notifikasi",
  "watermarkColumn": "id",
  "pollIntervalSeconds": 5,
  "templateMessage": "{{pesan}}",
  "recipientMode": "DYNAMIC_COLUMN",
  "phoneColumnName": "user_hp",
  "active": true
}
```
> Pada start pertama kali jika `lastWatermark` kosong, DarkoSync otomatis mengambil `MAX(id)` sebagai baseline agar data historis lama tidak ikut terkirim ulang.

---

## 5. Skenario 3 — Custom Message Langsung di Dalam SQL Trigger PostgreSQL

Jika format pesan membutuhkan logika SQL (`IF/ELSE`, `CASE`, format rupiah `to_char`), rakit pesannya di function trigger:

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
Lalu di Watcher DarkoSync, cukup set `templateMessage` = `{{pesan}}` dan `sqlQuery` = `""`.

---

## 6. Skenario 4 — Trigger HTTP API (`WEBHOOK_TRIGGER`)

Cocok bila pemicu berasal dari microservice luar, AWS Lambda (untuk Amazon RDS via extension `aws_lambda`), atau PostgreSQL non-RDS dengan `pg_net`.

1. **Buat Watcher** dengan `"watcherType": "WEBHOOK_TRIGGER"`.
2. **Panggil Endpoint Trigger** dari Postman / Lambda / `pg_net`:
   * `POST {{BASE_URL}}/api/v1/telegram/external/trigger/<WATCHER_ID>`
   * Header: `Authorization: Bearer <TELEGRAM_EXTERNAL_TOKEN>`
   * Body JSON:
     ```json
     {
       "kode_cabang": "C009",
       "nama_cabang": "MEDAN"
     }
     ```
   *(Jika Watcher memiliki `sqlQuery` lookup ke `VIEW`, cukup kirim `{"id": 123}` saja.)*

---

## 7. Testing, Monitoring Log & Rollback

### Simulasi Cepat dari SQL Editor (Tanpa Insert Data Asli)
```sql
SELECT pg_notify('tg_cabang_baru', '{"kode_cabang":"C999","nama_cabang":"CABANG TEST"}');
```

### Cek Trigger yang Terpasang di PostgreSQL
```sql
SELECT tgname, tgenabled
FROM pg_trigger t
JOIN pg_class c ON t.tgrelid = c.oid
JOIN pg_namespace n ON c.relnamespace = n.oid
WHERE n.nspname = 'sch_erp_hr' AND c.relname = 'mhs_cabang' AND NOT t.tgisinternal;
```

### Cek Status & Log Pengiriman
* **UI**: Buka menu **Telegram Bot → Real-Time Watchers & Triggers** dan **Riwayat Log**.
* **API**: `GET {{BASE_URL}}/api/v1/telegram/external/watchers` (cek `totalSentCount`, `lastStatus`, `lastMessage`).

### Rollback / Nonaktifkan
* **Dari Postman**: `PUT {{BASE_URL}}/api/telegram/watchers/<WATCHER_ID>/toggle`
* **Dari Database**:
  ```sql
  DROP TRIGGER IF EXISTS trg_notify_mhs_cabang_tg ON sch_erp_hr.mhs_cabang;
  ```
