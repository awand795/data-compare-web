package com.dbdiff.service;

import com.dbdiff.model.NotificationChannel;
import com.dbdiff.repository.NotificationChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final RestTemplate restTemplate;

    public NotificationService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000); // 10 seconds
        factory.setReadTimeout(10000);    // 10 seconds
        this.restTemplate = new RestTemplate(factory);
    }
    
    @Autowired
    private NotificationChannelRepository channelRepository;

    public void sendToChannel(String channelId, String message) {
        NotificationChannel channel = channelRepository.findById(channelId);
        if (channel == null) {
            logger.warn("Notification channel not found: {}. Message not sent.", channelId);
            return;
        }

        if ("TELEGRAM".equals(channel.getType())) {
            sendTelegramMessage(channel.getBotToken(), channel.getChatId(), message);
        } else if ("DISCORD".equals(channel.getType())) {
            sendDiscordMessage(channel.getWebhookUrl(), message);
        }
    }

    public void sendTelegramMessage(String botToken, String chatId, String message) {
        if (botToken == null || botToken.isEmpty() || chatId == null || chatId.isEmpty()) {
            return;
        }
        try {
            String url = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);
            Map<String, Object> payload = new HashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", message);
            payload.put("parse_mode", "HTML");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            restTemplate.postForEntity(url, request, String.class);
        } catch (Exception e) {
            logger.error("Failed to send Telegram message: {}", e.getMessage(), e);
        }
    }

    public void sendDiscordMessage(String webhookUrl, String message) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return;
        }
        try {
            String formattedMessage = convertHtmlToDiscordMarkdown(message);

            Map<String, Object> payload = new HashMap<>();
            payload.put("content", formattedMessage);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            restTemplate.postForEntity(webhookUrl, request, String.class);
        } catch (Exception e) {
            logger.error("Failed to send Discord message: {}", e.getMessage(), e);
        }
    }

    /**
     * Konversi format HTML (yang biasa dipakai Telegram) menjadi Markdown yang kompatibel dengan Discord.
     */
    public static String convertHtmlToDiscordMarkdown(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        // Jika tidak mengandung tag HTML '<', langsung return (dengan safety limit Discord)
        if (!html.contains("<")) {
            if (html.length() > 1950) {
                return html.substring(0, 1920) + "\n... (truncated)";
            }
            return html;
        }

        String msg = html;

        // Code blocks: <pre><code>...</code></pre> atau <pre>...</pre>
        msg = msg.replaceAll("(?is)<pre><code>(.*?)</code></pre>", "\n```\n$1\n```\n");
        msg = msg.replaceAll("(?is)<pre>(.*?)</pre>", "\n```\n$1\n```\n");

        // Inline code: <code>...</code>
        msg = msg.replaceAll("(?is)<code>(.*?)</code>", "`$1`");

        // Bold: <b>...</b>, <strong>...</strong>
        msg = msg.replaceAll("(?is)<b>(.*?)</b>", "**$1**");
        msg = msg.replaceAll("(?is)<strong>(.*?)</strong>", "**$1**");

        // Italic: <i>...</i>, <em>...</em>
        msg = msg.replaceAll("(?is)<i>(.*?)</i>", "*$1*");
        msg = msg.replaceAll("(?is)<em>(.*?)</em>", "*$1*");

        // Underline: <u>...</u>
        msg = msg.replaceAll("(?is)<u>(.*?)</u>", "__$1__");

        // Strikethrough: <s>, <strike>, <del>
        msg = msg.replaceAll("(?is)<s>(.*?)</s>", "~~$1~~");
        msg = msg.replaceAll("(?is)<strike>(.*?)</strike>", "~~$1~~");
        msg = msg.replaceAll("(?is)<del>(.*?)</del>", "~~$1~~");

        // Links: <a href="url">text</a> -> [text](url)
        msg = msg.replaceAll("(?is)<a\\s+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", "[$2]($1)");

        // Line breaks & Paragraphs: <br>, <p>
        msg = msg.replaceAll("(?is)<br\\s*/?>", "\n");
        msg = msg.replaceAll("(?is)</p>", "\n");
        msg = msg.replaceAll("(?is)<p>", "");

        // Strip any remaining unhandled HTML tags
        msg = msg.replaceAll("<[^>]+>", "");

        // Unescape standard HTML entities
        msg = msg.replace("&amp;", "&")
                 .replace("&lt;", "<")
                 .replace("&gt;", ">")
                 .replace("&quot;", "\"")
                 .replace("&#39;", "'")
                 .replace("&apos;", "'");

        // Normalisasi multiple consecutive newlines (3 atau lebih dijadikan 2)
        msg = msg.replaceAll("\n{3,}", "\n\n").trim();

        // Perlindungan limit panjang karakter Discord (maksimal 2000 karakter)
        if (msg.length() > 1950) {
            msg = msg.substring(0, 1920) + "\n... (truncated)";
            // Pastikan jika terpotong di dalam code block, kita tutup dengan triple backticks
            int count = 0;
            int idx = 0;
            while ((idx = msg.indexOf("```", idx)) != -1) {
                count++;
                idx += 3;
            }
            if (count % 2 != 0) {
                msg += "\n```";
            }
        }

        return msg;
    }
}
