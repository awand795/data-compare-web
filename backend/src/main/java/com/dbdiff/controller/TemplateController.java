package com.dbdiff.controller;

import com.dbdiff.model.Template;
import com.dbdiff.repository.TemplateRepository;
import com.dbdiff.service.ScheduleManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private static final Logger logger = LoggerFactory.getLogger(TemplateController.class);

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private ScheduleManagerService scheduleManagerService;

    @GetMapping
    public List<Template> getAllTemplates() {
        return templateRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<Template> createTemplate(@RequestBody Template t) {
        if (t.getId() == null || t.getId().isEmpty()) {
            t.setId("tpl_" + UUID.randomUUID().toString());
        }
        templateRepository.save(t);
        if (scheduleManagerService != null) {
            int synced = scheduleManagerService.syncTemplateUpdates(t);
            if (synced > 0) {
                logger.info("Synced template {} with {} scheduled jobs", t.getId(), synced);
            }
        }
        return ResponseEntity.ok(t);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Template> updateTemplate(@PathVariable String id, @RequestBody Template t) {
        t.setId(id);
        templateRepository.save(t);
        if (scheduleManagerService != null) {
            int synced = scheduleManagerService.syncTemplateUpdates(t);
            logger.info("Updated template {} and synced {} scheduled jobs", id, synced);
        }
        return ResponseEntity.ok(t);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String id) {
        templateRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
