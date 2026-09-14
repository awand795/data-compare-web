package com.dbdiff.controller;

import net.coobird.thumbnailator.Thumbnails;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Universal Storage Engine & Bucket Manager
 * -------------------------------------------
 * Provides industrial-grade file storage with:
 * - Dynamic Bucket validation (format whitelist, max size in MB)
 * - Automatic image downscaling & compression via Thumbnailator
 * - Magic-byte security inspection via Apache Tika
 * - Path traversal protection
 * - UI bucket management table in sch_sync.storage_buckets
 */
@RestController
@RequestMapping("/api/storage")
@CrossOrigin(origins = "*")
public class StorageController {

    private static final Logger log = LoggerFactory.getLogger(StorageController.class);

    private final Path rootStorageLocation;
    private final Tika tika = new Tika();

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    public StorageController(@Value("${storage.location:./storage}") String storagePath) {
        this.rootStorageLocation = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootStorageLocation);
            log.info("Storage engine initialized at: {}", this.rootStorageLocation);
        } catch (IOException e) {
            log.error("Could not initialize storage directory: {}", e.getMessage());
        }
    }

    private synchronized void ensureTable() {
        if (jdbcTemplate == null) return;
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sch_sync.storage_buckets (
                    name                  VARCHAR(100) PRIMARY KEY,
                    description           TEXT,
                    allowed_extensions    VARCHAR(255) DEFAULT 'jpg,jpeg,png,webp',
                    max_size_mb           INT DEFAULT 10,
                    auto_compress         BOOLEAN DEFAULT TRUE,
                    max_width             INT DEFAULT 1920,
                    max_height            INT DEFAULT 1920,
                    image_quality         DOUBLE PRECISION DEFAULT 0.82,
                    is_public             BOOLEAN DEFAULT TRUE,
                    custom_error_message  TEXT,
                    created_at            TIMESTAMP DEFAULT NOW(),
                    updated_at            TIMESTAMP DEFAULT NOW()
                )
            """);

            // Seed default buckets if empty
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sch_sync.storage_buckets", Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.update("""
                    INSERT INTO sch_sync.storage_buckets 
                    (name, description, allowed_extensions, max_size_mb, auto_compress, max_width, max_height, image_quality, is_public)
                    VALUES 
                    ('foto_kendaraan', 'Foto kondisi armada saat check-in/out', 'jpg,jpeg,png,webp', 10, true, 1920, 1920, 0.82, true),
                    ('foto_barang', 'Foto barang bawaan saat kunjungan', 'jpg,jpeg,png,webp', 10, true, 1920, 1920, 0.82, true),
                    ('dokumen_armada', 'Dokumen STNK, BPKB, KIR, Asuransi armada', 'pdf,doc,docx,xls,xlsx,jpg,png', 25, false, 1920, 1920, 1.0, true),
                    ('general', 'Penyimpanan umum multi-format', '*', 50, false, 1920, 1920, 1.0, true)
                    ON CONFLICT DO NOTHING
                """);
            }
        } catch (Exception e) {
            log.warn("Storage buckets table check failed: {}", e.getMessage());
        }
    }

    // ── BUCKET MANAGEMENT ENDPOINTS ─────────────────────────────────────────

    @GetMapping("/buckets")
    public ResponseEntity<?> listBuckets() {
        ensureTable();
        try {
            List<Map<String, Object>> buckets = jdbcTemplate.queryForList(
                "SELECT * FROM sch_sync.storage_buckets ORDER BY name"
            );

            List<Map<String, Object>> enriched = new ArrayList<>();
            for (Map<String, Object> b : buckets) {
                Map<String, Object> item = new HashMap<>(b);
                String bName = String.valueOf(b.get("name"));
                Path bFolder = rootStorageLocation.resolve(bName).normalize();
                int fileCount = 0;
                long totalBytes = 0;
                if (Files.exists(bFolder) && Files.isDirectory(bFolder)) {
                    try (var stream = Files.list(bFolder)) {
                        for (Path p : stream.toList()) {
                            if (Files.isRegularFile(p)) {
                                fileCount++;
                                totalBytes += Files.size(p);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                item.put("file_count", fileCount);
                item.put("total_bytes", totalBytes);
                item.put("total_size_formatted", formatFileSize(totalBytes));
                enriched.add(item);
            }

            return ResponseEntity.ok(enriched);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/buckets")
    public ResponseEntity<?> saveBucket(@RequestBody Map<String, Object> body) {
        ensureTable();
        try {
            String name = String.valueOf(body.get("name")).trim().toLowerCase().replaceAll("[^a-z0-9_-]", "_");
            if (name.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Nama bucket wajib diisi"));
            }

            String desc = body.getOrDefault("description", "").toString();
            String allowedExt = body.getOrDefault("allowed_extensions", "jpg,jpeg,png,webp").toString().toLowerCase();
            int maxSizeMb = Integer.parseInt(body.getOrDefault("max_size_mb", 10).toString());
            boolean autoCompress = Boolean.parseBoolean(body.getOrDefault("auto_compress", true).toString());
            int maxWidth = Integer.parseInt(body.getOrDefault("max_width", 1920).toString());
            int maxHeight = Integer.parseInt(body.getOrDefault("max_height", 1920).toString());
            double quality = Double.parseDouble(body.getOrDefault("image_quality", 0.82).toString());
            boolean isPublic = Boolean.parseBoolean(body.getOrDefault("is_public", true).toString());
            String customErrMsg = body.getOrDefault("custom_error_message", "").toString();

            jdbcTemplate.update("""
                INSERT INTO sch_sync.storage_buckets
                (name, description, allowed_extensions, max_size_mb, auto_compress, max_width, max_height, image_quality, is_public, custom_error_message, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (name) DO UPDATE SET
                    description = EXCLUDED.description,
                    allowed_extensions = EXCLUDED.allowed_extensions,
                    max_size_mb = EXCLUDED.max_size_mb,
                    auto_compress = EXCLUDED.auto_compress,
                    max_width = EXCLUDED.max_width,
                    max_height = EXCLUDED.max_height,
                    image_quality = EXCLUDED.image_quality,
                    is_public = EXCLUDED.is_public,
                    custom_error_message = EXCLUDED.custom_error_message,
                    updated_at = NOW()
            """, name, desc, allowedExt, maxSizeMb, autoCompress, maxWidth, maxHeight, quality, isPublic, customErrMsg);

            // Pre-create bucket folder
            Path folder = rootStorageLocation.resolve(name).normalize();
            Files.createDirectories(folder);

            return ResponseEntity.ok(Map.of("success", true, "message", "Bucket '" + name + "' berhasil disimpan."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/buckets/{name}")
    public ResponseEntity<?> deleteBucket(@PathVariable String name) {
        ensureTable();
        try {
            int deleted = jdbcTemplate.update("DELETE FROM sch_sync.storage_buckets WHERE name = ?", name);
            if (deleted == 0) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(Map.of("success", true, "message", "Bucket '" + name + "' dihapus."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/buckets/{name}/files")
    public ResponseEntity<?> listFiles(@PathVariable String name) {
        try {
            String safeName = name.replaceAll("[^a-zA-Z0-9_-]", "");
            Path folder = rootStorageLocation.resolve(safeName).normalize();
            if (!Files.exists(folder) || !Files.isDirectory(folder)) {
                return ResponseEntity.ok(Collections.emptyList());
            }

            List<Map<String, Object>> files = new ArrayList<>();
            try (var stream = Files.list(folder)) {
                for (Path p : stream.toList()) {
                    if (Files.isRegularFile(p)) {
                        BasicFileAttributes attr = Files.readAttributes(p, BasicFileAttributes.class);
                        Map<String, Object> fileInfo = new HashMap<>();
                        String filename = p.getFileName().toString();
                        fileInfo.put("filename", filename);
                        fileInfo.put("url", "/api/storage/files/" + safeName + "/" + filename);
                        fileInfo.put("size", attr.size());
                        fileInfo.put("size_formatted", formatFileSize(attr.size()));
                        fileInfo.put("created_at", LocalDateTime.ofInstant(attr.creationTime().toInstant(), ZoneId.systemDefault()).toString());
                        fileInfo.put("modified_at", LocalDateTime.ofInstant(attr.lastModifiedTime().toInstant(), ZoneId.systemDefault()).toString());
                        String mime = Files.probeContentType(p);
                        fileInfo.put("mime_type", mime != null ? mime : "application/octet-stream");
                        files.add(fileInfo);
                    }
                }
            }
            files.sort((a, b) -> String.valueOf(b.get("modified_at")).compareTo(String.valueOf(a.get("modified_at"))));
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── UPLOAD ENGINE WITH VALIDATION & COMPRESSION ─────────────────────────

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "bucket", required = false) String bucketParam,
            @RequestParam(value = "folder", defaultValue = "general") String folderParam) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "File tidak boleh kosong"));
        }

        ensureTable();

        String rawBucket = (bucketParam != null && !bucketParam.trim().isEmpty()) ? bucketParam : folderParam;
        String bucket = rawBucket.replaceAll("[^a-zA-Z0-9_-]", "").toLowerCase();
        if (bucket.isEmpty()) bucket = "general";

        // 1. Load bucket configuration
        Map<String, Object> bucketConfig = null;
        if (jdbcTemplate != null) {
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM sch_sync.storage_buckets WHERE name = ?", bucket
                );
                if (!rows.isEmpty()) bucketConfig = rows.get(0);
            } catch (Exception ignored) {}
        }

        int maxSizeMb = bucketConfig != null && bucketConfig.get("max_size_mb") != null
            ? ((Number) bucketConfig.get("max_size_mb")).intValue() : 25;
        String allowedExtStr = bucketConfig != null && bucketConfig.get("allowed_extensions") != null
            ? String.valueOf(bucketConfig.get("allowed_extensions")).toLowerCase() : "*";
        boolean autoCompress = bucketConfig == null || Boolean.parseBoolean(String.valueOf(bucketConfig.getOrDefault("auto_compress", "true")));
        int maxWidth = bucketConfig != null && bucketConfig.get("max_width") != null ? ((Number) bucketConfig.get("max_width")).intValue() : 1920;
        int maxHeight = bucketConfig != null && bucketConfig.get("max_height") != null ? ((Number) bucketConfig.get("max_height")).intValue() : 1920;
        double quality = bucketConfig != null && bucketConfig.get("image_quality") != null ? ((Number) bucketConfig.get("image_quality")).doubleValue() : 0.82;
        String customErrMsg = bucketConfig != null && bucketConfig.get("custom_error_message") != null ? String.valueOf(bucketConfig.get("custom_error_message")).trim() : "";

        // 2. Validate Size Limit
        long maxSizeBytes = (long) maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxSizeBytes) {
            String msg = !customErrMsg.isEmpty() ? customErrMsg 
                : "Ukuran file (" + formatFileSize(file.getSize()) + ") melebihi batas maksimal yang diizinkan (" + maxSizeMb + " MB).";
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", msg));
        }

        // 3. Extract & Validate Extension
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.trim().isEmpty()) {
            originalName = "file_" + System.currentTimeMillis() + ".bin";
        }
        originalName = Paths.get(originalName).getFileName().toString();

        String extension = "";
        int dotIdx = originalName.lastIndexOf('.');
        if (dotIdx > 0) {
            extension = originalName.substring(dotIdx + 1).toLowerCase();
        }

        if (!allowedExtStr.equals("*") && !allowedExtStr.isBlank()) {
            Set<String> allowedSet = new HashSet<>(Arrays.asList(allowedExtStr.split("[,\\s|]+")));
            if (!allowedSet.contains(extension)) {
                String msg = !customErrMsg.isEmpty() ? customErrMsg
                    : "Format file '." + extension + "' tidak diizinkan. Format yang diterima: " + allowedExtStr.toUpperCase();
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", msg));
            }
        }

        // 4. Magic-Bytes MIME Inspection via Apache Tika
        String detectedMime = "application/octet-stream";
        try {
            detectedMime = tika.detect(file.getInputStream(), originalName);
        } catch (Exception e) {
            log.warn("Tika MIME detection error: {}", e.getMessage());
        }

        // Security check: Block executable or dangerous scripts
        String lowerMime = detectedMime.toLowerCase();
        if (lowerMime.contains("dosexec") || lowerMime.contains("x-executable") || 
            lowerMime.contains("x-sh") || lowerMime.contains("x-bat") || 
            lowerMime.contains("javascript") || lowerMime.contains("x-msdownload")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "success", false,
                "error", "File ditolak karena alasan keamanan (tipe file berbahaya terdeteksi)."
            ));
        }

        try {
            Path targetFolder = rootStorageLocation.resolve(bucket).normalize();
            Files.createDirectories(targetFolder);

            String baseName = dotIdx > 0 ? originalName.substring(0, dotIdx) : originalName;
            String cleanBase = baseName.replaceAll("[^a-zA-Z0-9_-]", "_");
            if (cleanBase.length() > 40) cleanBase = cleanBase.substring(0, 40);

            String extWithDot = extension.isEmpty() ? "" : "." + extension;
            String uniqueFilename = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "_" + cleanBase + extWithDot;
            Path destination = targetFolder.resolve(uniqueFilename).normalize();

            long originalBytes = file.getSize();
            long finalBytes = originalBytes;
            boolean wasCompressed = false;

            // 5. Image Compression & Resizing via Thumbnailator
            boolean isCompressibleImage = lowerMime.startsWith("image/") 
                && (extension.equals("jpg") || extension.equals("jpeg") || extension.equals("png") || extension.equals("webp"));

            if (autoCompress && isCompressibleImage) {
                try {
                    File destFile = destination.toFile();
                    Thumbnails.of(file.getInputStream())
                        .size(maxWidth, maxHeight)
                        .outputQuality(quality)
                        .toFile(destFile);

                    finalBytes = Files.size(destination);
                    wasCompressed = true;
                    log.info("Image compressed: {} -> {} bytes (saved {}%)", 
                        originalBytes, finalBytes, Math.round((1.0 - (double)finalBytes / originalBytes) * 100));
                } catch (Exception thumbEx) {
                    log.warn("Thumbnailator compression fallback to direct copy: {}", thumbEx.getMessage());
                    Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);
                    finalBytes = Files.size(destination);
                }
            } else {
                Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);
            }

            String publicUrl = "/api/storage/files/" + bucket + "/" + uniqueFilename;

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("url", publicUrl);
            response.put("filename", uniqueFilename);
            response.put("originalName", originalName);
            response.put("bucket", bucket);
            response.put("original_size", originalBytes);
            response.put("size", finalBytes);
            response.put("size_formatted", formatFileSize(finalBytes));
            response.put("compressed", wasCompressed);
            if (wasCompressed && originalBytes > 0) {
                int savedPct = (int) Math.round((1.0 - (double) finalBytes / originalBytes) * 100);
                response.put("saved_percentage", Math.max(0, savedPct) + "%");
            }
            response.put("mimeType", detectedMime);
            response.put("uploadedAt", LocalDateTime.now().toString());

            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("Upload failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Gagal menyimpan file: " + e.getMessage()));
        }
    }

    // ── SERVE FILE ──────────────────────────────────────────────────────────

    @GetMapping("/files/{folder}/{filename:.+}")
    public ResponseEntity<Resource> getFile(
            @PathVariable String folder,
            @PathVariable String filename) {

        try {
            String safeFolder = folder.replaceAll("[^a-zA-Z0-9_-]", "");
            Path folderPath = rootStorageLocation.resolve(safeFolder).normalize();
            Path filePath = folderPath.resolve(filename).normalize();

            // Prevent path traversal
            if (!filePath.startsWith(rootStorageLocation)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }

            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = tika.detect(filePath.toFile());
            }
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            boolean isViewable = contentType.startsWith("image/") 
                    || contentType.equals("application/pdf")
                    || contentType.startsWith("text/");

            String disposition = isViewable
                    ? "inline; filename=\"" + resource.getFilename() + "\""
                    : "attachment; filename=\"" + resource.getFilename() + "\"";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                    .body(resource);

        } catch (MalformedURLException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/files/{folder}/{filename:.+}")
    public ResponseEntity<?> deleteFile(
            @PathVariable String folder,
            @PathVariable String filename) {

        try {
            String safeFolder = folder.replaceAll("[^a-zA-Z0-9_-]", "");
            Path folderPath = rootStorageLocation.resolve(safeFolder).normalize();
            Path filePath = folderPath.resolve(filename).normalize();

            if (!filePath.startsWith(rootStorageLocation)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                return ResponseEntity.ok(Map.of("success", true, "message", "File berhasil dihapus"));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Gagal menghapus file: " + e.getMessage()));
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
