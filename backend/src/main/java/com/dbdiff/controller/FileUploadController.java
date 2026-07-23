package com.dbdiff.controller;

import com.dbdiff.service.FileUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/file-upload")
public class FileUploadController {

    private final FileUploadService fileUploadService;

    @Autowired
    public FileUploadController(FileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "tableName", required = false) String tableName,
            @RequestParam(value = "description", required = false) String description) {
        try {
            Map<String, Object> result = fileUploadService.uploadFileToSchExcel(file, tableName, description);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Gagal mengunggah file"));
        }
    }

    @GetMapping("/tables")
    public ResponseEntity<?> getUploadedTables() {
        try {
            return ResponseEntity.ok(fileUploadService.getUploadedTables());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Gagal mengambil daftar tabel"));
        }
    }

    @GetMapping("/tables/{tableName}/preview")
    public ResponseEntity<?> getTablePreview(
            @PathVariable String tableName,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        try {
            Map<String, Object> preview = fileUploadService.getTablePreview(tableName, page, pageSize);
            return ResponseEntity.ok(preview);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Gagal membaca isi tabel"));
        }
    }

    @DeleteMapping("/tables/{tableName}")
    public ResponseEntity<?> deleteUploadedTable(@PathVariable String tableName) {
        try {
            fileUploadService.deleteUploadedTable(tableName);
            return ResponseEntity.ok(Map.of("success", true, "message", "Tabel " + tableName + " berhasil dihapus dari schema sch_excel"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Gagal menghapus tabel"));
        }
    }

    @PutMapping("/tables/{tableName}")
    public ResponseEntity<?> updateUploadedTable(
            @PathVariable String tableName,
            @RequestBody Map<String, String> body) {
        try {
            String newTableName = body.get("newTableName");
            if (newTableName == null) newTableName = body.get("tableName");
            String description = body.get("description");
            Map<String, Object> result = fileUploadService.updateUploadedTable(tableName, newTableName, description);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Gagal memperbarui tabel"));
        }
    }
}
