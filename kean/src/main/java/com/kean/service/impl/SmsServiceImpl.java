package com.kean.service.impl;

import com.kean.common.ErrorCode;
import com.kean.config.SmsProperties;
import com.kean.exception.BizException;
import com.kean.service.MailService;
import com.kean.service.SmsService;
import com.kean.utils.QqEmails;
import com.kean.vo.SmsSendVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Slf4j
@Service
public class SmsServiceImpl implements SmsService {

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final int DAILY_LIMIT = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final SmsProperties smsProperties;
    private final MailService mailService;

    public SmsServiceImpl(StringRedisTemplate redisTemplate, SmsProperties smsProperties, MailService mailService) {
        this.redisTemplate = redisTemplate;
        this.smsProperties = smsProperties;
        this.mailService = mailService;
    }

    @Override
    public SmsSendVO send(String target, String scene) {
        String email = QqEmails.normalize(target);
        String normalizedScene = scene.trim().toUpperCase();
        String cooldownKey = "kean:sms:cd:" + normalizedScene + ":" + email;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            throw new BizException(ErrorCode.SMS_TOO_FREQUENT);
        }
        String dayKey = "kean:sms:day:" + LocalDate.now() + ":" + email;
        String used = redisTemplate.opsForValue().get(dayKey);
        int count = used == null ? 0 : Integer.parseInt(used);
        if (count >= DAILY_LIMIT) {
            throw new BizException(ErrorCode.SMS_TOO_FREQUENT, "今日验证码次数已用完");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redisTemplate.opsForValue().set("kean:sms:code:" + normalizedScene + ":" + email, code, CODE_TTL);
        redisTemplate.opsForValue().set(cooldownKey, "1", COOLDOWN);
        Duration untilMidnight = Duration.between(LocalDateTime.now(), LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.MIN));
        redisTemplate.opsForValue().set(dayKey, String.valueOf(count + 1), untilMidnight.isNegative() ? Duration.ofHours(1) : untilMidnight);
        if (mailService.ready()) {
            mailService.sendVerifyCode(email, normalizedScene, code);
            return new SmsSendVO(true, smsProperties.isExposeCode() ? code : null, "MAIL");
        }
        if (!smsProperties.isAllowConsole()) {
            throw new BizException(ErrorCode.MAIL_NOT_CONFIGURED);
        }
        printToConsole(normalizedScene, email, code);
        log.warn("SMS_ALLOW_CONSOLE 已开启，验证码仅打印控制台 scene={} email={}", normalizedScene, email);
        return new SmsSendVO(true, smsProperties.isExposeCode() ? code : null, "CONSOLE");
    }

    @Override
    public void verifyAndConsume(String target, String scene, String code) {
        String email = QqEmails.normalize(target);
        if (!StringUtils.hasText(code)) {
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        String key = "kean:sms:code:" + scene.trim().toUpperCase() + ":" + email;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached == null || !cached.equals(code.trim())) {
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        redisTemplate.delete(key);
    }

    private void printToConsole(String scene, String email, String code) {
        String banner = """
                
                ============================================================
                课安验证码（未配置 QQ 邮箱，仅打印控制台）
                场景: %s
                邮箱: %s
                验证码: %s
                有效期: 5 分钟
                ============================================================
                """.formatted(scene, email, code);
        System.out.println(banner);
        System.out.flush();
    }
}
