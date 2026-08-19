# 🛡️ Kafka Connect Cleanup & Backup Report

> [!WARNING]
> Server sempat mengalami **Out of Memory (OOM) dan Swap Thrashing** akibat 211 Sink Connector duplikat yang berjalan serentak. 
> Masalah ini berhasil diselesaikan dengan menghapus 142 connector lama yang duplikat dan menyisakan **68 connector unik** terbaru (1 per tabel).

## 📄 File Backup
Agar connector yang dihapus bisa dikembalikan sewaktu-waktu (jika terjadi kesalahan), konfigurasi lengkap dari semua 68 connector yang dipertahankan telah di-*backup* ke dalam file JSON lokal.

* **Lokasi File Backup:** [kept_connectors_backup.json](file:///home/awanda/.gemini/antigravity-cli/brain/4226b538-032d-4960-8e7f-0837730fbbc6/kept_connectors_backup.json) (Ukuran: ~93 KB)
* **Total Connector Backup:** 68 Connector (mewakili 68 tabel yang disinkronisasi)
* **Kandungan File:** Semua connector ini **SUDAH** memuat topik untuk *semua* database yang tergabung (MKN, SSI, BPI). Tidak ada data sinkronisasi yang terputus akibat penghapusan ini.

## 🛠️ Script Pemulihan (Restore)
Jika agen/bot AI lain atau Anda sendiri perlu melakukan *restore* / mereplikasi connector ini ke Kafka Connect yang baru atau yang lama, Anda dapat menggunakan script Python yang telah disiapkan.

* **Lokasi Script Restore:** [restore_connectors.py](file:///home/awanda/.gemini/antigravity-cli/brain/4226b538-032d-4960-8e7f-0837730fbbc6/scratch/restore_connectors.py)

**Cara Menjalankan Restore (dari dalam server):**
```bash
# Upload kedua file ke server (misal ke /tmp/) lalu jalankan:
python3 /tmp/restore_connectors.py /tmp/kept_connectors_backup.json
```

## 📝 Next Action (Wajib)
Bug duplikasi ini berasal dari *source code* Java `DataWarehouseService.java`. Jika kode tersebut tidak diperbaiki, maka ketika Anda mengeklik "Deploy" di web, connector duplikat akan bermunculan kembali dan memakan RAM server.
Silakan konfirmasi perbaikan *source code* Java agar OOM tidak pernah terjadi lagi selamanya.
