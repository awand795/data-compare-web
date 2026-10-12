package com.dbdiff.service;

import com.dbdiff.model.TelegramBotConfig;
import com.dbdiff.model.TelegramContact;
import com.dbdiff.repository.TelegramBotConfigRepository;
import com.dbdiff.repository.TelegramContactRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelegramPollingService {

    private static final Logger logger = LoggerFactory.getLogger(TelegramPollingService.class);

    private final TelegramBotConfigRepository botConfigRepository;
    private final TelegramContactRepository contactRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Track last update_id per botId to prevent processing duplicate updates
    private final Map<String, Long> lastUpdateIds = new ConcurrentHashMap<>();

    // Deduplicate processed messages to prevent any duplicate replies
    private final Set<String> processedMessageKeys = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 2000;
                }
            })
    );

    @Autowired
    public TelegramPollingService(TelegramBotConfigRepository botConfigRepository,
                                  TelegramContactRepository contactRepository) {
        this.botConfigRepository = botConfigRepository;
        this.contactRepository = contactRepository;
        this.objectMapper = new ObjectMapper();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Poll updates from Telegram every 3 seconds for all registered and active bots.
     */
    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public void pollAllBots() {
        List<TelegramBotConfig> bots;
        try {
            bots = botConfigRepository.findAll();
        } catch (Exception e) {
            return;
        }

        for (TelegramBotConfig bot : bots) {
            if (!bot.isPollingActive() || bot.getBotToken() == null || bot.getBotToken().trim().isEmpty()) {
                continue;
            }
            try {
                pollBotUpdates(bot);
            } catch (Exception e) {
                logger.debug("Polling failed for bot {}: {}", bot.getName(), e.getMessage());
            }
        }
    }

    private void pollBotUpdates(TelegramBotConfig bot) {
        long offset = lastUpdateIds.getOrDefault(bot.getId(), 0L) + 1;
        String url = String.format("https://api.telegram.org/bot%s/getUpdates?offset=%d&limit=30&timeout=2",
                bot.getBotToken(), offset);

        String response;
        try {
            response = restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            return;
        }

        if (response == null || response.isEmpty()) return;

        try {
            JsonNode root = objectMapper.readTree(response);
            if (!root.path("ok").asBoolean(false)) return;

            JsonNode updates = root.path("result");
            if (!updates.isArray() || updates.isEmpty()) return;

            for (JsonNode update : updates) {
                long updateId = update.path("update_id").asLong();
                if (updateId >= offset) {
                    lastUpdateIds.put(bot.getId(), updateId);
                }

                JsonNode message = update.path("message");
                if (message.isMissingNode()) continue;

                processIncomingMessage(bot, message);
            }
        } catch (Exception e) {
            logger.warn("Error parsing updates for bot {}: {}", bot.getName(), e.getMessage());
        }
    }

    private void processIncomingMessage(TelegramBotConfig bot, JsonNode message) {
        JsonNode chat = message.path("chat");
        JsonNode from = message.path("from");
        String chatId = chat.path("id").asText();
        if (chatId == null || chatId.isEmpty()) return;

        long messageId = message.path("message_id").asLong();
        String msgKey = bot.getId() + ":" + chatId + ":" + messageId;
        if (messageId > 0 && !processedMessageKeys.add(msgKey)) {
            return;
        }

        String firstName = from.path("first_name").asText("");
        String lastName = from.path("last_name").asText("");
        String username = from.path("username").asText("");
        String text = message.path("text").asText("").trim();

        // 1. Check if user sent Contact object (via button "📱 Bagikan Nomor Telepon Saya")
        JsonNode contactNode = message.path("contact");
        if (!contactNode.isMissingNode()) {
            String phoneNumber = contactNode.path("phone_number").asText("").replaceAll("[^0-9]", "");
            saveContactAndReply(bot, chatId, phoneNumber, firstName, lastName, username, true);
            return;
        }

        // 2. Check if user sent /start command
        if (text.startsWith("/start")) {
            String[] parts = text.split("\\s+", 2);
            String phoneParam = null;
            if (parts.length > 1) {
                String potentialPhone = parts[1].replaceAll("[^0-9]", "");
                if (potentialPhone.length() >= 8) {
                    phoneParam = potentialPhone;
                }
            }

            if (phoneParam != null) {
                if (phoneParam.startsWith("08")) {
                    phoneParam = "62" + phoneParam.substring(1);
                }
                saveContactAndReply(bot, chatId, phoneParam, firstName, lastName, username, false);
            } else {
                // Check if user is already registered with a phone number
                TelegramContact existing = contactRepository.findByBotIdAndChatId(bot.getId(), chatId);
                if (existing != null && existing.getPhoneNumber() != null && !existing.getPhoneNumber().trim().isEmpty()) {
                    String alreadyMsg = String.format(
                            "✅ <b>Anda Sudah Terdaftar!</b>\n\n" +
                            "Halo <b>%s</b>, akun Telegram Anda sudah terdaftar dan terhubung dengan nomor <code>%s</code> di bot <b>%s</b>.\n\n" +
                            "Status: 🟢 <b>Aktif</b>\n" +
                            "Anda akan otomatis menerima pesan notifikasi dan laporan di sini.",
                            escapeHtml(firstName), existing.getPhoneNumber(), escapeHtml(bot.getName())
                    );
                    sendSimpleMessage(bot.getBotToken(), chatId, alreadyMsg);
                    return;
                }

                // Save contact without phone, then send greeting with request_contact button
                TelegramContact c = new TelegramContact();
                c.setBotId(bot.getId());
                c.setChatId(chatId);
                c.setFirstName(firstName);
                c.setLastName(lastName);
                c.setUsername(username);
                c.setActive(true);
                contactRepository.upsertContact(c);

                sendGreetingWithContactButton(bot, chatId, firstName);
            }
            return;
        }

        // 3. Check if user typed their phone number directly as a regular message (e.g. "082362851796" or "+6282362851796")
        String cleanPhone = text.replaceAll("[^0-9]", "");
        if (cleanPhone.length() >= 9 && cleanPhone.length() <= 15 &&
                (text.startsWith("08") || text.startsWith("+62") || text.startsWith("62") || text.startsWith("+"))) {
            if (cleanPhone.startsWith("08")) {
                cleanPhone = "62" + cleanPhone.substring(1);
            }
            saveContactAndReply(bot, chatId, cleanPhone, firstName, lastName, username, false);
            return;
        }
    }

    private void saveContactAndReply(TelegramBotConfig bot, String chatId, String phone,
                                     String firstName, String lastName, String username, boolean isFromContactButton) {
        TelegramContact c = new TelegramContact();
        c.setBotId(bot.getId());
        c.setChatId(chatId);
        c.setPhoneNumber(phone);
        c.setFirstName(firstName);
        c.setLastName(lastName);
        c.setUsername(username);
        c.setActive(true);
        contactRepository.upsertContact(c);

        String replyMsg = String.format(
                "✅ <b>Pendaftaran Berhasil!</b>\n\n" +
                "Halo <b>%s</b>, nomor Anda <code>%s</code> telah berhasil terhubung dengan <b>%s</b> di Darkosync Studio.\n\n" +
                "Anda akan menerima notifikasi dan laporan otomatis di sini.",
                escapeHtml(firstName), phone, escapeHtml(bot.getName())
        );

        sendSimpleMessage(bot.getBotToken(), chatId, replyMsg);
    }

    private void sendGreetingWithContactButton(TelegramBotConfig bot, String chatId, String firstName) {
        try {
            String url = String.format("https://api.telegram.org/bot%s/sendMessage", bot.getBotToken());

            String greetingText = String.format(
                    "👋 Halo <b>%s</b>! Selamat datang di <b>%s</b>.\n\n" +
                    "Agar sistem kami dapat mengirimkan pesan dan notifikasi ke nomor Anda, silakan:\n" +
                    "1️⃣ Klik tombol <b>📱 Bagikan Nomor Telepon Saya</b> di bawah, ATAU\n" +
                    "2️⃣ Ketik langsung nomor HP Anda di chat ini (contoh: <code>081234567890</code>)",
                    escapeHtml(firstName), escapeHtml(bot.getName())
            );

            Map<String, Object> contactBtn = new HashMap<>();
            contactBtn.put("text", "📱 Bagikan Nomor Telepon Saya");
            contactBtn.put("request_contact", true);

            List<List<Map<String, Object>>> keyboard = new ArrayList<>();
            keyboard.add(Collections.singletonList(contactBtn));

            Map<String, Object> replyMarkup = new HashMap<>();
            replyMarkup.put("keyboard", keyboard);
            replyMarkup.put("one_time_keyboard", true);
            replyMarkup.put("resize_keyboard", true);

            Map<String, Object> payload = new HashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", greetingText);
            payload.put("parse_mode", "HTML");
            payload.put("reply_markup", replyMarkup);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(url, new HttpEntity<>(payload, headers), String.class);
        } catch (Exception e) {
            logger.warn("Failed to send greeting keyboard to chat {}: {}", chatId, e.getMessage());
        }
    }

    public void sendSimpleMessage(String botToken, String chatId, String text) {
        try {
            String url = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);
            Map<String, Object> payload = new HashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", text);
            payload.put("parse_mode", "HTML");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(url, new HttpEntity<>(payload, headers), String.class);
        } catch (Exception e) {
            logger.warn("Failed to send simple message to chat {}: {}", chatId, e.getMessage());
        }
    }

    public Map<String, Object> getBotInfo(String botToken) {
        try {
            String url = String.format("https://api.telegram.org/bot%s/getMe", botToken);
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            if (root.path("ok").asBoolean(false)) {
                JsonNode res = root.path("result");
                Map<String, Object> info = new HashMap<>();
                info.put("id", res.path("id").asText());
                info.put("firstName", res.path("first_name").asText());
                info.put("username", res.path("username").asText());
                info.put("canJoinGroups", res.path("can_join_groups").asBoolean());
                return info;
            }
        } catch (Exception e) {
            logger.warn("Failed to get bot info from token: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
