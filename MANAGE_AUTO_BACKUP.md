# Panduan Mengelola Auto Backup Database (Task Scheduler)

Sistem *auto backup* telah dikonfigurasi menggunakan **Windows Task Scheduler** dengan nama tugas: **`DarkoSync_DB_Backup`**.

Tugas ini berjalan otomatis di *background* setiap hari pada jam **09:00 Pagi** dan **04:00 Sore**. 

---

## 1. Bagaimana Jika Laptop Mati atau Di-Restart?

- **Bersifat permanen / otomatis**: Jika laptop Anda dimatikan (Shutdown) atau di-restart, jadwal ini **tidak akan terhapus**. Saat laptop dinyalakan kembali dan waktu menunjukkan jadwal yang telah ditentukan, proses akan tetap berjalan secara otomatis di belakang layar.
- **Jika laptop mati tepat pada jam jadwal**: Apabila laptop Anda sedang mati persis pada pukul 09:00 atau 16:00, maka *backup* pada jam tersebut akan terlewat (*skip*). Sistem tidak akan melakukan *backup* susulan, tetapi akan kembali berjalan normal pada jadwal berikutnya saat laptop sudah menyala.

---

## 2. Cara Mematikan Sementara (Disable) Auto Backup

Jika Anda ingin menghentikan *auto backup* untuk sementara waktu tanpa menghapusnya, Anda bisa menggunakan salah satu dari dua cara berikut:

### Opsi A: Menggunakan PowerShell (Paling Cepat)
1. Buka aplikasi **PowerShell** (bisa dicari melalui menu Start Windows).
2. Salin dan tempel perintah berikut, lalu tekan Enter:
   ```powershell
   Disable-ScheduledTask -TaskName "DarkoSync_DB_Backup"
   ```

### Opsi B: Menggunakan Tampilan Aplikasi (GUI)
1. Tekan tombol `Windows`, lalu ketik **Task Scheduler** dan buka aplikasinya.
2. Di panel sebelah kiri, klik folder **Task Scheduler Library**.
3. Di daftar bagian tengah, cari tugas yang bernama **`DarkoSync_DB_Backup`**.
4. Klik kanan pada tugas tersebut, lalu pilih **Disable**. 

*(Catatan: Untuk menyalakannya kembali sewaktu-waktu, lakukan langkah yang sama namun pilih **Enable**, atau jalankan perintah `Enable-ScheduledTask -TaskName "DarkoSync_DB_Backup"`).*

---

## 3. Cara Menghapus (Delete) Auto Backup Secara Permanen

Jika Anda sudah tidak membutuhkan sistem *auto backup* ini lagi, ikuti langkah berikut untuk mencabutnya dari sistem:

### Opsi A: Menggunakan PowerShell
Buka aplikasi PowerShell dan jalankan perintah ini:
```powershell
Unregister-ScheduledTask -TaskName "DarkoSync_DB_Backup" -Confirm:$false
```

### Opsi B: Menggunakan Tampilan Aplikasi (GUI)
1. Buka aplikasi **Task Scheduler**.
2. Masuk ke **Task Scheduler Library**.
3. Cari **`DarkoSync_DB_Backup`**, klik kanan, lalu pilih **Delete** dan klik *Yes* untuk konfirmasi.
