# Solusi Permanen: Mencegah Korupsi Tanggal (Date) di ClickHouse

Dokumen ini menjelaskan langkah-langkah untuk mengimplementasikan **Opsi B**, yaitu memperbaiki format tanggal langsung dari level **Debezium Source Connector** agar tidak dikirim sebagai _epoch milliseconds_, melainkan sebagai format `String` (ISO-8601, contoh: `"YYYY-MM-DD"`).

## ⚠️ Latar Belakang Masalah
Secara default, Debezium mengirimkan tipe data `DATE` dari PostgreSQL dalam bentuk _integer_ (jumlah hari atau milliseconds sejak epoch 1 Jan 1970). 
Ketika ClickHouse (yang memiliki tipe `Date` 16-bit) menerima angka _milliseconds_ yang sangat besar (misalnya `1786320000000` untuk tahun 2026), ClickHouse akan mengalami *integer overflow* (`modulo 65536`). Hasil sisa bagi tersebut diterjemahkan menjadi tanggal mundur, seperti tahun **1984** atau **1998**.

## 🛠️ Implementasi Opsi B (Format String via SMT)

Untuk mengubah output tanggal Debezium menjadi `String` sebelum masuk ke Kafka/ClickHouse, kita harus menggunakan **Single Message Transform (SMT)** bawaan Kafka Connect bernama `TimestampConverter`.

Konfigurasi SMT yang perlu ditambahkan ke konektor adalah seperti ini:
```json
"transforms": "...,formatDate",
"transforms.formatDate.type": "org.apache.kafka.connect.transforms.TimestampConverter$Value",
"transforms.formatDate.target.type": "string",
"transforms.formatDate.field": "tgl_ord_jl",
"transforms.formatDate.format": "yyyy-MM-dd"
```

### Penting: Modifikasi Backend (`DataWarehouseService.java`)
Karena konfigurasi Debezium *di-generate* secara otomatis oleh backend Spring Boot Anda setiap kali ada pembuatan/update pipeline, maka penambahan konfigurasi SMT ini **wajib** dimasukkan ke dalam logika Java di `DataWarehouseService.java`. Jika Anda hanya mengubahnya via Kafka Connect REST API, konfigurasi tersebut akan tertimpa (_overwrite_) saat deployment pipeline berikutnya.

**Langkah Perubahan di Kode Java:**
Anda perlu memodifikasi method pembuatan source connector (sekitar line 1000 pada `DataWarehouseService.java`) untuk melakukan iterasi pada field-field berjenis `DATE` atau `TIMESTAMP`, lalu secara dinamis menambahkan config `TimestampConverter` SMT ke list `transforms`.

Contoh konsep kodenya:
```java
// Saat mendeteksi ada kolom berjenis DATE, catat nama kolomnya, misalnya 'tgl_ord_jl'
sourceConfig.put("transforms", "...,formatDate");
sourceConfig.put("transforms.formatDate.type", "org.apache.kafka.connect.transforms.TimestampConverter$Value");
sourceConfig.put("transforms.formatDate.target.type", "string");
sourceConfig.put("transforms.formatDate.field", "tgl_ord_jl");
sourceConfig.put("transforms.formatDate.format", "yyyy-MM-dd");
```
*(Catatan: Jika ada lebih dari 1 kolom date, SMT harus di-chain, misal `formatDate1, formatDate2`)*.

---

### Perbandingan dengan Opsi A
Jika setelah dibaca Opsi B dirasa terlalu kompleks (karena mengharuskan injeksi SMT dinamis per-kolom di Java), Anda bisa beralih ke **Opsi A**, yaitu:
Cukup ubah 1 baris di `DataWarehouseService.java` (Line 2451) dari:
```java
if (lowerName.contains("date") || jdbcType == java.sql.Types.DATE) return "Date";
```
Menjadi:
```java
if (lowerName.contains("date") || jdbcType == java.sql.Types.DATE) return "String";
```
Lalu di sisi *Materialized View*, ubah string tersebut menjadi Date: `toDate(...)`. Opsi A jauh lebih mudah diimplementasikan karena tidak menyentuh konfigurasi infrastruktur Kafka Connect.

Silakan berikan instruksi kepada agen AI Anda di sesi berikutnya terkait pendekatan mana yang akhirnya dipilih.
