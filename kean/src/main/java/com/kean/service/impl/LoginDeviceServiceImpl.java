package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kean.common.ErrorCode;
import com.kean.entity.LoginDevice;
import com.kean.entity.SysUser;
import com.kean.exception.BizException;
import com.kean.mapper.LoginDeviceMapper;
import com.kean.mapper.SysUserMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 登录设备管理：记录设备、列出设备、手动下线条设备，以及「仅允许一台设备在线」的顶号。
 *
 * <h2>「仅允许一台设备在线」现在是双开关，默认整体关闭</h2>
 * <p>顶号动作（把该用户其他设备的 jti 拉黑 + 软删 login_device 行）受<b>两道</b>条件约束，
 * 任何一道不满足都不会踢人：</p>
 * <ol>
 *   <li><b>全局配置开关</b> {@code kean.security.single-device.enabled}
 *       （环境变量 {@code KEAN_SECURITY_SINGLE_DEVICE_ENABLED}），<b>默认 {@code false}</b>
 *       —— 见 {@link #singleDeviceFeatureEnabled}。false 时本类<b>不做任何</b>互踢动作：
 *       不读 {@code sys_user.single_device} 列、不拉黑 jti、不软删设备行、不发顶号通知；</li>
 *   <li>用户自己的 {@code sys_user.single_device} 列（1 = 开启）。</li>
 * </ol>
 * <p>默认（false）下多端可同时在线，与引入该功能之前的行为逐字节一致。</p>
 *
 * <p><b>本类里不受该开关影响、必须保持原样的动作</b>（刻意区分，别一起关掉）：</p>
 * <ul>
 *   <li>{@link #recordLogin}：设备记录 + 新设备登录提醒 + 「<b>同一台设备换了 jti</b>」时
 *       拉黑自己那条旧 jti —— 这是设备记录的自清理，不是互踢；</li>
 *   <li>{@link #revokeAll}：注销账号时让<b>全部</b>设备（含当前这台）失效；</li>
 *   <li>{@link #kick}：用户在「管理登录设备」页主动把某台设备下线。</li>
 * </ul>
 *
 * <h2>⚠️ 边界：即便这里全部放开，box-im 的 im-server 仍会按 devId 挤下线</h2>
 * <p>迁移到 box-im 的多端模型后，本类的互踢已默认关闭，但 <b>im-server 侧的限制依然存在</b>：
 * 同一 {@code (userId, terminal)} 再来一条连接时，im-server 的 {@code LoginProcessor} 会按
 * <b>终端码 {@code devId}</b> 判断「是不是同一台设备」—— 同一个 devId = 挤掉旧连接；
 * <b>不同 devId = 给旧 server 投一条 {@code im:user:force_logout:{serverId}}</b>（把旧设备踢下线）。
 * 详见 {@code docs/ops/im-server-patch.md} §1.5。</p>
 * <p>因此实际可达的状态是：</p>
 * <ul>
 *   <li>✅ <b>手机 A + 电脑</b> 可以并存（{@code terminal} 不同）；</li>
 *   <li>❌ <b>两台手机</b>（同一个 {@code terminal}、不同 {@code devId}）仍会互踢 ——
 *       <b>这是 box-im 上游的既有行为，不是课安的 bug，也不是本开关没生效</b>。</li>
 * </ul>
 * <p>要真正支持「两台手机同时在线」，必须改 im-server（独立仓库，本次不动）。</p>
 */
@Service
public class LoginDeviceServiceImpl implements LoginDeviceService {

    private static final Logger log = LoggerFactory.getLogger(LoginDeviceServiceImpl.class);

    private final LoginDeviceMapper loginDeviceMapper;
    private final SysUserMapper sysUserMapper;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final NotificationService notificationService;

    /**
     * 「仅允许一台设备在线」的<b>全局</b>开关，默认 {@code false}（即默认不启用单设备限制）。
     *
     * <p>与项目其余开关写法一致：默认值写在 {@code @Value} 占位符里，
     * <b>不写进 {@code application*.yml}</b>。可用环境变量
     * {@code KEAN_SECURITY_SINGLE_DEVICE_ENABLED=true}（Spring relaxed binding →
     * {@code kean.security.single-device.enabled}）打开。</p>
     *
     * <p>为 {@code false} 时 {@link #enforceSingleDevice(Long, String)} 第一行就返回，
     * 「顶号」整条链路完全不执行。</p>
     */
    private final boolean singleDeviceFeatureEnabled;

    public LoginDeviceServiceImpl(
            LoginDeviceMapper loginDeviceMapper,
            JwtService jwtService,
            TokenBlacklistService tokenBlacklistService,
            NotificationService notificationService,
            SysUserMapper sysUserMapper,
            @Value("${kean.security.single-device.enabled:false}") boolean singleDeviceFeatureEnabled
    ) {
        this.loginDeviceMapper = loginDeviceMapper;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.notificationService = notificationService;
        this.sysUserMapper = sysUserMapper;
        this.singleDeviceFeatureEnabled = singleDeviceFeatureEnabled;
        // 启动即把「单设备限制到底开没开」打出来（照 ImSenderService 的启动日志风格）。
        // 目的：运维能一眼确认配置有没有被 Spring 读到 —— 只看 /proc/<pid>/environ 或 .env.prod
        // 只能证明「变量注入了」，不能证明「@Value 解析成功」（键名拼错会静默沿用默认 false）。
        // 我们踩过「以为是关的、其实是开的」的坑，所以开启/关闭都要打，且都带配置项全名。
        String hint = "（配置项 kean.security.single-device.enabled / 环境变量 KEAN_SECURITY_SINGLE_DEVICE_ENABLED，"
                + "默认 false）";
        if (singleDeviceFeatureEnabled) {
            log.warn("[单设备限制] singleDeviceFeatureEnabled=true{} → 顶号互踢已启用："
                    + "对 sys_user.single_device=1 的用户，每次登录都会把其他设备顶下线", hint);
        } else {
            log.info("[单设备限制] singleDeviceFeatureEnabled=false{} → 顶号互踢已停用："
                    + "不读 sys_user.single_device、不拉黑其他设备、不软删 login_device 行，多端可同时在线", hint);
        }
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
            // 原来这条分支直接 return；顶号处理必须在「记录完本次设备」之后接上，
            // 否则首次登录（设备表里还没有这台设备）时不会踢掉其他设备。
            // 注意：该方法自己会先查全局开关（默认 false = 直接返回），此处不做前置判断，
            // 保证「判定只有一处」，不会出现两地口径不一致。
            enforceSingleDevice(userId, jti);
            return;
        }
        if (!Objects.equals(existing.getJti(), jti)) {
            // ⚠️ 这不是「单设备互踢」，不要跟着开关一起关掉：
            // 同一台设备（设备名 + IP 相同）换了一次 jti，说明它自己上一次的登录态已经失效，
            // 这里拉黑的是**自己那条旧 jti**，不影响其他设备，也不受单设备开关约束。
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
        enforceSingleDevice(userId, jti);
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

    /**
     * 「仅允许一台设备在线」的顶号动作：把该用户除 {@code keepJti} 以外的登录态全部顶下线。
     *
     * <p>两个调用点：{@link #recordLogin} 传本次登录的 jti（新设备登录即顶号）；
     * {@code AuthServiceImpl.updateSingleDevice} 传当前请求所在设备的 jti
     * （打开开关时立刻生效，不必等下次登录）。方法本身不关心触发场景。
     *
     * <p><b>先判断再执行，且判断两道</b>：
     * <ol>
     *   <li>{@link #singleDeviceFeatureEnabled}（{@code kean.security.single-device.enabled}，
     *       <b>默认 false</b>）为 false 时<b>第一行就返回</b> —— 连 {@code sys_user} 都不查，
     *       更不会拉黑 jti、软删设备行、发顶号通知。即「模块级关闭」；</li>
     *   <li>该开关为 true 时，再看用户自己的 {@code sys_user.single_device} 是否为 1。
     *       为 0（默认值）时同样什么都不做，只多一次按主键读用户记录。</li>
     * </ol>
     *
     * <p>拉黑对象：该用户当前未被软删的 {@code login_device} 行里，jti 与 {@code keepJti}
     * 不同的那些（即其他设备）。传入的这台设备永远排除在外 —— 与 {@link #kick(Long)}
     * 「拒绝踢当前设备」的口径一致。
     *
     * <p>ttl 直接复用 {@link #blacklistJti(String, LocalDateTime)}，与 {@code kick} 以及
     * 本类中「同一台设备换了 jti」时的做法保持同一套算法：按该 device 行的
     * {@code expire_at} 计算剩余秒数，{@code expire_at} 为空时兜底 7 天，下限 60 秒。
     * 之所以不另写一套，是因为同一件事两处口径早晚会不一致。
     *
     * <p>同时把被顶掉的 device 行软删（{@code @TableLogic} 的 {@code deleteById} 即置
     * {@code deleted = 1}）：{@link #listMine()} 只查未删除的行，所以开启开关后用户在
     * 「登录设备」页看到的就只有当前这一台，不会留下一排已经被踢掉、点「退出登录」
     * 还会报错的僵尸设备。记录不做物理删除，{@code login_count}、最后在线时间等历史仍可查。
     *
     * <p><b>⚠️ 只关掉互踢，不动设备记录本身</b>：{@link #listMine()}「管理登录设备」页
     * 与 {@link #kick(Long)} 手工下线<b>不依赖本方法</b>，关掉开关后它们照常工作。
     */
    @Override
    public void enforceSingleDevice(Long userId, String keepJti) {
        // 全局开关默认 false = 不启用单设备限制：整段逻辑（含读 sys_user 那一步）直接跳过。
        if (!singleDeviceFeatureEnabled) {
            return;
        }
        if (userId == null) {
            return;
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getSingleDevice() == null || user.getSingleDevice() != 1) {
            return;
        }
        List<LoginDevice> devices = loginDeviceMapper.selectList(
                new LambdaQueryWrapper<LoginDevice>().eq(LoginDevice::getUserId, userId)
        );
        int kicked = 0;
        for (LoginDevice device : devices) {
            if (keepJti != null && keepJti.equals(device.getJti())) {
                continue;
            }
            blacklistJti(device.getJti(), device.getExpireAt());
            loginDeviceMapper.deleteById(device.getId());
            kicked += 1;
        }
        if (kicked > 0) {
            notifySingleDeviceKick(userId, kicked);
        }
    }

    /**
     * 真的顶掉了其他设备才提醒（没踢到就不发，所以正常单端用户不会被刷屏）。
     *
     * <p>文案要同时覆盖两种触发：新设备登录顶号、以及刚打开开关时立刻顶号 ——
     * 所以不提「新设备登录」，只说因为开启了「仅允许一台设备在线」，其他设备已被退出。
     *
     * <p>通知按用户存、所有设备都会看到，所以写成对谁读都成立的「其他设备」，
     * 而不是「本设备」。被踢的那台设备下一次请求会拿到 40102，客户端随即弹
     * 「已退出登录」并回到登录页 —— 这条通知是那份提示的留痕，
     * 也覆盖了「被踢时 App 不在前台、当时看不到弹窗」的情况。
     */
    private void notifySingleDeviceKick(Long userId, int kicked) {
        try {
            notificationService.notifyUser(
                    userId,
                    // 必须用 "SYSTEM"：消息页两个 Tab 按 type 过滤，自定义 type 会落库但哪个 Tab 都看不到。
                    "SYSTEM",
                    "账号安全提醒",
                    "你的账号开启了「仅允许一台设备在线」，"
                            + (kicked > 1 ? "其他 " + kicked + " 台设备" : "其他设备")
                            + "已被退出登录。如非本人操作，请立即修改密码。",
                    "USER",
                    userId
            );
        } catch (Exception ignored) {
            // 提醒失败不影响登录本身
        }
    }

    /**
     * 注销账号：把该用户全部登录设备（含当前这台）的 jti 拉黑，并软删 device 行。
     *
     * <p>与 {@link #enforceSingleDevice(Long, String)} 逐行同构，只是没有 {@code keepJti}
     * —— 账号都要注销了，没有任何设备需要留下。这里<b>不做 try/catch</b>：
     * 调用方是注销流程，Redis 拉黑失败必须让整个注销事务回滚（什么都不改），
     * 否则会出现「账号已匿名化但其他设备还活着」的中间态。
     */
    @Override
    public void revokeAll(Long userId) {
        if (userId == null) {
            return;
        }
        List<LoginDevice> devices = loginDeviceMapper.selectList(
                new LambdaQueryWrapper<LoginDevice>().eq(LoginDevice::getUserId, userId)
        );
        for (LoginDevice device : devices) {
            blacklistJti(device.getJti(), device.getExpireAt());
            loginDeviceMapper.deleteById(device.getId());
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
