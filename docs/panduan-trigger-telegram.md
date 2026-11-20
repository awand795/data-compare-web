# Panduan Membuat DB Trigger Telegram Real-Time (Opsi 2A & 2B)

Panduan ini menjelaskan cara memasang trigger di database PostgreSQL ERP **tanpa mengubah aplikasi ERP**, lalu mengirim pesan Telegram secara otomatis menggunakan data dari **`VIEW`** (misalnya kolom `user_hp` dan `pesan`).

> **Catatan Arsitektur:** Karena PostgreSQL tidak mengizinkan trigger `AFTER INSERT` dipasang langsung di atas `VIEW`, trigger dipasang pada **tabel dasar** yang menjadi sumber `VIEW` tersebut. Trigger cukup mengirim key (misalnya `id`), lalu DarkoSync otomatis melakukan query lookup ke `VIEW` untuk mengambil `user_hp` dan `pesan`.

---

## Langkah 1: Buat Watcher di DarkoSync (Bisa via UI atau API)

Anda bisa membuat Watcher lewat menu **Telegram Bot → Real-Time Watchers & Triggers**, atau langsung lewat API:

### Contoh Create Watcher via API (`POST /api/v1/telegram/external/watchers`)

Gunakan Bearer Token dari tab **API Gateway & Spec** di menu Telegram Bot.

```bash
curl -X POST "https://DOMAIN-DARKOSYNC-ANDA/api/v1/telegram/external/watchers" \
  -H "Authorization: Bearer YOUR_TELEGRAM_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Notif Real-Time View Notifikasi",
    "botId": "ID_BOT_TELEGRAM_ANDA",
    "connectionId": "ID_KONEKSI_DB_ERP_ANDA",
    "watcherType": "PG_LISTEN_NOTIFY",
    "pgChannelName": "tg_notif_baru",
    "sqlQuery": "SELECT user_hp, pesan FROM sch_erp_system.view_all_notifikasi WHERE id = :id",
    "templateMessage": "{{pesan}}",
    "recipientMode": "DYNAMIC_COLUMN",
    "phoneColumnName": "user_hp",
    "active": true
  }'
```

* Jika ingin pakai **Opsi 2A (HTTP Trigger)**, ganti `"watcherType": "WEBHOOK_TRIGGER"` (kolom `pgChannelName` tidak diperlukan).
* Simpan **`id`** watcher yang dikembalikan (misalnya `tgw-a1b2c3d4`) untuk dipakai di URL trigger pada Opsi 2A.
* Pastikan `"phoneColumnName": "user_hp"` sesuai dengan nama kolom nomor HP / Chat ID di `VIEW` Anda (format `08...`, `628...`, atau `+628...` otomatis dicocokkan ke Master Kontak Telegram).

---

## Opsi 2B: Trigger PostgreSQL `pg_notify` + `LISTEN/NOTIFY` (Direkomendasikan)

**Kelebihan:**
* Tidak perlu install extension tambahan di PostgreSQL.
* **Sangat aman untuk transaksi ERP**: `pg_notify` hanya mengirim sinyal ringan ke memory setelah transaksi `COMMIT`, sehingga tidak pernah membuat `INSERT` ERP lambat atau timeout.
* Delay pengiriman sangat cepat (< 1 detik).

### 1. Buat Function Trigger

Ganti `sch_erp_system.nama_tabel_sumber` dengan tabel dasar yang memicu munculnya baris baru di `VIEW` Anda, dan sesuaikan `tg_notif_baru` dengan `pgChannelName` pada Watcher.

```sql
CREATE OR REPLACE FUNCTION sch_erp_system.fn_notify_tg_baru()
RETURNS trigger AS $$
BEGIN
  -- Cukup kirim key (misal id) atau seluruh baris NEW dalam format JSON
  PERFORM pg_notify(
    'tg_notif_baru',
    json_build_object('id', NEW.id)::text
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

> **Catatan:** Jika `VIEW` Anda lookup-nya memakai kolom lain selain `id` (misalnya `no_bukti` atau `kode_transaksi`), ubah `json_build_object('no_bukti', NEW.no_bukti)` dan sesuaikan `sqlQuery` di Watcher menjadi `WHERE no_bukti = :no_bukti`.

### 2. Pasang Trigger ke Tabel Sumber

```sql
DROP TRIGGER IF EXISTS trg_notify_tg_baru ON sch_erp_system.nama_tabel_sumber;

CREATE TRIGGER trg_notify_tg_baru
AFTER INSERT ON sch_erp_system.nama_tabel_sumber
FOR EACH ROW
EXECUTE FUNCTION sch_erp_system.fn_notify_tg_baru();
```

*(Jika notifikasi juga perlu terkirim saat `UPDATE` status tertentu, bisa gunakan `AFTER INSERT OR UPDATE` dengan kondisi `WHEN (NEW.status = 'APPROVED')`.)*

---

## Opsi 2A: Trigger PostgreSQL `pg_net` (Tembak Langsung ke HTTP API DarkoSync)

**Kelebihan:**
* Tidak membuka koneksi listener persistent ke PostgreSQL.
* Memanggil endpoint HTTP secara asynchronous melalui worker `pg_net` di PostgreSQL.

**Syarat:** Extension `pg_net` harus tersedia dan aktif di PostgreSQL Anda.

### 1. Aktifkan Extension `pg_net`

```sql
CREATE EXTENSION IF NOT EXISTS pg_net;
```

### 2. Buat Function Trigger HTTP Call

Ganti:
* `https://DOMAIN-DARKOSYNC-ANDA` dengan domain/IP server DarkoSync Anda.
* `tgw-a1b2c3d4` dengan `id` Watcher bertipe `WEBHOOK_TRIGGER`.
* `YOUR_TELEGRAM_API_TOKEN` dengan API Token Telegram External Anda.

```sql
CREATE OR REPLACE FUNCTION sch_erp_system.fn_http_tg_baru()
RETURNS trigger AS $$
BEGIN
  PERFORM net.http_post(
    url := 'https://DOMAIN-DARKOSYNC-ANDA/api/v1/telegram/external/trigger/tgw-a1b2c3d4',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer YOUR_TELEGRAM_API_TOKEN'
    ),
    body := jsonb_build_object('id', NEW.id)
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

### 3. Pasang Trigger ke Tabel Sumber

```sql
DROP TRIGGER IF EXISTS trg_http_tg_baru ON sch_erp_system.nama_tabel_sumber;

CREATE TRIGGER trg_http_tg_baru
AFTER INSERT ON sch_erp_system.nama_tabel_sumber
FOR EACH ROW
EXECUTE FUNCTION sch_erp_system.fn_http_tg_baru();
```

---

## Cara Testing Sebelum Pasang di Produksi

### Test Manual Endpoint Opsi 2A via `curl`
Pastikan API + query `VIEW` + pengiriman Telegram berjalan sukses sebelum trigger dipasang:

```bash
curl -X POST "https://DOMAIN-DARKOSYNC-ANDA/api/v1/telegram/external/trigger/tgw-a1b2c3d4" \
  -H "Authorization: Bearer YOUR_TELEGRAM_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"id": 12345}'
```

### Test Manual Sinyal Opsi 2B langsung dari SQL Editor PostgreSQL
Anda bisa mensimulasikan trigger tanpa harus insert data sungguhan ke tabel ERP:

```sql
SELECT pg_notify('tg_notif_baru', '{"id": 12345}');
```

Setelah dijalankan, cek hasil pengiriman di menu **Telegram Bot → Riwayat Log**.

---

## Cara Menonaktifkan / Menghapus Trigger (Rollback)

Jika sewaktu-waktu ingin mematikan trigger di PostgreSQL:

```sql
-- Untuk Opsi 2B
DROP TRIGGER IF EXISTS trg_notify_tg_baru ON sch_erp_system.nama_tabel_sumber;

-- Untuk Opsi 2A
DROP TRIGGER IF EXISTS trg_http_tg_baru ON sch_erp_system.nama_tabel_sumber;
```
