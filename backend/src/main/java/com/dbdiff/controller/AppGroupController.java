package com.dbdiff.controller;

import com.dbdiff.repository.AppGroupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups")
@CrossOrigin(origins = "*")
public class AppGroupController {

    @Autowired
    private AppGroupRepository appGroupRepository;

    @GetMapping
    public ResponseEntity<List<String>> getGroups(@RequestParam(required = false, defaultValue = "ALL") String module) {
        return ResponseEntity.ok(appGroupRepository.getGroups(module));
    }

    @PostMapping
    public ResponseEntity<?> addGroup(@RequestBody Map<String, String> body) {
        String module = body.get("module");
        String name = body.get("name");
        if (module == null || name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Module and Name are required"));
        }
        appGroupRepository.addGroup(module, name.trim());
        return ResponseEntity.ok(Map.of("success", true, "name", name.trim()));
    }

    @PutMapping("/rename")
    public ResponseEntity<?> renameGroup(@RequestBody Map<String, String> body) {
        String module = body.get("module");
        String oldName = body.get("oldName");
        String newName = body.get("newName");
        if (module == null || oldName == null || newName == null || newName.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Module, oldName, and newName are required"));
        }
        appGroupRepository.renameGroup(module, oldName.trim(), newName.trim());
        return ResponseEntity.ok(Map.of("success", true, "oldName", oldName, "newName", newName.trim()));
    }

    @PostMapping("/telegram/assign")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> assignTelegramGroup(@RequestBody Map<String, Object> body) {
        String groupName = body.get("groupName") != null ? body.get("groupName").toString() : "General";
        List<String> scheduleIds = body.get("scheduleIds") instanceof List ? (List<String>) body.get("scheduleIds") : List.of();
        List<String> watcherIds = body.get("watcherIds") instanceof List ? (List<String>) body.get("watcherIds") : List.of();
        appGroupRepository.assignTelegramGroup(groupName, scheduleIds, watcherIds);
        return ResponseEntity.ok(Map.of("success", true, "groupName", groupName));
    }

    @DeleteMapping
    public ResponseEntity<?> deleteGroup(@RequestParam String module, @RequestParam String name) {
        if (module == null || name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Module and Name are required"));
        }
        appGroupRepository.deleteGroup(module, name.trim());
        return ResponseEntity.ok(Map.of("success", true, "message", "Group deleted from database"));
    }
}