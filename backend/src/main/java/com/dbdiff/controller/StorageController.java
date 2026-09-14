package com.dbdiff.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/storage")
@CrossOrigin(origins = "*")
public class StorageController {

    private static final Logger log = LoggerFactory.getLogger(StorageController.class);

    private final Path rootStorageLocation;

    public StorageController(@Value("${storage.location:./storage}") String storagePath) {
        this.rootStorageLocation = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootStorageLocation);
            log.info("Storage engine initialized at: {}", this.rootStorageLocation);
        } catch (IOException e) {
            log.error("Could not initialize storage directory: {}", e.getMessage());
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folder", defaultValue = "general") String folder) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "File tidak boleh kosong"));
        }

        // Sanitize folder name
        String safeFolder = folder.replaceAll("[^a-zA-Z0-9_-]", "");
        if (safeFolder.isEmpty()) safeFolder = "general";

        try {
            Path targetFolder = rootStorageLocation.resolve(safeFolder).normalize();
            Files.createDirectories(targetFolder);

            String originalName = file.getOriginalFilename();
            if (originalName == null || originalName.trim().isEmpty()) {
                originalName = "file_" + System.currentTimeMillis() + ".bin";
            }
            originalName = Paths.get(originalName).getFileName().toString();

            String extension = "";
            int dotIdx = originalName.lastIndexOf('.');
            if (dotIdx > 0) {
                extension = originalName.substring(dotIdx).toLowerCase();
            }
            String baseName = dotIdx > 0 ? originalName.substring(0, dotIdx) : originalName;
            String cleanBase = baseName.replaceAll("[^a-zA-Z0-9_-]", "_");
            if (cleanBase.length() > 50) {
                cleanBase = cleanBase.substring(0, 50);
            }

            String uniqueFilename = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "_" + cleanBase + extension;
            Path destination = targetFolder.resolve(uniqueFilename).normalize();

            Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);

            String publicUrl = "/api/storage/files/" + safeFolder + "/" + uniqueFilename;

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("url", publicUrl);
            response.put("filename", uniqueFilename);
            response.put("originalName", originalName);
            response.put("folder", safeFolder);
            response.put("size", file.getSize());
            String detectedMime = file.getContentType();
            if (detectedMime == null || detectedMime.isEmpty()) {
                detectedMime = Files.probeContentType(destination);
            }
            response.put("mimeType", detectedMime != null ? detectedMime : "application/octet-stream");
            response.put("uploadedAt", java.time.LocalDateTime.now().toString());

            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("Upload failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Gagal menyimpan file: " + e.getMessage()));
        }
    }

    @GetMapping("/files/{folder}/{filename:.+}")
    public ResponseEntity<Resource> serveFile(
            @PathVariable("folder") String folder,
            @PathVariable("filename") String filename) {

        String safeFolder = folder.replaceAll("[^a-zA-Z0-9_-]", "");
        Path filePath = rootStorageLocation.resolve(safeFolder).resolve(filename).normalize();

        // Prevent path traversal
        if (!filePath.startsWith(rootStorageLocation)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }

            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .body(resource);

        } catch (MalformedURLException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/files/{folder}/{filename:.+}")
    public ResponseEntity<?> deleteFile(
            @PathVariable("folder") String folder,
            @PathVariable("filename") String filename) {

        String safeFolder = folder.replaceAll("[^a-zA-Z0-9_-]", "");
        Path filePath = rootStorageLocation.resolve(safeFolder).resolve(filename).normalize();

        if (!filePath.startsWith(rootStorageLocation)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                return ResponseEntity.ok(Map.of("success", true, "message", "File berhasil dihapus"));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "error", "File tidak ditemukan"));
            }
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
