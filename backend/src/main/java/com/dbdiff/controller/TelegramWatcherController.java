package com.dbdiff.controller;

import com.dbdiff.model.TelegramWatcher;
import com.dbdiff.repository.TelegramWatcherRepository;
import com.dbdiff.service.TelegramWatcherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/telegram/watchers")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class TelegramWatcherController {

    private final TelegramWatcherRepository watcherRepository;
    private final TelegramWatcherService watcherService;

    @Autowired
    public TelegramWatcherController(TelegramWatcherRepository watcherRepository,
                                     TelegramWatcherService watcherService) {
        this.watcherRepository = watcherRepository;
        this.watcherService = watcherService;
    }

    @GetMapping
    public ResponseEntity<List<TelegramWatcher>> getAll() {
        return ResponseEntity.ok(watcherRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<TelegramWatcher> create(@RequestBody TelegramWatcher watcher) {
        TelegramWatcher saved = watcherRepository.save(watcher);
        watcherService.registerWatcher(saved);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody TelegramWatcher watcher) {
        TelegramWatcher existing = watcherRepository.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        TelegramWatcher updated = watcherRepository.update(id, watcher);
        watcherService.registerWatcher(updated);
        return ResponseEntity.ok(updated);
    }

    @PutMapping("/{id}/toggle")
    public ResponseEntity<?> toggle(@PathVariable String id) {
        TelegramWatcher existing = watcherRepository.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        boolean next = !existing.isActive();
        watcherRepository.toggleActive(id, next);
        TelegramWatcher updated = watcherRepository.findById(id);
        watcherService.registerWatcher(updated);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/run-now")
    public ResponseEntity<?> runNow(@PathVariable String id) {
        return ResponseEntity.ok(watcherService.executePollingCycle(id, true));
    }

    @PostMapping("/{id}/reset-watermark")
    public ResponseEntity<?> resetWatermark(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        String val = body != null ? body.get("lastWatermark") : null;
        watcherRepository.resetWatermark(id, val);
        return ResponseEntity.ok(watcherRepository.findById(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        watcherService.unregisterWatcher(id);
        watcherRepository.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
