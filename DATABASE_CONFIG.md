# Internal Database Configuration

Aplikasi ini menggunakan database internal untuk menyimpan metadata seperti koneksi (connections), jadwal sinkronisasi (schedules), dan template konfigurasi. 

Awalnya, aplikasi ini menggunakan **Neon** sebagai database internal, kemudian dimigrasi ke **Aiven**. Berikut adalah konfigurasi kredensial untuk kedua layanan tersebut sebagai referensi dokumentasi.

---

## 1. Aiven Database (Active)

Konfigurasi ini saat ini digunakan sebagai sumber data utama aplikasi.

- **Provider**: Aiven
- **Service URI**: `postgres://avnadmin:YOUR_AIVEN_PASSWORD@***REMOVED***:25789/defaultdb?sslmode=require`
- **Host**: `***REMOVED***`
- **Port**: `25789`
- **Database Name**: `defaultdb`
- **Username**: `avnadmin`
- **Password**: `YOUR_AIVEN_PASSWORD`
- **SSL Mode**: `require`

### Environment Variables
```env
DB_HOST=***REMOVED***
DB_PORT=25789
DB_NAME=defaultdb
DB_USER=avnadmin
DB_PASSWORD=YOUR_AIVEN_PASSWORD
```

---

## 2. Neon Database (Legacy)

Konfigurasi historis sebelum migrasi dilakukan. Kredensial ini mungkin masih dapat diakses tetapi tidak lagi digunakan sebagai database utama aplikasi.

- **Provider**: Neon
- **Host**: `***REMOVED***`
- **Port**: `5432`
- **Database Name**: `neondb`
- **Username**: `neondb_owner`
- **Password**: `YOUR_NEON_PASSWORD`
- **SSL Mode**: `require`

### Environment Variables
```env
DB_HOST=***REMOVED***
DB_PORT=5432
DB_NAME=neondb
DB_USER=neondb_owner
DB_PASSWORD=YOUR_NEON_PASSWORD
```
