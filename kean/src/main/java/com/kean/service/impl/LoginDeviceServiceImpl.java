package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kean.common.ErrorCode;
import com.kean.entity.LoginDevice;
import com.kean.exception.BizException;
import com.kean.mapper.LoginDeviceMapper;
import com.kean.security.JwtService;
import com.kean.security.LoginUser;
import com.kean.security.SecurityUtils;
import com.kean.security.TokenBlacklistService;
import com.kean.service.LoginDeviceService;
import com.kean.service.NotificationService;
import com.kean.utils.IpUtils;
import com.kean.vo.LoginDeviceVO;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

@Service
public class LoginDeviceServiceImpl implements LoginDeviceService {

    private final LoginDeviceMapper loginDeviceMapper;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final NotificationService notificationService;

    public LoginDeviceServiceImpl(
            LoginDeviceMapper loginDeviceMapper,
            JwtService jwtService,
            TokenBlacklistService tokenBlacklistService,
            NotificationService notificationService
    ) {
        this.loginDeviceMapper = loginDeviceMapper;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.notificationService = notificationService;
    }

    @Override
    public void recordLogin(Long userId, String token, HttpServletRequest request) {
        Claims claims = jwtService.parse(token);
        String jti = claims.getId();
        if (!StringUtils.hasText(jti) || userId == null) {
            return;
        }
        String deviceName = resolveDeviceName(request);
        String ip = normalizeIp(request);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = toLocal(claims.getExpiration() == null ? null : claims.getExpiration().toInstant());
        LoginDevice existing = findByJti(jti);
        if (existing == null) {
            existing = findByDevice(userId, deviceName, ip);
        }
        if (existing == null) {
            LoginDevice device = new LoginDevice();
            device.setUserId(userId);
            device.setJti(jti);
            device.setDeviceName(deviceName);
            device.setIp(ip);
            device.setLoginCount(1);
            device.setDeleted(0);
            device.setLastSeenAt(now);
            device.setExpireAt(expireAt);
            saveDevice(device);
            notifyNewDevice(userId, deviceName, ip, now);
            return;
        }
        if (!Objects.equals(existing.getJti(), jti)) {
            blacklistJti(existing.getJti(), existing.getExpireAt());
            existing.setJti(jti);
            existing.setLoginCount(nextCount(existing.getLoginCount()));
        }
        existing.setUserId(userId);
        existing.setDeviceName(deviceName);
        existing.setIp(ip);
        existing.setLastSeenAt(now);
        existing.setExpireAt(expireAt);
        existing.setDeleted(0);
        if (existing.getLoginCount() == null || existing.getLoginCount() < 1) {
            existing.setLoginCount(1);
        }
        saveDevice(existing);
    }

    /**
     * 新设备登录提醒。
     *
     * 只在「此前从未见过这台设备」时提醒（按设备名 + IP 识别，与登录设备列表的分组口径一致），
     * 所以同一台设备反复登录不会刷屏。通知落库后由 NotificationService 顺带做实时推送，
     * 已登录的其他设备会立刻在消息页看到。
     *
     * 注意：通知是按用户存的，无法只发给「其他」设备，因此刚登录的这台也会看到同一条 ——
     * 这反过来也可以当作一份登录记录，不算坏事。
     */
    private void notifyNewDevice(Long userId, String deviceName, String ip, LocalDateTime at) {
        try {
            String time = at == null ? "" : at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            notificationService.notifyUser(
                    userId,
                    // 必须用 "SYSTEM"：消息页的两个 Tab 是按 type 过滤的
                    // （SYSTEM 归「系统」，TASK/APPLICATION 或 bizType 为 TASK/REVIEW 归「申请与履约」）。
                    // 用自定义 type 会落库但哪个 Tab 都看不到，而未读角标却会涨。
                    "SYSTEM",
                    "账号安全提醒",
                    "你的账号在一台新设备上登录（" + deviceName + "，IP " + ip + "，" + time
                            + "）。如果不是你本人操作，请立即修改密码。",
                    "USER",
                    userId
            );
        } catch (Exception ignored) {
            // 提醒失败不影响登录本身
        }
    }

    @Override
    public void touchCurrent(HttpServletRequest request) {
        LoginUser user = SecurityUtils.currentUserOrNull();
        if (user == null || !StringUtils.hasText(user.jti())) {
            return;
        }
        String deviceName = resolveDeviceName(request);
        String ip = normalizeIp(request);
        LoginDevice device = findByJti(user.jti());
        if (device == null) {
            device = findByDevice(user.userId(), deviceName, ip);
        }
        LocalDateTime now = LocalDateTime.now();
        if (device == null) {
            LoginDevice created = new LoginDevice();
            created.setUserId(user.userId());
            created.setJti(user.jti());
            created.setDeviceName(deviceName);
            created.setIp(ip);
            created.setLoginCount(1);
            created.setDeleted(0);
            created.setLastSeenAt(now);
            saveDevice(created);
            return;
        }
        if (!Objects.equals(device.getUserId(), user.userId())) {
            return;
        }
        if (!Objects.equals(device.getJti(), user.jti())) {
            blacklistJti(device.getJti(), device.getExpireAt());
            device.setJti(user.jti());
        }
        device.setLastSeenAt(now);
        device.setDeviceName(deviceName);
        if (!StringUtils.hasText(device.getIp())) {
            device.setIp(ip);
        }
        device.setDeleted(0);
        if (device.getLoginCount() == null || device.getLoginCount() < 1) {
            device.setLoginCount(1);
        }
        saveDevice(device);
    }

    @Override
    public void removeCurrent() {
        // 退出登录只作废令牌，设备记录留给下次登录累加次数
    }

    @Override
    public void removeByJti(String jti) {
        // 同上：不清记录，否则登录次数会一直停在 1
    }

    @Override
    public List<LoginDeviceVO> listMine() {
        LoginUser user = SecurityUtils.currentUser();
        return loginDeviceMapper.selectList(
                new LambdaQueryWrapper<LoginDevice>()
                        .eq(LoginDevice::getUserId, user.userId())
                        .orderByDesc(LoginDevice::getLastSeenAt)
                        .orderByDesc(LoginDevice::getId)
        ).stream().map(item -> toVo(item, user.jti())).toList();
    }

    @Override
    public void kick(Long deviceId) {
        LoginUser user = SecurityUtils.currentUser();
        LoginDevice device = loginDeviceMapper.selectById(deviceId);
        if (device == null || !Objects.equals(device.getUserId(), user.userId())) {
            throw new BizException(ErrorCode.USER_NOT_FOUND, "设备不存在");
        }
        if (StringUtils.hasText(user.jti()) && user.jti().equals(device.getJti())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前设备请使用退出登录");
        }
        Instant expireAt = device.getExpireAt() == null
                ? Instant.now().plus(Duration.ofDays(7))
                : device.getExpireAt().atZone(ZoneId.systemDefault()).toInstant();
        long ttlSeconds = Duration.between(Instant.now(), expireAt).getSeconds();
        tokenBlacklistService.blacklist(device.getJti(), ttlSeconds);
        loginDeviceMapper.deleteById(device.getId());
    }

    private LoginDevice findByJti(String jti) {
        return loginDeviceMapper.selectOne(
                new LambdaQueryWrapper<LoginDevice>().eq(LoginDevice::getJti, jti)
        );
    }

    private LoginDeviceVO toVo(LoginDevice device, String currentJti) {
        return new LoginDeviceVO(
                device.getId(),
                device.getDeviceName(),
                device.getIp(),
                StringUtils.hasText(currentJti) && currentJti.equals(device.getJti()),
                device.getLoginCount() == null ? 1 : device.getLoginCount(),
                device.getLastSeenAt(),
                device.getCreatedAt()
        );
    }

    private LoginDevice findByDevice(Long userId, String deviceName, String ip) {
        if (userId == null || !StringUtils.hasText(deviceName)) {
            return null;
        }
        String ipKey = ip == null ? "" : ip;
        return loginDeviceMapper.selectOne(
                new LambdaQueryWrapper<LoginDevice>()
                        .eq(LoginDevice::getUserId, userId)
                        .eq(LoginDevice::getDeviceName, deviceName)
                        .eq(LoginDevice::getIp, ipKey)
                        .orderByDesc(LoginDevice::getLastSeenAt)
                        .orderByDesc(LoginDevice::getId)
                        .last("LIMIT 1")
        );
    }

    private void saveDevice(LoginDevice device) {
        if (device.getId() == null) {
            loginDeviceMapper.insert(device);
        } else {
            loginDeviceMapper.updateById(device);
        }
    }

    private void blacklistJti(String jti, LocalDateTime expireAt) {
        if (!StringUtils.hasText(jti)) {
            return;
        }
        Instant expire = expireAt == null
                ? Instant.now().plus(Duration.ofDays(7))
                : expireAt.atZone(ZoneId.systemDefault()).toInstant();
        long ttlSeconds = Math.max(Duration.between(Instant.now(), expire).getSeconds(), 60);
        tokenBlacklistService.blacklist(jti, ttlSeconds);
    }

    private int nextCount(Integer current) {
        return current == null || current < 1 ? 2 : current + 1;
    }

    private String normalizeIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String ip = IpUtils.clientIp(request);
        return StringUtils.hasText(ip) ? ip.trim() : "";
    }

    private LocalDateTime toLocal(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private String resolveDeviceName(HttpServletRequest request) {
        String named = request.getHeader("X-Kean-Device");
        if (StringUtils.hasText(named)) {
            return named.length() > 120 ? named.substring(0, 120) : named.trim();
        }
        String ua = request.getHeader("User-Agent");
        if (!StringUtils.hasText(ua)) {
            return "未知设备";
        }
        String lower = ua.toLowerCase();
        if (lower.contains("iphone")) {
            return "iPhone";
        }
        if (lower.contains("ipad")) {
            return "iPad";
        }
        if (lower.contains("android")) {
            return "Android";
        }
        if (lower.contains("windows")) {
            return "Windows 浏览器";
        }
        if (lower.contains("mac os") || lower.contains("macintosh")) {
            return "Mac 浏览器";
        }
        if (lower.contains("linux")) {
            return "Linux 浏览器";
        }
        return "未知设备";
    }
}
