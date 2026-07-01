# Panduan Sinkronisasi Git Setelah Pembersihan (Force Push)

⚠️ **BACA INI JIKA ANDA BARU SAJA MEMBERSIHKAN HISTORY GIT** ⚠️

Karena repositori ini baru saja melalui proses "Pembersihan Sejarah" menggunakan BFG Repo-Cleaner (untuk menghapus *password* yang bocor di komit masa lalu), sejarah/history Git di server lokal Anda saat ini **berbeda** dengan yang ada di GitHub.

## ❌ JANGAN GUNAKAN `git pull`
Jika Anda menjalankan perintah `git pull` biasa saat ini, Git akan mencoba menggabungkan (*merge*) sejarah lama yang bocor dengan sejarah baru yang bersih. **Ini akan menyebabkan password yang sudah dihapus muncul kembali di repositori!**

## ✅ LAKUKAN INI SEBAGAI GANTINYA (Hanya 1x Ini Saja)

Untuk memaksa server Anda membuang sejarah lamanya yang kotor dan menjiplak persis versi bersih yang ada di GitHub, jalankan dua perintah ini secara berurutan di dalam folder proyek di server:

```bash
git fetch origin
git reset --hard origin/main
```

### Penjelasan Perintah:
1. `git fetch origin` : Mendownload status dan riwayat terbaru dari GitHub tanpa mengubah file lokal Anda.
2. `git reset --hard origin/main` : Memaksa file lokal dan riwayat komit Anda agar 100% sama identik dengan *branch* `main` yang ada di GitHub (membuang semua sisa-sisa komit lama yang kotor).

---

## 🚀 Langkah Selanjutnya

Setelah sinkronisasi paksa di atas berhasil dilakukan, Anda bisa melanjutkan langkah *deploy* seperti biasa:

```bash
docker-compose build
docker-compose config | docker stack deploy -c - darkosync
```

*Catatan: Untuk rilis fitur dan update-update berikutnya di masa depan, Anda sudah bisa kembali menggunakan perintah `git pull origin main` seperti biasa.*
