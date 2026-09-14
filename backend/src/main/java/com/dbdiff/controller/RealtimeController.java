package com.dbdiff.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/realtime")
@CrossOrigin(origins = "*")
public class RealtimeController {

    private static final Logger log = LoggerFactory.getLogger(RealtimeController.class);

    private static final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @RequestParam(value = "topic", defaultValue = "general") String topic) {

        SseEmitter emitter = new SseEmitter(1800000L);
        emitters.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(topic, emitter));
        emitter.onTimeout(() -> removeEmitter(topic, emitter));
        emitter.onError((e) -> removeEmitter(topic, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data(Map.of(
                            "status", "connected",
                            "topic", topic,
                            "timestamp", System.currentTimeMillis()
                    )));
        } catch (IOException e) {
            removeEmitter(topic, emitter);
        }

        return emitter;
    }

    @PostMapping("/broadcast")
    public Map<String, Object> broadcast(
            @RequestParam(value = "topic", defaultValue = "general") String topic,
            @RequestBody Map<String, Object> payload) {

        int delivered = broadcastToTopic(topic, "DATA_CHANGE", payload);
        return Map.of("success", true, "topic", topic, "deliveredCount", delivered);
    }

    public static int broadcastToTopic(String topic, String eventName, Object data) {
        List<SseEmitter> topicEmitters = emitters.get(topic);
        if (topicEmitters == null || topicEmitters.isEmpty()) {
            return 0;
        }

        List<SseEmitter> deadEmitters = new ArrayList<>();
        int count = 0;

        for (SseEmitter emitter : topicEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
                count++;
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        topicEmitters.removeAll(deadEmitters);
        return count;
    }

    private static void removeEmitter(String topic, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(topic);
        if (list != null) {
            list.remove(emitter);
        }
    }
}
