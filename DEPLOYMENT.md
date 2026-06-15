# Panduan Deployment & Update Docker (Server)

File ini berisi rangkuman perintah-perintah penting untuk mengelola aplikasi Darkosync (Data Compare Web) di server Linux Anda.

## 1. Pertama Kali Install (Clone)

Jika Anda baru pertama kali memasukkan aplikasi ini ke server:

```bash
cd /var/www
git clone https://github.com/awand795/data-compare-web.git
cd data-compare-web
sudo docker compose -p darkosync up -d --build
```

## 2. Cara Update / Tarik Perubahan Baru (Pull & Rebuild)

Gunakan perintah ini setiap kali ada perubahan kode di GitHub yang ingin diterapkan ke server Anda:

```bash
cd /var/www/data-compare-web
git pull
sudo docker compose -p darkosync up -d --build
```

> **Catatan:** Tambahan bendera `--build` di atas memastikan Docker membangun ulang (rebuild) image dengan kode terbaru. Docker akan otomatis mematikan kontainer lama dan menggantinya dengan yang baru tanpa mengganggu kontainer lain.

## 3. Cek Status Kontainer

Untuk melihat apakah aplikasi (Frontend/Backend) sedang berjalan (Up) atau mati (Exited):

```bash
sudo docker compose -p darkosync ps
```

## 4. Melihat Log Aplikasi (Error / Status)

Untuk melihat proses yang sedang berjalan di balik layar (misal melihat apakah Spring Boot sudah siap):

```bash
# Melihat log semua (frontend & backend) secara real-time
sudo docker compose -p darkosync logs -f

# Melihat log khusus Backend saja
sudo docker compose -p darkosync logs -f backend

# Melihat log khusus Frontend (Nginx) saja
sudo docker compose -p darkosync logs -f frontend
```
*(Tekan `Ctrl + C` untuk keluar dari layar log).*

## 5. Melihat Ukuran Image Docker

Untuk melihat berapa memori Hardisk (Storage) yang digunakan oleh aplikasi ini:

```bash
sudo docker images | grep darkosync
```

## 6. Menghentikan Aplikasi (Tanpa Menghapus Data)

Jika Anda ingin mematikan aplikasinya sementara waktu:

```bash
cd /var/www/data-compare-web
sudo docker compose -p darkosync down
```

---

**Tips Tambahan:**
Jika Anda merasa repot harus mengetik `sudo` setiap saat, Anda bisa memberikan hak akses Docker secara permanen ke user Anda (`awanda`) dengan perintah berikut:

```bash
sudo usermod -aG docker $USER
newgrp docker
```
Setelah itu, Anda bisa menghilangkan kata `sudo` pada semua perintah di atas.
