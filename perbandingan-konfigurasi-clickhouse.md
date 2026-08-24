# Perbandingan Konfigurasi ClickHouse & Panduan Rollback

Dokumen ini mencatat rincian konfigurasi ClickHouse sebelum dan sesudah optimasi sumber daya (CPU, RAM, dan Disk Spill), serta menyediakan panduan praktis jika sewaktu-waktu ingin mengembalikan (*rollback*) ke konfigurasi awal.

---

## 1. Tabel Perbandingan Konfigurasi

| Parameter | Lokasi Konfigurasi | Nilai Awal (Sebelum) | Nilai Sekarang (Setelah Optimasi) | Fungsi & Dampak |
|---|---|---|---|---|
| **`max_server_memory_usage`** | `/etc/clickhouse-server/config.d/memory_limit.xml` | `0` (Tidak Terbatas / 14.4 GB via ratio 0.9) | **`10737418240` (10 GB)** | Membatasi total konsumsi RAM seluruh instance ClickHouse agar tidak menyedot habis seluruh RAM 16 GB server. |
| **`max_threads`** | `/etc/clickhouse-server/users.d/user_limits.xml` | `6` (Membajak seluruh Core CPU) | **`4` (Maksimal 4 Core)** | Menyisakan 2 Core CPU untuk OS, Kafka Connect, dan Debezium agar koneksi tidak putus (*freeze*). |
| **`max_memory_usage`** | `/etc/clickhouse-server/users.d/user_limits.xml` | `20000000000` (20 GB) / `0` | **`5368709120` (5 GB)** | Batas maksimal RAM untuk **satu query tunggal**. Mencegah satu query berat menumbangkan server. |
| **`max_bytes_before_external_group_by`** | `/etc/clickhouse-server/users.d/user_limits.xml` | `2000000000` (2 GB) | **`3221225472` (3 GB)** | Batas RAM sebelum operasi `GROUP BY` dialihkan (*spill*) ke Disk. |
| **`max_bytes_before_external_sort`** | `/etc/clickhouse-server/users.d/user_limits.xml` | `2000000000` (2 GB) | **`3221225472` (3 GB)** | Batas RAM sebelum operasi `ORDER BY / SORT` dialihkan (*spill*) ke Disk. |
| **`max_bytes_in_join`** | `/etc/clickhouse-server/users.d/user_limits.xml` | `0` (Tidak Dibatasi / Menumpuk di RAM) | **`2684354560` (2.5 GB)** | Ambang batas memori tabel JOIN sebelum dialihkan ke penyimpanan disk. |
| **`join_algorithm`** | `/etc/clickhouse-server/users.d/user_limits.xml` | `grace_hash,hash` (Murni RAM Hash Join) | **`full_sorting_merge,hash`** | Mengizinkan ClickHouse melakukan *Disk-based Sort-Merge Join* untuk query puluhan juta baris tanpa error *Memory Limit Exceeded*. |

---

## 2. File Konfigurasi Saat Ini di Server

### A. `/etc/clickhouse-server/config.d/memory_limit.xml`
```xml
<?xml version="1.0"?>
<clickhouse>
    <max_server_memory_usage>10737418240</max_server_memory_usage>
</clickhouse>
```

### B. `/etc/clickhouse-server/users.d/user_limits.xml`
```xml
<?xml version="1.0"?>
<clickhouse>
    <profiles>
        <default>
            <max_threads>4</max_threads>
            <max_memory_usage>5368709120</max_memory_usage>
            <max_bytes_before_external_group_by>3221225472</max_bytes_before_external_group_by>
            <max_bytes_before_external_sort>3221225472</max_bytes_before_external_sort>
            <max_bytes_in_join>2684354560</max_bytes_in_join>
            <join_algorithm>full_sorting_merge,hash</join_algorithm>
        </default>
    </profiles>
</clickhouse>
```

---

## 3. Panduan Rollback (Cara Mengembalikan ke Settingan Awal)

Jika sewaktu-waktu Anda ingin mengembalikan ClickHouse ke konfigurasi *default* pabrikan tanpa pembatasan ketat, ikuti langkah-langkah berikut via terminal SSH:

### Langkah 1: Hapus atau Reset File Konfigurasi Tambahan

Jalankan perintah berikut di terminal SSH:

```bash
# 1. Hapus batas global memory_limit.xml
sudo rm -f /etc/clickhouse-server/config.d/memory_limit.xml

# 2. Kembalikan user_limits.xml ke konfigurasi default
sudo bash -c 'cat > /etc/clickhouse-server/users.d/user_limits.xml << "EOF"
<?xml version="1.0"?>
<clickhouse>
    <profiles>
        <default>
            <max_threads>6</max_threads>
            <max_memory_usage>10000000000</max_memory_usage>
            <max_bytes_before_external_group_by>2000000000</max_bytes_before_external_group_by>
            <max_bytes_before_external_sort>2000000000</max_bytes_before_external_sort>
            <max_bytes_in_join>0</max_bytes_in_join>
            <join_algorithm>grace_hash,hash</join_algorithm>
        </default>
    </profiles>
</clickhouse>
EOF'

# 3. Set kepemilikan file
sudo chown clickhouse:clickhouse /etc/clickhouse-server/users.d/user_limits.xml
```

### Langkah 2: Restart ClickHouse Server

```bash
sudo systemctl restart clickhouse-server
```

### Langkah 3: Verifikasi

Jalankan query untuk memastikan settingan sudah kembali seperti semula:

```bash
clickhouse-client --password 'click!EnergyData@202608' --query "SELECT name, value FROM system.settings WHERE name IN ('max_threads', 'max_memory_usage', 'join_algorithm', 'max_bytes_in_join') FORMAT PrettyCompactMonoBlock;"
```
