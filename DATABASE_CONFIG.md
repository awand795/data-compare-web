# Internal Database Configuration

Aplikasi ini menggunakan database internal untuk menyimpan metadata seperti koneksi (connections), jadwal sinkronisasi (schedules), dan template konfigurasi. 

Awalnya, aplikasi ini menggunakan **Neon** sebagai database internal, kemudian dimigrasi ke **Aiven**. Berikut adalah konfigurasi kredensial untuk kedua layanan tersebut sebagai referensi dokumentasi.

---

## 1. Darkosuite Database (Active)

Konfigurasi ini saat ini digunakan sebagai sumber data utama aplikasi.

- **Provider**: Self-Hosted (YOUR_DB_HOST)
- **Service URI**: `postgres://postgres:YOUR_DARKOSUITE_PASSWORD@YOUR_DB_HOST:8832/data_setting_sync`
- **Host**: `YOUR_DB_HOST`
- **Port**: `8832`
- **Database Name**: `data_setting_sync`
- **Username**: `postgres`
- **Password**: `YOUR_DARKOSUITE_PASSWORD`

### Environment Variables
```env
DB_HOST=YOUR_DB_HOST
DB_PORT=8832
DB_NAME=data_setting_sync
DB_USER=postgres
DB_PASSWORD=YOUR_DARKOSUITE_PASSWORD
```

---

## 2. Aiven Database (Legacy)

Konfigurasi historis sebelum migrasi dilakukan.

- **Provider**: Aiven
- **Service URI**: `postgres://avnadmin:YOUR_AIVEN_PASSWORD@YOUR_AIVEN_HOST:25789/defaultdb?sslmode=require`
- **Host**: `YOUR_AIVEN_HOST`
- **Port**: `25789`
- **Database Name**: `defaultdb`
- **Username**: `avnadmin`
- **Password**: `YOUR_AIVEN_PASSWORD`
- **SSL Mode**: `require`

## 2. Neon Database (Legacy)

Konfigurasi historis sebelum migrasi dilakukan. Kredensial ini mungkin masih dapat diakses tetapi tidak lagi digunakan sebagai database utama aplikasi.

- **Provider**: Neon
- **Host**: `YOUR_NEON_HOST`
- **Port**: `5432`
- **Database Name**: `neondb`
- **Username**: `neondb_owner`
- **Password**: `YOUR_NEON_PASSWORD`
- **SSL Mode**: `require`

### Environment Variables
```env
DB_HOST=YOUR_NEON_HOST
DB_PORT=5432
DB_NAME=neondb
DB_USER=neondb_owner
DB_PASSWORD=YOUR_NEON_PASSWORD
```
