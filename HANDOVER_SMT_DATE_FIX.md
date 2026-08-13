# 📋 Handover: SMT Date Fix Verification & Fallback Plan

> **Dibuat**: 2026-08-13 20:23 WIB  
> **Agent berikutnya**: Lanjutkan tugas ini besok di kantor

---

## 🎯 Konteks Masalah

**Root Cause:** Debezium mengirim kolom `DATE` dari PostgreSQL sebagai `int32` (jumlah hari sejak 1970-01-01) dengan logical type `io.debezium.time.Date`. Saat nilai ini sampai ke ClickHouse, terjadi *overflow* karena ClickHouse `Date` (16-bit UInt) tidak bisa menampung nilai yang terlalu besar, sehingga tanggal menjadi tahun **1984 atau 1998**. Baris yang terdampak kemudian **tidak muncul** di query yang memfilter `tgl >= 2023`, menyebabkan selisih data.

---

## ✅ Yang Sudah Dilakukan (2026-08-13 malam)

### 1. Fix Data yang Sudah Hilang (Manual Backfill)
- ✅ Insert ulang `trd_penjualan` SSI 1 baris yang hilang (PK: `SO00126008553 / FJ00126010788 / RKH-37800-K16-002`) dengan `version = 21594449569137` (current CDC version + 1)
- ✅ Insert ulang `trh_penjualan` yang hilang di SSI dan MKN sebelumnya (sesi sebelumnya)

### 2. Patch SMT ke Live Debezium Connectors
Ketiga source connector yang berjalan sudah di-patch via Kafka Connect REST API dengan **TimestampConverter SMT** untuk 22 kolom Date:

```
diterima_tgl, non_aktif_tgl, tgl_batal, tgl_blokir, tgl_bukti,
tgl_fak_jl, tgl_jth_tempo, tgl_jto_pdc, tgl_kerja, tgl_kliring,
tgl_lahir, tgl_npwp, tgl_ord_jl, tgl_order, tgl_pdc, tgl_proses,
tgl_ref, tgl_retur, tgl_setor, tgl_terakhir_kerja, tgl_tran, tgl_ttb
```

Connector yang di-patch:
- `source-p001_ssi_erp-shared` → HTTP 200 ✅
- `source-p003_mkn_erp-shared` → HTTP 200 ✅
- `source-p011_bpi_erp-shared` → HTTP 200 ✅

### 3. Fix Permanen di Backend (Opsi B)
- ✅ Commit & push ke GitHub: `feat(pipeline): implement Option B SMT injection`
- ✅ Build & push Docker image: `awandadarkotech/darkosync-backend:latest`
- ⏳ Deploy otomatis via cron jam **22.00** malam ini (sudah terjadwal di server)

---

## ⚠️ Yang Perlu Diverifikasi Besok

**Tingkat keyakinan saat ini: ~65-70%**

Ketidakpastian utama: `TimestampConverter` SMT Kafka Connect dirancang untuk timestamp dalam milliseconds. Debezium mengirim `DATE` sebagai `io.debezium.time.Date` (int32, days since epoch) — belum diverifikasi apakah SMT dapat mengenali dan mengkonversi format ini dengan benar.

### Langkah Verifikasi (Jalankan Besok)

**Step 1: Cek apakah ada data baru masuk dengan tanggal yang benar**
```sql
-- Di ClickHouse, cek data yang masuk setelah jam 20:25 WIB 2026-08-13
SELECT no_fak_jl, tgl_fak_jl, toYear(tgl_fak_jl) as tahun
FROM dw_erp.trh_penjualan FINAL
WHERE _sign = 1
ORDER BY tgl_fak_jl DESC
LIMIT 20
```

**Hasil yang diharapkan:** `tahun = 2026`
**Hasil yang menandakan GAGAL:** `tahun = 1984` atau `1998`

**Step 2 (bila perlu): Paksa update 1 baris di PostgreSQL SSI untuk test**
```python
# Jalankan script test di scratch/ atau update manual via psql
# Pantau hasilnya di ClickHouse 30 detik kemudian
```

---

## 🚨 Rencana Fallback Bila SMT Gagal

### Opsi Fallback A: Ubah Tipe Kolom di Backend ke Int32 (Paling Aman)

Jika `TimestampConverter` tidak bisa mengenali format Debezium, ubah 1 baris di `DataWarehouseService.java` line ~2491 dari:
```java
if (lowerName.contains("date") || jdbcType == java.sql.Types.DATE) return "Date";
```
menjadi:
```java
if (lowerName.contains("date") || jdbcType == java.sql.Types.DATE) return "Int32";
```
Debezium mengirim jumlah hari sejak epoch sebagai Int32. ClickHouse bisa konversi: `toDate(int32_col)`.

Efek samping: kolom tgl di ClickHouse jadi Int32, perlu update Materialized View atau query pakai `toDate(tgl_fak_jl)`.

### Opsi Fallback B: Hapus SMT, Gunakan `date.mode` di Debezium Config

Tambahkan konfigurasi berikut ke source connector (via REST API):
```json
{
  "converters": "date",
  "date.type": "org.apache.kafka.connect.transforms.TimestampConverter$Value",
  "date.format.date": "yyyy-MM-dd"
}
```

### Opsi Fallback C: Hapus SMT, Biarkan Sebagai Int32, Fix di Sink

Hapus SMT formatDate dari semua connector, lalu di sink connector tambahkan cast agar Int32 dikonversi sebelum masuk ClickHouse.

**Script untuk hapus SMT dari semua connector:**
```bash
ssh -i ~/Downloads/awanda.pem -p 8822 -o StrictHostKeyChecking=no awanda@94.237.69.119 "python3 - << 'EOF'
import requests
CONNECT_URL = 'http://localhost:8083/connectors'
for conn in ['source-p001_ssi_erp-shared', 'source-p003_mkn_erp-shared', 'source-p011_bpi_erp-shared']:
    config = requests.get(f'{CONNECT_URL}/{conn}/config').json()
    tx = [t for t in config.get('transforms','').split(',') if not t.startswith('formatDate')]
    config['transforms'] = ','.join(tx)
    for k in list(config.keys()):
        if 'formatDate' in k:
            del config[k]
    r = requests.put(f'{CONNECT_URL}/{conn}/config', json=config)
    print(f'{conn}: HTTP {r.status_code}')
EOF
"
```

---

## 📝 Catatan Penting

- ❌ **Jangan pernah redeploy pipeline** (instruksi user)
- ❌ **Jangan gunakan version arbitrary (99999999)** untuk insert manual — selalu `MAX(version) + 1`
- ✅ Script backfill ada di: scratch/ directory di artifacts
- ✅ Server: `94.237.69.119`, SSH key: `~/Downloads/awanda.pem`, port `8822`
- ✅ ClickHouse: password `click!EnergyData@202608`, database target: `dw_erp`

---

## 🔧 Script Diagnosa Cepat

**Cek status semua source connector:**
```bash
ssh -i ~/Downloads/awanda.pem -p 8822 -o StrictHostKeyChecking=no awanda@94.237.69.119 \
"for c in source-p001_ssi_erp-shared source-p003_mkn_erp-shared source-p011_bpi_erp-shared; do
  STATE=\$(curl -s http://localhost:8083/connectors/\$c/status | python3 -c \"import sys,json; d=json.load(sys.stdin); t=d.get('tasks',[{}])[0]; print(d['connector']['state'], t.get('state','?'), t.get('trace','OK')[:100] if t.get('trace') else 'OK')\")
  echo \"\$c: \$STATE\"
done"
```

**Cek apakah SMT formatDate sudah terpasang:**
```bash
ssh -i ~/Downloads/awanda.pem -p 8822 -o StrictHostKeyChecking=no awanda@94.237.69.119 \
"curl -s http://localhost:8083/connectors/source-p001_ssi_erp-shared/config | python3 -c \"import sys,json; d=json.load(sys.stdin); print(d.get('transforms',''))\""
```

**Cek data terbaru di ClickHouse untuk memantau tanggal:**
```bash
ssh -i ~/Downloads/awanda.pem -p 8822 -o StrictHostKeyChecking=no awanda@94.237.69.119 \
"clickhouse-client --password 'click!EnergyData@202608' --query=\"SELECT no_fak_jl, tgl_fak_jl, toYear(tgl_fak_jl) FROM dw_erp.trh_penjualan FINAL WHERE is_deleted=0 ORDER BY version DESC LIMIT 10\""
```
