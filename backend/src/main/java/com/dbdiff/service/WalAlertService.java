package com.dbdiff.service;

import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.model.WalAlertSchedule;
import com.dbdiff.repository.ConnectionRepository;
import com.dbdiff.repository.NotificationChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class WalAlertService {

    private static final Logger logger = LoggerFactory.getLogger(WalAlertService.class);

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionManagerService connectionManagerService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationChannelRepository channelRepository;

    public static class WalSlotInfo {
        public String connectionName;
        public String slotName;
        public String database;
        public String plugin;
        public boolean active;
        public Long activePid;
        public long walBytes;
        public String walPretty;
    }

    public List<WalSlotInfo> scanSlots(String connectionId) {
        List<WalSlotInfo> result = new ArrayList<>();
        List<ConnectionDetails> connections = new ArrayList<>();

        if (connectionId != null && !connectionId.trim().isEmpty()) {
            ConnectionDetails conn = connectionRepository.findById(connectionId);
            if (conn != null) connections.add(conn);
        } else {
            List<ConnectionDetails> allConns = connectionRepository.findAll();
            for (ConnectionDetails c : allConns) {
                if ("postgresql".equalsIgnoreCase(c.getType())) {
                    connections.add(c);
                }
            }
        }

        for (ConnectionDetails connDetails : connections) {
            if (!"postgresql".equalsIgnoreCase(connDetails.getType())) continue;
            try {
                DataSource ds = connectionManagerService.getDataSource(connDetails);
                try (Connection conn = ds.getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                         "SELECT slot_name, plugin, slot_type, database, temporary, active, active_pid, " +
                         "COALESCE(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn), 0) AS wal_bytes, " +
                         "COALESCE(pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)), '0 bytes') AS wal_retained " +
                         "FROM pg_replication_slots")) {
                    while (rs.next()) {
                        WalSlotInfo info = new WalSlotInfo();
                        info.connectionName = connDetails.getName();
                        info.slotName = rs.getString("slot_name");
                        info.plugin = rs.getString("plugin");
                        info.database = rs.getString("database");
                        info.active = rs.getBoolean("active");
                        long pid = rs.getLong("active_pid");
                        info.activePid = rs.wasNull() ? null : pid;
                        info.walBytes = rs.getLong("wal_bytes");
                        info.walPretty = rs.getString("wal_retained");
                        result.add(info);
                    }
                }
            } catch (Exception ex) {
                logger.error("Error checking PostgreSQL replication slots for {}: {}", connDetails.getName(), ex.getMessage());
            }
        }
        return result;
    }

    public boolean evaluateAndSendAlert(WalAlertSchedule schedule, boolean forceSend) {
        List<WalSlotInfo> slots = scanSlots(schedule.getConnectionId());
        long thresholdBytes = ((long) schedule.getThresholdMb()) * 1024L * 1024L;

        List<WalSlotInfo> criticalSlots = new ArrayList<>();
        for (WalSlotInfo s : slots) {
            if (s.walBytes >= thresholdBytes || (!s.active && s.walBytes > 10 * 1024 * 1024L)) {
                criticalSlots.add(s);
            }
        }

        boolean isCritical = !criticalSlots.isEmpty();
        if (forceSend || isCritical) {
            sendWalAlertNotification(schedule, slots, criticalSlots, isCritical, forceSend);
            return true;
        }

        return false;
    }

    private void sendWalAlertNotification(WalAlertSchedule schedule, List<WalSlotInfo> allSlots,
                                          List<WalSlotInfo> criticalSlots, boolean isCritical, boolean isTest) {
        String channelIds = schedule.getChannelIds();
        if (channelIds == null || channelIds.trim().isEmpty()) {
            logger.warn("WAL Alert Schedule {} has no target notification channels configured.", schedule.getName());
            return;
        }

        String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // HTML for Telegram
        StringBuilder html = new StringBuilder();
        if (isTest) {
            html.append("🧪 <b>[TEST NOTIFICATION] POSTGRESQL WAL SLOTS ALERT</b>\n\n");
        } else if (isCritical) {
            html.append("🚨 <b>[ALERT] POSTGRESQL REPLICATION WAL BLOAT WARNING!</b>\n\n");
        } else {
            html.append("ℹ️ <b>[STATUS] WAL REPLICATION SLOTS OK</b>\n\n");
        }

        html.append("📌 <b>Rule:</b> ").append(schedule.getName()).append("\n");
        html.append("🎯 <b>Threshold:</b> <code>").append(schedule.getThresholdMb()).append(" MB</code>\n");
        html.append("⏱️ <b>Waktu:</b> ").append(timeStr).append("\n\n");

        if (!criticalSlots.isEmpty()) {
            html.append("<b>⚠️ Replication Slot Kritis Terdeteksi:</b>\n");
            for (WalSlotInfo s : criticalSlots) {
                html.append(String.format(" • <b>[%s]</b> <code>%s</code> (DB: %s)\n   └ WAL Retained: <b>%s</b> | Status: <b>%s</b>\n",
                        s.connectionName, s.slotName, s.database != null ? s.database : "-", s.walPretty,
                        s.active ? "ACTIVE" : "INACTIVE (BERBAHAYA BAGI DISK)"));
            }
            html.append("\n<i>Perhatian: Slot yang inaktif/menahan WAL besar dapat menyebabkan disk server database PostgreSQL penuh!</i>\n\n");
        } else {
            html.append("✅ Semua replication slot dalam batas wajar (< ").append(schedule.getThresholdMb()).append(" MB).\n\n");
        }

        html.append("<b>Total Slot Aktif/Inaktif:</b> ").append(allSlots.size()).append(" slot terdeteksi.");

        // Markdown for Discord
        StringBuilder disc = new StringBuilder();
        if (isTest) {
            disc.append("🧪 **[TEST] POSTGRESQL WAL SLOTS ALERT**\n");
        } else if (isCritical) {
            disc.append("🚨 **[ALERT] POSTGRESQL REPLICATION WAL BLOAT WARNING!**\n");
        } else {
            disc.append("ℹ️ **[STATUS] WAL REPLICATION SLOTS OK**\n");
        }
        disc.append("📌 **Rule:** ").append(schedule.getName()).append(" | **Threshold:** `").append(schedule.getThresholdMb()).append(" MB`\n");
        disc.append("⏱️ **Time:** ").append(timeStr).append("\n\n");

        if (!criticalSlots.isEmpty()) {
            disc.append("**Replication Slot Kritis Terdeteksi:**\n");
            for (WalSlotInfo s : criticalSlots) {
                disc.append(String.format("• **[%s]** `%s` (DB: %s) -> WAL: **%s** | Status: **%s**\n",
                        s.connectionName, s.slotName, s.database != null ? s.database : "-", s.walPretty,
                        s.active ? "ACTIVE" : "INACTIVE (DANGER)"));
            }
            disc.append("\n");
        } else {
            disc.append("✅ Semua replication slot dalam batas normal (< ").append(schedule.getThresholdMb()).append(" MB)\n");
        }

        disc.append(String.format("Total slot: %d", allSlots.size()));

        String[] ids = channelIds.split(",");
        for (String chId : ids) {
            chId = chId.trim();
            if (chId.isEmpty()) continue;
            try {
                var ch = channelRepository.findById(chId);
                if (ch != null) {
                    if ("TELEGRAM".equalsIgnoreCase(ch.getType())) {
                        notificationService.sendTelegramMessage(ch.getBotToken(), ch.getChatId(), html.toString());
                    } else if ("DISCORD".equalsIgnoreCase(ch.getType())) {
                        notificationService.sendDiscordMessage(ch.getWebhookUrl(), disc.toString());
                    }
                }
            } catch (Exception ex) {
                logger.error("Failed to send WAL alert to channel {}", chId, ex);
            }
        }
    }
}
