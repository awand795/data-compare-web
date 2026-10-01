package com.dbdiff.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    @Value("${spring.mail.host:smtp.gmail.com}")
    private String mailHost;

    @Value("${spring.mail.port:465}")
    private int mailPort;

    @Value("${spring.mail.username:awand795@gmail.com}")
    private String mailUsername;

    @Value("${spring.mail.password:eaexojmykidhwxbk}")
    private String mailPassword;

    @Value("${app.mail.from-address:awand795@gmail.com}")
    private String defaultMailFromAddress;

    @Value("${app.mail.from-name:Layanan Notifikasi}")
    private String defaultMailFromName;

    @Value("${app.backend.public-url:http://94.237.69.119:8081}")
    private String defaultPublicBackendUrl;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String defaultFrontendUrl;

    private JavaMailSender mailSender;

    public String getFrontendUrl() {
        return (defaultFrontendUrl != null && !defaultFrontendUrl.isBlank()) ? defaultFrontendUrl : "http://localhost:3000";
    }

    public String getPublicBackendUrl() {
        return (defaultPublicBackendUrl != null && !defaultPublicBackendUrl.isBlank()) ? defaultPublicBackendUrl : "http://94.237.69.119:8081";
    }

    private synchronized JavaMailSender getMailSender() {
        if (mailSender == null) {
            JavaMailSenderImpl impl = new JavaMailSenderImpl();
            impl.setHost(mailHost != null && !mailHost.isBlank() ? mailHost.trim() : "smtp.gmail.com");
            impl.setPort(mailPort > 0 ? mailPort : 465);
            impl.setUsername(mailUsername != null ? mailUsername.trim() : "");
            impl.setPassword(mailPassword != null ? mailPassword.trim() : "");
            impl.setDefaultEncoding("UTF-8");

            Properties props = impl.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", "true");

            if (mailPort == 465) {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.port", "465");
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                props.put("mail.smtp.socketFactory.fallback", "false");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }

            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");
            props.put("mail.smtp.writetimeout", "10000");

            mailSender = impl;
        }
        return mailSender;
    }

    public CompletableFuture<Boolean> sendVerificationEmailAsync(String toEmail, String recipientName, String token) {
        String verifyUrl = getPublicBackendUrl().replaceAll("/+$", "") + "/api/auth/verify-email?token=" + token;
        return sendDynamicVerificationEmailAsync(toEmail, recipientName, token, null, null, null, verifyUrl, "Layanan Notifikasi");
    }

    public CompletableFuture<Boolean> sendDynamicVerificationEmailAsync(
            String toEmail,
            String recipientName,
            String token,
            String customFrom,
            String customSubject,
            String customTemplate,
            String verifyUrl,
            String appName) {
        return CompletableFuture.supplyAsync(() ->
                sendDynamicVerificationEmail(toEmail, recipientName, token, customFrom, customSubject, customTemplate, verifyUrl, appName));
    }

    public boolean sendDynamicVerificationEmail(
            String toEmail,
            String recipientName,
            String token,
            String customFrom,
            String customSubject,
            String customTemplate,
            String verifyUrl,
            String appName) {

        if (toEmail == null || toEmail.trim().isEmpty() || token == null || token.trim().isEmpty()) {
            logger.warn("Skipping email verification: missing toEmail or token");
            return false;
        }

        try {
            JavaMailSender sender = getMailSender();
            MimeMessage mimeMessage = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());

            // 1. Resolve From Address & Display Name
            String fromAddr = mailUsername;
            String fromDisplay = defaultMailFromName;

            if (customFrom != null && !customFrom.isBlank()) {
                String cf = customFrom.trim();
                if (cf.contains("<") && cf.contains(">")) {
                    try {
                        InternetAddress parsed = new InternetAddress(cf);
                        fromAddr = parsed.getAddress();
                        if (parsed.getPersonal() != null && !parsed.getPersonal().isBlank()) {
                            fromDisplay = parsed.getPersonal();
                        }
                    } catch (Exception e) {
                        fromAddr = cf.replaceAll(".*<([^>]+)>.*", "$1").trim();
                        fromDisplay = cf.replaceAll("<[^>]+>", "").trim();
                    }
                } else if (cf.contains("@")) {
                    fromAddr = cf;
                } else {
                    fromDisplay = cf;
                }
            } else if (defaultMailFromAddress != null && !defaultMailFromAddress.isBlank()) {
                fromAddr = defaultMailFromAddress.trim();
            }

            helper.setFrom(fromAddr, fromDisplay);
            helper.setTo(toEmail.trim());

            // 2. Resolve Subject (Supports placeholders: {{nama}}, {{app_name}}, {{email}})
            String cleanName = (recipientName != null && !recipientName.isBlank()) ? recipientName.trim() : "Pengguna";
            String cleanApp = (appName != null && !appName.isBlank()) ? appName.trim() : fromDisplay;
            String subject = (customSubject != null && !customSubject.isBlank()) ? customSubject : "Verifikasi Alamat Email Anda - {{app_name}}";
            subject = subject
                    .replace("{{app_name}}", cleanApp)
                    .replace("{{nama}}", cleanName)
                    .replace("{{nama_lengkap}}", cleanName)
                    .replace("{{email}}", toEmail.trim());
            helper.setSubject(subject);

            // 3. Resolve HTML Body Template (Supports placeholders)
            String htmlBody;
            if (customTemplate != null && !customTemplate.isBlank()) {
                htmlBody = customTemplate
                        .replace("{{verification_link}}", verifyUrl)
                        .replace("{{link}}", verifyUrl)
                        .replace("{{nama}}", cleanName)
                        .replace("{{nama_lengkap}}", cleanName)
                        .replace("{{email}}", toEmail.trim())
                        .replace("{{app_name}}", cleanApp)
                        .replace("{{token}}", token);
                
                // Jika custom template berupa teks biasa tanpa HTML, buat wrapper HTML sederhana
                if (!htmlBody.toLowerCase().contains("<html") && !htmlBody.toLowerCase().contains("<body")) {
                    htmlBody = "<div style=\"font-family: sans-serif; line-height: 1.6; color: #334155; padding: 20px;\">" 
                            + htmlBody.replace("\n", "<br>") 
                            + "<br><br><a href=\"" + verifyUrl + "\" style=\"display: inline-block; background: #0f766e; color: #fff; text-decoration: none; padding: 12px 24px; border-radius: 6px; font-weight: bold;\">Verifikasi Email</a></div>";
                }
            } else {
                htmlBody = buildDefaultVerificationEmailHtml(cleanName, cleanApp, verifyUrl);
            }

            helper.setText(htmlBody, true);
            sender.send(mimeMessage);
            logger.info("Verification email successfully sent to {} with subject '{}'", toEmail, subject);
            return true;
        } catch (Exception ex) {
            logger.error("Failed to send verification email to {}: {}", toEmail, ex.getMessage(), ex);
            return false;
        }
    }

    private String buildDefaultVerificationEmailHtml(String recipientName, String appName, String verifyUrl) {
        return "<!DOCTYPE html>\n" +
            "<html lang=\"id\">\n" +
            "<head>\n" +
            "  <meta charset=\"UTF-8\">\n" +
            "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "  <title>Verifikasi Email - " + appName + "</title>\n" +
            "  <style>\n" +
            "    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; margin: 0; padding: 24px; color: #1e293b; }\n" +
            "    .card { max-width: 580px; margin: 0 auto; background: #ffffff; border-radius: 12px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 4px 16px rgba(0,0,0,0.06); }\n" +
            "    .header { background: linear-gradient(135deg, #042f2e 0%, #0f766e 100%); padding: 32px 28px; text-align: center; color: #ffffff; }\n" +
            "    .header h1 { margin: 0; font-size: 20px; font-weight: 700; letter-spacing: 0.5px; }\n" +
            "    .header p { margin: 6px 0 0 0; font-size: 13px; color: #99f6e4; letter-spacing: 0.3px; }\n" +
            "    .content { padding: 32px 28px; }\n" +
            "    .greeting { font-size: 16px; font-weight: 600; margin-bottom: 12px; color: #0f172a; }\n" +
            "    .desc { font-size: 14px; line-height: 1.6; color: #475569; margin-bottom: 24px; }\n" +
            "    .btn-container { text-align: center; margin: 32px 0; }\n" +
            "    .btn { display: inline-block; background-color: #0f766e; color: #ffffff !important; text-decoration: none; padding: 14px 32px; font-size: 15px; font-weight: 600; border-radius: 8px; box-shadow: 0 2px 8px rgba(15, 118, 110, 0.35); }\n" +
            "    .btn:hover { background-color: #115e59; }\n" +
            "    .alt-link { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 12px; font-size: 12px; color: #64748b; word-break: break-all; margin-top: 24px; }\n" +
            "    .footer { border-top: 1px solid #e2e8f0; background: #f8fafc; padding: 20px 28px; font-size: 12px; color: #94a3b8; text-align: center; line-height: 1.5; }\n" +
            "  </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "  <div class=\"card\">\n" +
            "    <div class=\"header\">\n" +
            "      <h1>" + appName + "</h1>\n" +
            "      <p>Sistem Layanan Autentikasi &amp; Keamanan Akun</p>\n" +
            "    </div>\n" +
            "    <div class=\"content\">\n" +
            "      <div class=\"greeting\">Halo, " + recipientName + "!</div>\n" +
            "      <p class=\"desc\">\n" +
            "        Terima kasih telah melakukan pendaftaran akun di <strong>" + appName + "</strong>.<br><br>\n" +
            "        Untuk mengaktifkan akun Anda dan melanjutkan proses verifikasi keamanan, silakan klik tombol verifikasi di bawah ini:\n" +
            "      </p>\n" +
            "      <div class=\"btn-container\">\n" +
            "        <a href=\"" + verifyUrl + "\" class=\"btn\" target=\"_blank\">Verifikasi Email Sekarang</a>\n" +
            "      </div>\n" +
            "      <p class=\"desc\" style=\"font-size: 13px; color: #64748b;\">\n" +
            "        Setelah tombol ditekan, akun Anda akan langsung terverifikasi dan Anda dapat kembali ke halaman login.\n" +
            "      </p>\n" +
            "      <div class=\"alt-link\">\n" +
            "        Jika tombol di atas tidak dapat diklik, salin dan buka tautan berikut di browser Anda:<br>\n" +
            "        <a href=\"" + verifyUrl + "\" style=\"color: #0f766e;\">" + verifyUrl + "</a>\n" +
            "      </div>\n" +
            "    </div>\n" +
            "    <div class=\"footer\">\n" +
            "      &copy; " + java.time.Year.now().getValue() + " " + appName + ". Seluruh hak cipta dilindungi.<br>\n" +
            "      Email ini dibuat secara otomatis oleh sistem keamanan. Mohon untuk tidak membalas pesan ini.\n" +
            "    </div>\n" +
            "  </div>\n" +
            "</body>\n" +
            "</html>";
    }
}
