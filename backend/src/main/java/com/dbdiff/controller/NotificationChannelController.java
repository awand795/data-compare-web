package com.dbdiff.controller;

import com.dbdiff.model.NotificationChannel;
import com.dbdiff.repository.NotificationChannelRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notification-channels")
@CrossOrigin(origins = "*")
public class NotificationChannelController {

    private final NotificationChannelRepository repository;

    @Autowired
    public NotificationChannelController(NotificationChannelRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<NotificationChannel> getAll() {
        return repository.findAll();
    }

    @PostMapping
    public NotificationChannel create(@RequestBody NotificationChannel channel) {
        return repository.save(channel);
    }

    @PutMapping("/{id}")
    public ResponseEntity<NotificationChannel> update(@PathVariable String id, @RequestBody NotificationChannel channel) {
        repository.update(id, channel);
        return ResponseEntity.ok(repository.findById(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        repository.delete(id);
        return ResponseEntity.ok().build();
    }
}
