package com.kean.service.impl;

import com.kean.common.ErrorCode;
import com.kean.config.MailProperties;
import com.kean.exception.BizException;
import com.kean.service.MailService;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class MailServiceImpl implements MailService {

    private final MailProperties mailProperties;
    private final ObjectProvider<JavaMailSender> mailSender;

    public MailServiceImpl(MailProperties mailProperties, ObjectProvider<JavaMailSender> mailSender) {
        this.mailProperties = mailProperties;
        this.mailSender = mailSender;
    }

    @Override
    public boolean ready() {
        return mailProperties.ready() && mailSender.getIfAvailable() != null;
    }

    @Override
    public void sendVerifyCode(String to, String scene, String code) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (!mailProperties.ready() || sender == null) {
            throw new BizException(ErrorCode.MAIL_NOT_CONFIGURED);
        }
        String sceneLabel = sceneLabel(scene);//邮件类型
        String subject = "【课安】 验证码";
        String text = """
                您好，

                您的验证码：%s
                5 分钟内有效，请勿泄露给他人喵。

                如非本人操作，请忽略本邮件喵。
                —— 课安
                """.formatted(code);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            String from = mailProperties.getFrom().trim();
            String fromName = StringUtils.hasText(mailProperties.getFromName()) ? mailProperties.getFromName().trim() : "课安";
            helper.setFrom(new InternetAddress(from, fromName, "UTF-8"));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);
            sender.send(message);
            log.info("验证码邮件已发送 scene={} to={}", scene, to);
        } catch (Exception ex) {
            log.error("验证码邮件发送失败 scene={} to={}", scene, to, ex);
            throw new BizException(ErrorCode.MAIL_SEND_FAILED);
        }
    }

    private String sceneLabel(String scene) {
        return switch (scene == null ? "" : scene.trim().toUpperCase()) {
            case "REGISTER" -> "注册账号";
            case "FORGOT_PASSWORD" -> "重置密码";
            case "CHANGE_PASSWORD" -> "修改密码";
            case "CHANGE_EMAIL" -> "更换邮箱";
            default -> "身份验证";
        };
    }
}
