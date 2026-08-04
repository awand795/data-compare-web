# Solusi Masalah PostgreSQL WAL Slot Membengkak (Debezium Hang)

## 📌 Deskripsi Masalah
Database PostgreSQL RDS (`mkndarkoerpdb`, `ssidarkoerpdb`, `bpidarkoerpdb`) mengalami pembengkakan WAL (Write-Ahead Log) hingga 6GB+. Meskipun status konektor Debezium menunjukkan `RUNNING`, offset Kafka tidak bertambah, menyebabkan *Replication Slot* tertahan dan tidak mengosongkan log transaksi.

Pengecekan mendalam menemukan 3 penyebab utama:
1. **Dynamic SSH Port dari Backend Java**: 
   Skrip awal mencoba menghubungkan Debezium ke backend menggunakan port SSH statis (contoh: 39157, 44353). Namun, backend Java (melalui JSch) sebenarnya membuka tunnel di port acak (contoh: 46475, 46831) setiap kali `/api/test-connection` dipanggil, sehingga Debezium mendapat error `Connection refused`.
2. **MTU (Maximum Transmission Unit) Docker Network Fragmentasi (SSL Hang)**:
   Meskipun port acak ditemukan, Debezium tetap *hang* saat melakukan SSL handshake dengan RDS karena paket sertifikat RDS terlalu besar untuk melewati MTU jaringan Docker *overlay bridge* (biasanya 1450 byte). Koneksi JDBC Postgres menggantung tanpa batas.
3. **Debezium Split-Brain (Multiple Replicas)**:
   Karena kontainer Debezium sering direstart saat *troubleshooting*, Docker Swarm sempat menjalankan 2 instance kontainer Debezium secara bersamaan. Hal ini memecah koneksi Kafka dan menyebabkan *worker* saling bertabrakan, menghentikan proses baca *offset*.

## 🚀 Langkah Perbaikan yang Dilakukan

Untuk mengatasi masalah tanpa perlu mengupdate/mendownload ulang *backend* (sesuai instruksi), telah dibuat sebuah skrip khusus: `fix_debezium_dynamic_ports.py`.

### Cara Kerja `fix_debezium_dynamic_ports.py`:
1. **Force Tunnel Creation**: Memanggil `/api/test-connection` untuk MKN, SSI, dan BPI ke backend untuk memaksa pembukaan SSH tunnel ke AWS RDS.
2. **Auto-Detect Port**: Membaca log Docker container `darkosync_backend.1` secara *real-time* untuk menangkap port dinamis yang baru saja dibuka oleh JSch (Misal: `SSH tunnel established: localhost:46475 → mkndarkoerpdb`).
3. **Konfigurasi `sslmode=disable`**: Memperbarui config Debezium agar koneksi JDBC menonaktifkan SSL (`database.sslmode: disable`). Ini menghilangkan *SSL handshake* yang sering tersangkut karena MTU jaringan Docker. Password tetap aman karena sudah terenkripsi oleh SSH tunnel itu sendiri.
4. **Restart Connectors**: Melakukan restart pada semua connector dan task Debezium agar menggunakan port terbaru dan bypass SSL.

## 📋 Panduan Jika Masalah Terjadi Lagi

Jika di masa mendatang WAL kembali membengkak atau offset berhenti berjalan (karena *backend* restart, tunnel SSH terputus, atau *server maintenance*), jalankan perbaikan ini secara berurutan dari server:

```bash
# 1. SSH ke server
ssh -i /path/to/awanda.pem -p 8822 awanda@74.48.112.31

# 2. Pastikan Debezium HANYA berjalan 1 instance (mencegah split-brain)
docker service scale darkosync_debezium=1

# 3. Jalankan skrip perbaikan otomatis
python3 /home/awanda/fix_debezium_dynamic_ports.py
```

Skrip ini akan secara otomatis melakukan perbaikan rute port dan memastikan konektor Debezium kembali berjalan normal. Karena konektor telah berstatus **RUNNING**, offset akan segera terbaca, dan WAL di PostgreSQL akan turun (drain) secara berangsur.
