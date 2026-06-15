package com.dbdiff.controller;

import com.dbdiff.model.Template;
import com.dbdiff.repository.TemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    @Autowired
    private TemplateRepository templateRepository;

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
        return ResponseEntity.ok(t);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Template> updateTemplate(@PathVariable String id, @RequestBody Template t) {
        t.setId(id);
        templateRepository.save(t);
        return ResponseEntity.ok(t);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String id) {
        templateRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
