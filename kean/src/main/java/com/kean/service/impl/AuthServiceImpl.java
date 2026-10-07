package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kean.common.ErrorCode;
import com.kean.dto.ChangeEmailRequest;
import com.kean.dto.ChangePasswordRequest;
import com.kean.dto.DeleteAccountRequest;
import com.kean.dto.LoginRequest;
import com.kean.dto.RegisterRequest;
import com.kean.dto.ResetPasswordRequest;
import com.kean.dto.SendSmsRequest;
import com.kean.dto.UpdateProfileRequest;
import com.kean.entity.Campus;
import com.kean.entity.Notification;
import com.kean.entity.School;
import com.kean.entity.SysUser;
import com.kean.entity.UserBlacklist;
import com.kean.entity.UserFavorite;
import com.kean.enums.UserRole;
import com.kean.enums.UserStatus;
import com.kean.exception.BizException;
import com.kean.mapper.CampusMapper;
import com.kean.mapper.NotificationMapper;
import com.kean.mapper.SchoolMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.mapper.UserBlacklistMapper;
import com.kean.mapper.UserFavoriteMapper;
import com.kean.security.JwtService;
import com.kean.security.LoginUser;
import com.kean.security.SecurityUtils;
import com.kean.security.TokenBlacklistService;
import com.kean.security.TokenRevokeService;
import com.kean.service.AuthRateLimitService;
import com.kean.service.AuthService;
import com.kean.service.LoginDeviceService;
import com.kean.service.PresenceService;
import com.kean.service.SmsService;
import com.kean.service.TurnstileService;
import com.kean.utils.CampusNames;
import com.kean.utils.QqEmails;
import com.kean.service.SysConfigService;
import com.kean.utils.FileUrls;
import com.kean.utils.IpUtils;
import com.kean.vo.LoginVO;
import com.kean.vo.SmsSendVO;
import com.kean.vo.UserConverter;
import com.kean.vo.UserVO;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final int ENABLED = 1;
    private static final int MAX_SCHOOL_CHANGES = 3;

    /** 注销后对外显示的昵称。 */
    private static final String DELETED_NICKNAME = "已注销用户";

    private final SysUserMapper sysUserMapper;
    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;
    private final NotificationMapper notificationMapper;
    private final UserFavoriteMapper favoriteMapper;
    private final UserBlacklistMapper blacklistMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final TokenRevokeService tokenRevokeService;
    private final SmsService smsService;
    private final SysConfigService sysConfigService;
    private final PresenceService presenceService;
    private final LoginDeviceService loginDeviceService;
    private final TurnstileService turnstileService;
    private final AuthRateLimitService authRateLimitService;

    public AuthServiceImpl(
            SysUserMapper sysUserMapper,
            SchoolMapper schoolMapper,
            CampusMapper campusMapper,
            NotificationMapper notificationMapper,
            UserFavoriteMapper favoriteMapper,
            UserBlacklistMapper blacklistMapper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            TokenBlacklistService tokenBlacklistService,
            TokenRevokeService tokenRevokeService,
            SmsService smsService,
            SysConfigService sysConfigService,
            PresenceService presenceService,
            LoginDeviceService loginDeviceService,
            TurnstileService turnstileService,
            AuthRateLimitService authRateLimitService
    ) {
        this.sysUserMapper = sysUserMapper;
        this.schoolMapper = schoolMapper;
        this.campusMapper = campusMapper;
        this.notificationMapper = notificationMapper;
        this.favoriteMapper = favoriteMapper;
        this.blacklistMapper = blacklistMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.tokenRevokeService = tokenRevokeService;
        this.smsService = smsService;
        this.sysConfigService = sysConfigService;
        this.presenceService = presenceService;
        this.loginDeviceService = loginDeviceService;
        this.turnstileService = turnstileService;
        this.authRateLimitService = authRateLimitService;
    }

    @Override
    @Transactional
    public UserVO register(RegisterRequest request, HttpServletRequest httpRequest) {
        String ip = IpUtils.clientIp(httpRequest);
        turnstileService.verifyOrReject(request.turnstileToken(), ip);
        if (!sysConfigService.registerEnabled()) {
            throw new BizException(ErrorCode.REGISTER_CLOSED);
        }
        assertSchoolEnabled(request.schoolId());
        if (existsUsername(request.username())) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }
        String email = normalizeEmail(request.email());
        if (existsEmail(email)) {
            throw new BizException(ErrorCode.EMAIL_EXISTS);
        }
        smsService.verifyAndConsume(email, "REGISTER", request.smsCode());

        SysUser user = new SysUser();
        user.setRole(UserRole.USER.name());
        user.setUsername(request.username());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname());
        user.setGender(request.gender());
        user.setSchoolId(request.schoolId());
        // 校区改为用户手输文本（选填）：空值 = 没填，不再写 campus_id
        user.setCampusText(CampusNames.normalize(request.campusText()));
        user.setSchoolChangeCount(0);
        user.setCompletedCount(0);
        user.setRatingCount(0);
        user.setCancelledCount(0);
        user.setReportedCount(0);
        user.setStatus(UserStatus.NORMAL.name());
        user.setForbidPublish(0);
        user.setForbidApply(0);
        user.setMuted(0);
        sysUserMapper.insert(user);
        log.info("新注册用户：{}，学校id:{}", user.getUsername(), user.getSchoolId());
        return toUserVo(user);
    }

    @Override
    public SmsSendVO sendSms(SendSmsRequest request, HttpServletRequest httpRequest) {
        String ip = IpUtils.clientIp(httpRequest);
        String scene = request.scene().trim().toUpperCase();
        if (needsPublicTurnstile(scene)) {
            turnstileService.verifyOrReject(request.turnstileToken(), ip);
        }
        authRateLimitService.assertSmsAllowed(ip);
        if ("CHANGE_PASSWORD".equals(scene)) {
            SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
            if (user == null) {
                throw new BizException(ErrorCode.UNAUTHORIZED);
            }
            if (!StringUtils.hasText(user.getEmail())) {
                throw new BizException(ErrorCode.EMAIL_REQUIRED);
            }
            return smsService.send(user.getEmail(), scene);
        }
        String email = normalizeEmail(request.email());
        if ("FORGOT_PASSWORD".equals(scene)) {
            if (!existsEmail(email)) {
                return new SmsSendVO(true, null, "MAIL");
            }
            return smsService.send(email, scene);
        }
        if ("CHANGE_EMAIL".equals(scene)) {
            SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
            if (user == null) {
                throw new BizException(ErrorCode.UNAUTHORIZED);
            }
            if (email.equalsIgnoreCase(user.getEmail() == null ? "" : user.getEmail())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "新邮箱不能与当前绑定相同");
            }
            if (existsEmail(email, user.getId())) {
                throw new BizException(ErrorCode.EMAIL_EXISTS);
            }
            if (StringUtils.hasText(user.getEmail())) {
                return smsService.send(user.getEmail(), scene);
            }
            return smsService.send(email, scene);
        }
        if (existsEmail(email)) {
            throw new BizException(ErrorCode.EMAIL_EXISTS);
        }
        return smsService.send(email, scene);
    }

    @Override
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED);
        }
        if (!StringUtils.hasText(user.getEmail())) {
            throw new BizException(ErrorCode.EMAIL_REQUIRED);
        }
        smsService.verifyAndConsume(user.getEmail(), "CHANGE_PASSWORD", request.smsCode());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request, HttpServletRequest httpRequest) {
        turnstileService.verifyOrReject(request.turnstileToken(), IpUtils.clientIp(httpRequest));
        String email = normalizeEmail(request.email());
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, email)
        );
        if (user == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该 QQ 邮箱未注册");
        }
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED);
        }
        smsService.verifyAndConsume(email, "FORGOT_PASSWORD", request.smsCode());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional
    public UserVO changeEmail(ChangeEmailRequest request) {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED);
        }
        String email = normalizeEmail(request.email());
        if (email.equalsIgnoreCase(user.getEmail() == null ? "" : user.getEmail())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新邮箱不能与当前绑定相同");
        }
        if (existsEmail(email, user.getId())) {
            throw new BizException(ErrorCode.EMAIL_EXISTS);
        }
        String verifyTarget = StringUtils.hasText(user.getEmail()) ? user.getEmail() : email;
        smsService.verifyAndConsume(verifyTarget, "CHANGE_EMAIL", request.smsCode());
        user.setEmail(email);
        sysUserMapper.updateById(user);
        return toUserVo(user);
    }

    @Override
    @Transactional
    public UserVO updatePrivacy(Integer privateAccount) {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED);
        }
        user.setPrivateAccount(privateAccount != null && privateAccount == 1 ? 1 : 0);
        sysUserMapper.updateById(user);
        return toUserVo(user);
    }

    /**
     * 「仅允许一台设备在线」开关。写法与 {@link #updatePrivacy(Integer)} 完全一致。
     *
     * <p>打开时（写入 1）会<b>立刻</b>顶掉该用户其他设备的登录态，不必等下一次登录 ——
     * 否则别的设备还活着，用户会以为这个开关没作用。当前这台设备保留：
     * 取当前请求的 jti（{@link SecurityUtils#currentUserOrNull()} 的 {@code jti()}，
     * 与 {@code LoginDeviceServiceImpl} 里取当前设备 jti 的方式一致）传给
     * {@link LoginDeviceService#enforceSingleDevice(Long, String)}。
     *
     * <p>关闭时（写入 0）<b>什么都不做</b>：不拉黑、不删设备行、不发通知，
     * 与引入本开关之前的行为完全一致。
     *
     * <p>顺序是先写库再顶号，且顶号整段 try/catch：开关没写进库就谈不上顶号；
     * 而顶号失败（Redis 抖动、删行失败等）绝不能把已经写好的开关回滚掉。
     */
    @Override
    @Transactional
    public UserVO updateSingleDevice(Integer singleDevice) {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED);
        }
        user.setSingleDevice(singleDevice != null && singleDevice == 1 ? 1 : 0);
        sysUserMapper.updateById(user);
        if (user.getSingleDevice() == 1) {
            LoginUser current = SecurityUtils.currentUserOrNull();
            String keepJti = current == null ? null : current.jti();
            if (!StringUtils.hasText(keepJti)) {
                // 正常走鉴权流程不会到这里。真拿不到当前设备的 jti 时宁可不踢：
                // 若把 null 传下去，会把包括自己在内的所有设备一起踢掉，
                // 用户刚打开开关就被登出，比「暂时没顶号」更糟。
                log.warn("开启单设备在线但取不到当前 jti，跳过顶号：userId={}", user.getId());
            } else {
                try {
                    loginDeviceService.enforceSingleDevice(user.getId(), keepJti);
                } catch (Exception ex) {
                    // 顶号失败不影响开关：updateById 已执行，吞掉异常让事务继续提交。
                    log.warn("开启单设备在线后顶号失败（开关已生效）：userId={}", user.getId(), ex);
                }
            }
        }
        return toUserVo(user);
    }

    /**
     * 注销账号（不可逆）。<b>执行顺序是设计的一部分，不要调整</b>：
     *
     * <ol>
     *   <li><b>校验密码</b>：{@code PasswordEncoder#matches} 失败直接抛错，
     *       此时一个字节都不改（后面的步骤全都不执行）。</li>
     *   <li><b>拉黑全部登录设备的 jti</b>：{@link LoginDeviceService#revokeAll(Long)}
     *       复用 {@code kick}/{@code enforceSingleDevice} 那套拉黑逻辑，并顺手软删
     *       {@code login_device} 行，所以所有设备立刻失效。</li>
     *   <li><b>匿名化 {@code sys_user} 行</b>（逐列见下）。
     *       {@code email}/{@code phone} 必须真正置为 NULL：两个列都是唯一索引，
     *       MySQL 允许多个 NULL，所以不会冲突；反之如果留原值，这个邮箱/手机号
     *       就永远不能再被注册。</li>
     *   <li><b>逻辑删除</b>：{@code deleted = 1}（本项目统一逻辑删除，绝不物理删除）。</li>
     *   <li><b>清理只属于本人、不影响他人的数据</b>：站内通知、收藏、本人发起的黑名单
     *       （{@code login_device} 已在第 2 步处理）。</li>
     * </ol>
     *
     * <p><b>刻意不动的数据</b>：{@code substitute_task}、{@code chat_message}、{@code review}、
     * {@code report} 一行都不删 —— 对方的任务记录、聊天记录与信用评价都依赖它们，
     * 隐私政策也写明「与交易对方或信用体系相关的记录，注销后以去标识化方式保留」。
     * 作者本人已在第 3 步匿名化，这就足够了。
     *
     * <p>第 2 步不吞异常：Redis 拉黑失败会让整个事务回滚（什么都不改），
     * 避免出现「账号已匿名化但其他设备还能继续用」的中间态。
     */
    @Override
    @Transactional
    public void deleteAccount(DeleteAccountRequest request) {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED);
        }
        // 只允许普通学生自助注销；管理员账号走后台管理流程。
        // （SecurityConfig 里 /api/me/** 只要求 authenticated，所以必须在这里挡。）
        // 角色判断沿用本类 login() 的既有写法，不另造常量。
        if (!UserRole.USER.name().equals(user.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "管理员账号不能自助注销");
        }

        // 1. 校验当前密码。错误码风格与 AdminSystemServiceImpl.changeOwnPassword 的「原密码不正确」一致：
        //    用 40000 业务码而不是 40101，客户端才不会把「密码填错」当成登录态失效。
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前密码不正确");
        }

        // 2. 全部设备立刻失效（拉黑 jti + 软删 login_device 行）。
        loginDeviceService.revokeAll(user.getId());
        // 2.1 兜底：额外把「该用户此前签发的所有 Token」整体作废（TokenRevokeService，
        //     与封禁/管理员改密同一套机制，JwtAuthFilter 每个请求都会查）。
        //     只靠 jti 黑名单会漏掉一种情况：某台设备早先被踢下线时 device 行已软删，
        //     之后 Redis 里的黑名单键被清掉/淘汰 —— 那个 Token 就既不在 login_device 里，
        //     也没人知道它的 jti，注销后仍能通过鉴权。这一行把口子彻底堵上，
        //     对即将注销的账号没有任何副作用（该账号此后不可能再登录）。
        tokenRevokeService.revoke(user.getId());

        // 3. 匿名化。null 列必须用 UpdateWrapper 显式 set：updateById 默认 NOT_NULL 策略会跳过 null，
        //    清不掉 email/phone/avatar_url/cover_url（updateCover 清封面用的也是这个写法）。
        //    password_hash 写成一个「随机且不可用」的值：随机 UUID 的 BCrypt 哈希，
        //    原文不落库、谁也拿不到，所以原密码不可能再登录。
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId())
                .set(SysUser::getEmail, null)
                .set(SysUser::getPhone, null)
                .set(SysUser::getNickname, DELETED_NICKNAME)
                .set(SysUser::getAvatarUrl, null)
                .set(SysUser::getCoverUrl, null)
                .set(SysUser::getPasswordHash, passwordEncoder.encode(UUID.randomUUID().toString()))
                .set(SysUser::getUsername, anonymizedUsername(user.getId())));

        // 4. 逻辑删除（@TableLogic 的 deleteById 即 deleted = 1），不做物理删除。
        sysUserMapper.deleteById(user.getId());

        // 5. 只清理「只属于他」的数据。三个 mapper 对应的表都没有 deleted 列，
        //    照 FavoriteServiceImpl/BlacklistServiceImpl 的写法直接物理删除自己的行。
        notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, user.getId()));
        favoriteMapper.delete(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, user.getId()));
        // 只删「我拉黑别人」的行。「别人拉黑我」的行属于对方的黑名单数据，
        // 按设计不动（BlacklistServiceImpl.listMine 对已注销用户本就回退显示「同学」）。
        blacklistMapper.delete(new LambdaQueryWrapper<UserBlacklist>()
                .eq(UserBlacklist::getUserId, user.getId()));

        log.info("用户注销完成：id={}", user.getId());
    }

    /**
     * 注销后的用户名占位值：必须不含原邮箱、且全局唯一。
     *
     * <p>前缀 + 主键保证唯一（主键最长 19 位，13 + 19 = 32，正好不超 {@code username VARCHAR(32)}）。
     * 特意带一个 {@code '-'}：注册与建管理员的校验都是 {@code ^[a-zA-Z0-9_]+$}，
     * 所以这个值不可能被任何人再注册成功，也就不会撞上唯一索引
     * （逻辑删除行不参与应用层的重名校验，但数据库唯一索引仍然会拦）。
     */
    private String anonymizedUsername(Long userId) {
        return "deleted-user-" + userId;
    }

    @Override
    @Transactional
    public LoginVO login(LoginRequest request, HttpServletRequest httpRequest) {
        String ip = IpUtils.clientIp(httpRequest);
        turnstileService.verifyOrReject(request.turnstileToken(), ip);
        authRateLimitService.assertLoginAllowed(ip, request.username());
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.username())
        );
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            authRateLimitService.recordLoginFailure(ip, request.username());
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED);
        }
        authRateLimitService.clearLoginFailures(ip, request.username());
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(IpUtils.clientIp(httpRequest));
        sysUserMapper.updateById(user);

        String token = jwtService.createToken(user.getId(), user.getUsername(), user.getRole());
        loginDeviceService.recordLogin(user.getId(), token, httpRequest);
        if (UserRole.USER.name().equals(user.getRole())) {
            presenceService.heartbeat(user.getId());
        }
        return new LoginVO(token, toUserVo(user));
    }

    @Override
    public UserVO currentUser() {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED);
        }
        return toUserVo(user);
    }

    @Override
    @Transactional
    public UserVO updateProfile(UpdateProfileRequest request) {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED);
        }
        assertSchoolEnabled(request.schoolId());
        boolean schoolChanged = !Objects.equals(user.getSchoolId(), request.schoolId());
        if (schoolChanged) {
            int used = user.getSchoolChangeCount() == null ? 0 : user.getSchoolChangeCount();
            if (used >= MAX_SCHOOL_CHANGES) {
                throw new BizException(ErrorCode.SCHOOL_CHANGE_LIMIT);
            }
            user.setSchoolChangeCount(used + 1);
        }
        String campusText = CampusNames.normalize(request.campusText());
        user.setNickname(request.nickname().trim());
        user.setGender(request.gender());
        user.setSchoolId(request.schoolId());
        user.setCampusText(campusText);
        sysUserMapper.updateById(user);
        // 校区是手输文本又是可选项：清空时必须显式写 NULL（文本列 + 旧 campus_id），否则 MyBatis-Plus 会跳过 null 字段、旧校区被保留。
        if (campusText == null) {
            sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getId, user.getId())
                    .set(SysUser::getCampusText, null)
                    .set(SysUser::getCampusId, null));
        }
        return toUserVo(user);
    }

    @Override
    @Transactional
    public UserVO updateAvatar(String objectKey) {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String key = FileUrls.objectKey(objectKey);
        if (key == null || !key.startsWith("avatar/" + user.getId() + "/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "头像文件无效");
        }
        user.setAvatarUrl(key);
        sysUserMapper.updateById(user);
        return toUserVo(user);
    }

    @Override
    @Transactional
    public UserVO updateCover(String objectKey) {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String key = FileUrls.objectKey(objectKey);
        if (!StringUtils.hasText(key)) {
            sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getId, user.getId())
                    .set(SysUser::getCoverUrl, null));
            user.setCoverUrl(null);
            return toUserVo(user);
        }
        if (!key.startsWith("cover/" + user.getId() + "/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "背景图文件无效");
        }
        user.setCoverUrl(key);
        sysUserMapper.updateById(user);
        return toUserVo(user);
    }

    @Override
    public void logout(HttpServletRequest httpRequest) {
        String header = httpRequest.getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        Claims claims = jwtService.parse(header.substring(7));
        Instant expireAt = claims.getExpiration().toInstant();
        long ttlSeconds = Duration.between(Instant.now(), expireAt).getSeconds();
        tokenBlacklistService.blacklist(claims.getId(), ttlSeconds);
        loginDeviceService.removeByJti(claims.getId());
        try {
            presenceService.offline(Long.valueOf(claims.getSubject()));
        } catch (NumberFormatException ignored) {
            // ignore
        }
    }

    private UserVO toUserVo(SysUser user) {
        String schoolName = null;
        String campusName = null;
        if (user.getSchoolId() != null) {
            School school = schoolMapper.selectById(user.getSchoolId());
            if (school != null) {
                schoolName = school.getName();
            }
        }
        if (user.getCampusId() != null) {
            Campus campus = campusMapper.selectById(user.getCampusId());
            if (campus != null) {
                campusName = campus.getName();
            }
        }
        // 校区改为手输文本：优先文本，旧数据回退到上面的 campus_id 旧校区名
        campusName = CampusNames.display(user.getCampusText(), campusName);
        // 当前用户自己的资料：保留 phone / email 完整值（mine/profile.vue 与 mine/password.vue 依赖 email）
        return UserConverter.toVo(user, schoolName, campusName, false, true);
    }

    /**
     * 学校仍必填：必须存在且启用。
     * 校区已改为用户手输文本（选填），不再需要校验校区 id，也不写 campus_id。
     */
    private void assertSchoolEnabled(Long schoolId) {
        School school = schoolMapper.selectById(schoolId);
        if (school == null || school.getStatus() == null || school.getStatus() != ENABLED) {
            throw new BizException(ErrorCode.SCHOOL_INVALID);
        }
    }

    private boolean existsUsername(String username) {
        return sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)
        ) > 0;
    }

    private boolean existsEmail(String email) {
        return existsEmail(email, null);
    }

    private boolean existsEmail(String email, Long excludeUserId) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, email);
        if (excludeUserId != null) {
            wrapper.ne(SysUser::getId, excludeUserId);
        }
        return sysUserMapper.selectCount(wrapper) > 0;
    }

    private String normalizeEmail(String email) {
        return QqEmails.normalize(email);
    }

    private static boolean needsPublicTurnstile(String scene) {
        return "REGISTER".equals(scene) || "FORGOT_PASSWORD".equals(scene);
    }
}
