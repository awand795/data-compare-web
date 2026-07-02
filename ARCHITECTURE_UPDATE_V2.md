# Arsitektur & Deployment V2 (Docker Hub & Zero Downtime)

Dokumen ini merangkum pembaruan arsitektur besar yang dilakukan untuk meningkatkan keandalan sistem (*bulletproof*) saat menangani sinkronisasi data (*streaming*) dalam jumlah masif, serta penyederhanaan alur *deployment* menggunakan Docker Hub.

---

## 1. Perombakan Alur Deployment (Docker Hub CI/CD)

Kita telah beralih dari mem-*build* image secara lokal di server menjadi menggunakan **Docker Hub** (`awandadarkotech`). Pembaruan ini memberikan beberapa keuntungan:
- **Server Jauh Lebih Ringan:** Server tidak perlu lagi melakukan proses kompilasi (`build`) yang menguras CPU dan RAM.
- **Deteksi Pembaruan Otomatis:** Docker Swarm sekarang dapat melacak perubahan *image digest* langsung dari Docker Hub.
- **Alur Kerja Terpisah:** Proses pengembangan (*Build & Push*) dilakukan di laptop, sedangkan server hanya bertugas menerima rilis (*Pull & Deploy*).

### Skrip Otomatisasi: `deploy.sh`
Alur *deployment* di server kini diotomatisasi sepenuhnya lewat `deploy.sh`. 
Jika dulunya Anda harus mengetik perintah panjang, kini Anda cukup login ke server dan menjalankan:
```bash
git pull origin main
./deploy.sh
```
Skrip ini menggunakan parameter `--with-registry-auth` agar Swarm memiliki otorisasi untuk mengecek versi terbaru di Docker Hub dan melakukan pergantian kontainer tanpa mematikan layanan (*Zero Downtime*).

---

## 2. Ketahanan Koneksi Database (HikariCP Bulletproof)

Masalah utama sebelumnya adalah koneksi database yang terputus tiba-tiba (*Socket Closed*) di tengah proses komparasi data karena kapasitas *pool* yang kecil dan aturan evakuasi (*eviction*) yang agresif.

Berikut 3 perbaikan utama di sisi *Connection Manager*:

1. **Smart Eviction (Reference Counting):**
   Fungsi pembersihan *pool* (eviction) kini dilengkapi dengan pengaman `Semaphore`. Jika sebuah koneksi sedang dipakai oleh *DataComparisonService* untuk proses sinkronisasi panjang, sistem **akan menolak untuk menutupnya**. Penutupan ditunda sampai *job* benar-benar selesai.

2. **Perluasan Kapasitas Pool:**
   - **`MAX_POOL_CACHE`** dinaikkan dari `3` menjadi `100`. (Menampung memori koneksi dari banyak database).
   - **`setMaximumPoolSize`** dinaikkan dari `10` menjadi `20`. (Mencegah *bottleneck* saat menyinkronkan banyak tabel secara bersamaan/paralel dari satu database).

3. **Penghapusan Deteksi Kebocoran (Leak Detection):**
   Fitur `setLeakDetectionThreshold` yang tadinya disetel 2 menit, kini **dimatikan (0)**. Ini dilakukan karena proses sinkronisasi wajar memakan waktu lebih dari 2 menit. Dengan mematikannya, kita menyelamatkan *log* server dari *false alarm* ("Connection Leak") yang bisa membuat hardisk penuh.

---

## 3. Garansi Zero Downtime 100%

Anda tidak perlu khawatir melakukan `./deploy.sh` berulang kali meskipun masih ada pengguna yang sedang menjalankan proses sinkronisasi (*compare data*) di latar belakang.

- **Start-First:** Swarm akan selalu menyalakan kontainer versi terbaru (V2) terlebih dahulu.
- **Graceful Shutdown:** Kontainer lama (V1) akan mendapat sinyal untuk berhenti menerima *request* baru. Namun, V1 akan **tetap hidup** (hingga batas toleransi 30 menit) demi menyelesaikan sisa pekerjaan komparasi yang sedang berlangsung.
- **Efek Samping Terkontrol:** Satu-satunya hal yang terjadi jika Anda sering mendeploy adalah penumpukan konsumsi RAM server sementara (karena V1 dan V2 hidup bersamaan sampai V1 selesai), yang mana hal ini sangat wajar dan aman dalam arsitektur berskala besar.
