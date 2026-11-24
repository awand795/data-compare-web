# Panduan Lengkap Postman & DB Trigger Telegram: Notif Cabang Baru (`dba_erp_demo`)

Panduan ini berisi langkah-langkah lengkap dari awal sampai selesai menggunakan **Postman** dan **SQL PostgreSQL** untuk mengirim pesan Telegram otomatis setiap kali ada cabang baru ditambahkan ke tabel `sch_erp_hr.mhs_cabang` di database `dba_erp_demo` (`DEMO-ERP`), dikirim ke kontak **Awanda (`082362851796` / `chatId: 5514717368`)** melalui bot **Darko Report**.

---

## Ringkasan Data Konfigurasi (Dari Server DarkoSync)

* **Connection ID (`DEMO-ERP` / `dba_erp_demo`)**: `1783329332719`
* **Bot ID (`Darko Report` / `@darkoreportbot`)**: `49659711-baef-4cb9-a5aa-3bb8edfd5d76`
* **Contact ID (`Awanda` / `082362851796` / `6282362851796`)**: `6eeb8cb9-95ce-4b5a-9297-7b393edc4957` (`chatId`: `5514717368`)
* **External API Token**: `dks_live_c69e313835394d81b0e18cdbb5bb0603`
* **Tabel Target**: `sch_erp_hr.mhs_cabang` (kolom: `kode_cabang`, `nama_cabang`, `kota`, `input_dt`, dll.)
* **Watcher ID yang sudah dibuat**: `tgw-aa7203d0` (Channel `PG_LISTEN_NOTIFY`: `tg_cabang_baru`)

---

## Bagian 1: Langkah-Langkah Setup via Postman (Dari Nol)

Gunakan Base URL DarkoSync Anda (misal `https://DOMAIN-DARKOSYNC-ANDA` atau `http://94.237.69.119:5173`).

### Step 1 — Cek Daftar Bot Telegram
* **Method & URL**: `GET {{BASE_URL}}/api/v1/telegram/external/bots`
* **Headers**:
  * `Authorization`: `Bearer dks_live_c69e313835394d81b0e18cdbb5bb0603`
* **Hasil**: Ambil `id` bot yang ingin dipakai (contoh: `49659711-baef-4cb9-a5aa-3bb8edfd5d76`).

---

### Step 2 — Cek Daftar Kontak & ID Koneksi Database
1. **Cek Kontak Telegram** (untuk mendapatkan `id` kontak tujuan `082362851796`):
   * **Method & URL**: `GET {{BASE_URL}}/api/telegram/contacts`
   * Cari yang `phoneNumber` = `6282362851796` → catat `id`-nya (`6eeb8cb9-95ce-4b5a-9297-7b393edc4957`).
   *(Pastikan user sudah pernah `/start` ke bot agar `chatId` terdaftar.)*

2. **Cek Koneksi Database**:
   * **Method & URL**: `GET {{BASE_URL}}/api/connections`
   * Cari `name` = `DEMO-ERP` (`dba_erp_demo`) → catat `id`-nya (`1783329332719`).

---

### Step 3 — Buat Real-Time Watcher (`PG_LISTEN_NOTIFY`) via Postman
* **Method & URL**: `POST {{BASE_URL}}/api/v1/telegram/external/watchers`
* **Headers**:
  * `Authorization`: `Bearer dks_live_c69e313835394d81b0e18cdbb5bb0603`
  * `Content-Type`: `application/json`
* **Body (raw JSON)**:
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
* **Response**: DarkoSync akan mengembalikan detail watcher (termasuk `"id": "tgw-aa7203d0"`) dan otomatis langsung menjalankan listener `LISTEN tg_cabang_baru` ke PostgreSQL `dba_erp_demo`.

*(Tips: Jika suatu saat Anda ingin mengirim ke nomor HP dinamis dari kolom tabel/view, cukup ganti `"recipientMode": "DYNAMIC_COLUMN"` dan tambah `"phoneColumnName": "user_hp"`.)*

---

### Step 4 — Cek Daftar Watcher yang Aktif via Postman
* **Method & URL**: `GET {{BASE_URL}}/api/v1/telegram/external/watchers`
* **Headers**:
  * `Authorization`: `Bearer dks_live_c69e313835394d81b0e18cdbb5bb0603`

---

## Bagian 2: Pasang Trigger di Database PostgreSQL (`dba_erp_demo`)

Jalankan SQL berikut di database `dba_erp_demo` (via DBeaver / pgAdmin / `psql`). Trigger ini memakai fitur bawaan `pg_notify` sehingga **tidak perlu install plugin/extension apa pun** (aman untuk Amazon RDS PostgreSQL juga):

```sql
-- 1. Buat Function Trigger
CREATE OR REPLACE FUNCTION sch_erp_hr.fn_notify_mhs_cabang_tg()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('tg_cabang_baru', row_to_json(NEW)::text);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 2. Pasang Trigger AFTER INSERT pada tabel sch_erp_hr.mhs_cabang
DROP TRIGGER IF EXISTS trg_notify_mhs_cabang_tg ON sch_erp_hr.mhs_cabang;

CREATE TRIGGER trg_notify_mhs_cabang_tg
AFTER INSERT ON sch_erp_hr.mhs_cabang
FOR EACH ROW
EXECUTE FUNCTION sch_erp_hr.fn_notify_mhs_cabang_tg();
```

---

## Bagian 3: Cara Testing

### Opsi A — Simulasi Tanpa Insert Data Asli (dari SQL Editor)
Jalankan query ini di `dba_erp_demo`:
```sql
SELECT pg_notify('tg_cabang_baru', '{"kode_cabang":"C999","nama_cabang":"CABANG TEST"}');
```
Dalam < 1 detik, Telegram `082362851796` (Awanda) akan langsung menerima pesan:
> `halo ini nama cabang baru yang di tambah CABANG TEST (Kode: C999)`

### Opsi B — Test via Postman (Endpoint Trigger Langsung)
* **Method & URL**: `POST {{BASE_URL}}/api/v1/telegram/external/trigger/tgw-aa7203d0`
* **Headers**:
  * `Authorization`: `Bearer dks_live_c69e313835394d81b0e18cdbb5bb0603`
  * `Content-Type`: `application/json`
* **Body (raw JSON)**:
  ```json
  {
    "kode_cabang": "C999",
    "nama_cabang": "CABANG TEST POSTMAN"
  }
  ```

### Opsi C — Test Insert Langsung ke Tabel `sch_erp_hr.mhs_cabang`
Setiap kali aplikasi ERP (atau query `INSERT`) menambahkan baris baru ke `sch_erp_hr.mhs_cabang`, trigger `trg_notify_mhs_cabang_tg` otomatis menembak `pg_notify` dan pesan langsung terkirim ke Telegram.

---

## Bagian 4: Cara Rollback / Nonaktifkan
* **Nonaktifkan sementara dari DarkoSync API (Postman)**:
  `PUT {{BASE_URL}}/api/telegram/watchers/tgw-aa7203d0/toggle`
* **Hapus Trigger dari DB `dba_erp_demo`**:
  ```sql
  DROP TRIGGER IF EXISTS trg_notify_mhs_cabang_tg ON sch_erp_hr.mhs_cabang;
  ```
