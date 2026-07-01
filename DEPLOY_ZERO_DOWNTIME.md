# Panduan Deploy (Update Kode) dengan Zero Downtime

Dokumen ini berisi langkah-langkah untuk mengambil update terbaru dari Git (`git pull`) dan merilisnya ke server **tanpa menimbulkan jeda mati (Zero Downtime)** pada pengguna yang sedang aktif (misalnya yang sedang melakukan *compare* data).

Kita menggunakan **Docker Swarm** yang dikombinasikan dengan *Graceful Shutdown* di Spring Boot untuk mencapai hal ini. Sistem ini mengatasi masalah port bentrok dan memastikan perpindahan antar versi sangat halus.

---

## ⚠️ Fase Transisi: PERTAMA KALI Migrasi ke Swarm

Hanya lakukan langkah ini **SATU KALI SAJA** jika servermu saat ini masih menjalankan aplikasi menggunakan perintah `docker-compose up -d`. Langkah ini akan menimbulkan sedikit downtime sesaat untuk mengganti pondasi ke Swarm.

1. Tarik pembaruan kode terbaru:
   ```bash
   git pull origin main
   ```
2. Matikan sistem lama (untuk mencegah port bentrok):
   ```bash
   docker-compose down
   ```
3. Aktifkan mode Docker Swarm:
   ```bash
   docker swarm init
   ```
   *(Abaikan peringatan jika Swarm sudah aktif sebelumnya).*
4. Build ulang image aplikasi:
   ```bash
   docker-compose build
   ```
5. Deploy sistem baru ke Swarm (menggunakan trik config untuk membaca `.env`):
   ```bash
   docker-compose config | docker stack deploy -c - darkosync
   ```

Selamat! Mulai detik ini, aplikasimu sudah berjalan di atas infrastruktur Swarm dan siap untuk update *Zero Downtime* di masa depan.

---

## 🚀 Langkah Rutin: Update di Kemudian Hari (Zero Downtime)

Untuk update-update selanjutnya (misal besok ada rilis fitur baru), kamu **TIDAK PERLU LAGI** menjalankan `docker-compose down`. Cukup jalankan 3 perintah ini secara berurutan:

```bash
# 1. Ambil kode terbaru dari Git
git pull origin main

# 2. Build ulang image dengan kode terbaru secara lokal
docker-compose build

# 3. Deploy dan ganti versi secara otomatis tanpa memutus user
docker-compose config | docker stack deploy -c - darkosync
```

---

## 🔍 Apa yang Terjadi di Balik Layar Saat Update Rutin?

Ketika perintah deploy di langkah rutin dijalankan:
1. Docker Swarm tidak akan mematikan versi lama secara tiba-tiba.
2. Swarm akan menyalakan container versi **baru** di latar belakang.
3. Setelah versi **baru** siap, *Load Balancer* internal Docker akan otomatis membelokkan lalu lintas masuk (user baru) ke versi yang baru.
4. Swarm lalu mengirimkan perintah berhenti ke versi **lama**.
5. Karena kita sudah mengaktifkan *Graceful Shutdown*, versi **lama** akan menunda proses matinya jika masih ada pengguna yang belum selesai (*misal: sedang menunggu proses compare data*), dengan toleransi waktu hingga 30 menit.
6. Versi lama baru akan benar-benar mati dengan sendirinya setelah semua pekerjaan penggunanya tuntas.

Semua proses transisi ini **100% transparan dan tidak terasa** oleh pengguna!
