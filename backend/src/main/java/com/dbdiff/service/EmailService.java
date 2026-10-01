package com.dbdiff.service;

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
    private String mailFromAddress;

    @Value("${app.mail.from-name:PT Lotus Pradipta Mulia}")
    private String mailFromName;

    @Value("${app.backend.public-url:http://94.237.69.119:8081}")
    private String publicBackendUrl;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    private JavaMailSender mailSender;

    public String getFrontendUrl() {
        return (frontendUrl != null && !frontendUrl.isBlank()) ? frontendUrl : "http://localhost:3000";
    }

    public String getPublicBackendUrl() {
        return (publicBackendUrl != null && !publicBackendUrl.isBlank()) ? publicBackendUrl : "http://94.237.69.119:8081";
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
        return CompletableFuture.supplyAsync(() -> sendVerificationEmail(toEmail, recipientName, token));
    }

    public boolean sendVerificationEmail(String toEmail, String recipientName, String token) {
        if (toEmail == null || toEmail.trim().isEmpty() || token == null || token.trim().isEmpty()) {
            logger.warn("Skipping email verification: missing toEmail or token");
            return false;
        }

        try {
            JavaMailSender sender = getMailSender();
            MimeMessage mimeMessage = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());

            String fromAddr = (mailFromAddress != null && !mailFromAddress.isBlank()) ? mailFromAddress.trim() : mailUsername;
            String fromDisplay = (mailFromName != null && !mailFromName.isBlank()) ? mailFromName.trim() : "PT Lotus Pradipta Mulia";
            helper.setFrom(fromAddr, fromDisplay);
            helper.setTo(toEmail.trim());
            helper.setSubject("Verifikasi Alamat Email Anda - Web Fleet PT Lotus Pradipta Mulia");

            String backendBase = getPublicBackendUrl().replaceAll("/+$", "");
            String verifyUrl = backendBase + "/api/auth/verify-email?token=" + token;

            String htmlBody = buildVerificationEmailHtml(recipientName, verifyUrl);
            helper.setText(htmlBody, true);

            sender.send(mimeMessage);
            logger.info("Verification email successfully sent to {}", toEmail);
            return true;
        } catch (Exception ex) {
            logger.error("Failed to send verification email to {}: {}", toEmail, ex.getMessage(), ex);
            return false;
        }
    }

    private String buildVerificationEmailHtml(String recipientName, String verifyUrl) {
        String cleanName = (recipientName != null && !recipientName.isBlank()) ? recipientName.trim() : "Mitra";
        return "<!DOCTYPE html>\n" +
            "<html lang=\"id\">\n" +
            "<head>\n" +
            "  <meta charset=\"UTF-8\">\n" +
            "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "  <title>Verifikasi Email - PT Lotus Pradipta Mulia</title>\n" +
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
            "      <h1>PT LOTUS PRADIPTA MULIA</h1>\n" +
            "      <p>Sistem Layanan &amp; Pemantauan Web Fleet Kendaraan Operasional</p>\n" +
            "    </div>\n" +
            "    <div class=\"content\">\n" +
            "      <div class=\"greeting\">Halo, " + cleanName + "!</div>\n" +
            "      <p class=\"desc\">\n" +
            "        Terima kasih telah melakukan pendaftaran akun kemitraan di <strong>Web Fleet PT Lotus Pradipta Mulia</strong>.<br><br>\n" +
            "        Untuk memastikan keamanan akun serta mengaktifkan akses penuh pemantauan jadwal servis dan riwayat kendaraan Anda, silakan klik tombol verifikasi di bawah ini:\n" +
            "      </p>\n" +
            "      <div class=\"btn-container\">\n" +
            "        <a href=\"" + verifyUrl + "\" class=\"btn\" target=\"_blank\">Verifikasi Email Sekarang</a>\n" +
            "      </div>\n" +
            "      <p class=\"desc\" style=\"font-size: 13px; color: #64748b;\">\n" +
            "        Setelah tautan ditekan, akun Anda akan langsung terverifikasi dan Anda dapat langsung masuk ke sistem Web Fleet.\n" +
            "      </p>\n" +
            "      <div class=\"alt-link\">\n" +
            "        Jika tombol di atas tidak dapat diklik, salin dan buka tautan berikut di browser Anda:<br>\n" +
            "        <a href=\"" + verifyUrl + "\" style=\"color: #0f766e;\">" + verifyUrl + "</a>\n" +
            "      </div>\n" +
            "    </div>\n" +
            "    <div class=\"footer\">\n" +
            "      PT Lotus Pradipta Mulia &bull; Distributor Otomotif, Suku Cadang &amp; Pelumas Resmi<br>\n" +
            "      Kawasan Industri Medan (KIM 3), Medan, Sumatera Utara<br>\n" +
            "      Email ini dibuat otomatis oleh sistem keamanan. Mohon untuk tidak membalas pesan ini.\n" +
            "    </div>\n" +
            "  </div>\n" +
            "</body>\n" +
            "</html>";
    }
}
