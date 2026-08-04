# Solusi & Protocol Pemulihan PostgreSQL WAL Slot Membengkak (Debezium CDC)

> **Dokumen Panduan Operasional & Penanganan Masalah WAL Bloat**  
> *Dokumen ini dibuat agar setiap AI Agent atau System Administrator yang membaca repository ini dapat langsung memahami arsitektur, penyebab masalah, dan cara penanganannya secara instan.*

---

## 📌 Deskripsi Masalah

Database PostgreSQL RDS (`mkndarkoerpdb`, `ssidarkoerpdb`, `bpidarkoerpdb`) kerap mengalami pembengkakan file WAL (Write-Ahead Log) hingga **9.6 GB+**. Meskipun status konektor Debezium menunjukkan `RUNNING`, offset Kafka tidak bertambah, menyebabkan *Replication Slot* tertahan dan tidak mengosongkan log transaksi.

### 3 Penyebab Utama:
1. **Dynamic SSH Port Eviction dari Backend Java**: 
   Aplikasi backend Java (`DynamicSchedulerService`) memicu `ConnectionManagerService.evictConnection()` saat terjadi *query timeout* pada perbandingan data. Hal ini memutus SSH tunnel JSch yang ditumpangi Debezium, memutus *replication stream* CDC di tengah jalan.
2. **Penyatuan Cluster Database SSI & BPI**:
   Koneksi `P001-SSI-ERP` dan `P011-BPI-ERP` keduanya mengarah ke host RDS yang sama (`ssidarkoerpdb.cr1lxyris7tn.ap-southeast-1.rds.amazonaws.com:8832`). Konektor Debezium BPI harus diarahkan ke port SSH tunnel aktif yang sama atau yang baru saja dibuka oleh backend (`tasks.backend:<port>`).
3. **Penyumbatan Horizon LSN oleh Sesi menggantung (`idle in transaction`)**:
   Sesi query metadata yang menggantung di PostgreSQL menahan *snapshot horizon* (`restart_lsn`), mencegah PostgreSQL menghapus file WAL lama dari disk.

---

## 🚀 Skrip & Tooling Pemulihan yang Tersedia

Repository ini dilengkapi dengan skrip otomatis:

1. **`WAL_BLOAT_RESOLUTION_REPORT.md`**: Dokumen laporan analisis lengkap dan histori perbaikan per 4 Agustus 2026.
2. **`fix_debezium_dynamic_ports.py`**: Skrip otomatis untuk memicu `/api/test-connection`, membaca log port dinamis backend, dan meng-update config Debezium dengan `sslmode=disable`.
3. **`check_wal_size_direct.py`**: Skrip Python untuk mengecek status slot (`Active`), nilai LSN, dan ukuran *retained WAL* secara *real-time* lewat SSH tunnel.

---

## 📋 Prosedur Pemulihan Instan (SOP AI Agent & Sysadmin)

Jika di masa mendatang ukuran WAL membengkak kembali (misal > 1 GB) atau offset Debezium berhenti bergerak, ikuti langkah-langkah di bawah ini:

### 🔹 Skenario A: Pemulihan Koneksi (Konektor Disconnect / Port Mismatch)

```bash
# 1. SSH ke server
ssh -i /path/to/awanda.pem -p 8822 awanda@74.48.112.31

# 2. Pastikan Debezium HANYA berjalan 1 instance (mencegah split-brain)
docker service scale darkosync_debezium=1

# 3. Jalankan skrip perbaikan otomatis port SSH
python3 /home/awanda/fix_debezium_dynamic_ports.py
```

### 🔹 Skenario B: Pembersihan Instan WAL Bloat (Purge Disk Space dalam 10 Detik)

Jika konektor sudah `RUNNING` namun WAL di PostgreSQL RDS tidak kunjung turun (karena *decoding backlog* yang terlalu besar), lakukan **Prosedur Reset Slot & Checkpoint Instan**:

```python
# Jalankan skrip pembersihan di server:
# 1. Pause konektor Debezium (/pause)
# 2. Matikan PID walsender/idle transaction:
#    SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE state = 'idle in transaction';
# 3. Re-create / Advance replication slot:
#    SELECT pg_drop_replication_slot('slot_p001_ssi_erp_shared');
#    SELECT pg_create_logical_replication_slot('slot_p001_ssi_erp_shared', 'pgoutput');
# 4. Trigger Checkpoint di PostgreSQL:
#    CHECKPOINT;
# 5. Resume konektor Debezium (/resume)
```
> **Hasil:** File WAL sebesar 9+ GB di AWS RDS akan **langsung terhapus dan turun ke < 100 MB dalam 10 detik**, dan Debezium akan melanjutkan *streaming* dari transaksi terkini.

---

## 🛠️ Matrix Status & Verifikasi
Selalu pastikan setelah pemulihan:
* **Debezium Source Connectors:** `MKN`, `SSI`, `BPI`, `Dev`, `Demo` -> State `RUNNING` & Task `RUNNING`
* **ClickHouse Sink Connectors:** 39 dari 39 Pipeline -> State `RUNNING` & Task `RUNNING`
* **PostgreSQL Slots:** Status `Active: True`

