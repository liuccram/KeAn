package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kean.common.ErrorCode;
import com.kean.dto.ChangeEmailRequest;
import com.kean.dto.ChangePasswordRequest;
import com.kean.dto.LoginRequest;
import com.kean.dto.RegisterRequest;
import com.kean.dto.ResetPasswordRequest;
import com.kean.dto.SendSmsRequest;
import com.kean.dto.UpdateProfileRequest;
import com.kean.entity.Campus;
import com.kean.entity.School;
import com.kean.entity.SysUser;
import com.kean.enums.UserRole;
import com.kean.enums.UserStatus;
import com.kean.exception.BizException;
import com.kean.mapper.CampusMapper;
import com.kean.mapper.SchoolMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.JwtService;
import com.kean.security.LoginUser;
import com.kean.security.SecurityUtils;
import com.kean.security.TokenBlacklistService;
import com.kean.service.AuthRateLimitService;
import com.kean.service.AuthService;
import com.kean.service.LoginDeviceService;
import com.kean.service.PresenceService;
import com.kean.service.SmsService;
import com.kean.service.TurnstileService;
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

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final int ENABLED = 1;
    private static final int MAX_SCHOOL_CHANGES = 3;

    private final SysUserMapper sysUserMapper;
    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
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
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            TokenBlacklistService tokenBlacklistService,
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
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
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
        assertSchoolAndCampus(request.schoolId(), request.campusId());
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
        user.setCampusId(request.campusId());
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
        assertSchoolAndCampus(request.schoolId(), request.campusId());
        boolean schoolChanged = !Objects.equals(user.getSchoolId(), request.schoolId());
        if (schoolChanged) {
            int used = user.getSchoolChangeCount() == null ? 0 : user.getSchoolChangeCount();
            if (used >= MAX_SCHOOL_CHANGES) {
                throw new BizException(ErrorCode.SCHOOL_CHANGE_LIMIT);
            }
            user.setSchoolChangeCount(used + 1);
        }
        user.setNickname(request.nickname().trim());
        user.setGender(request.gender());
        user.setSchoolId(request.schoolId());
        user.setCampusId(request.campusId());
        sysUserMapper.updateById(user);
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
        // 当前用户自己的资料：保留 phone / email 完整值（mine/profile.vue 与 mine/password.vue 依赖 email）
        return UserConverter.toVo(user, schoolName, campusName, false, true);
    }

    private void assertSchoolAndCampus(Long schoolId, Long campusId) {
        School school = schoolMapper.selectById(schoolId);
        Campus campus = campusMapper.selectById(campusId);
        if (school == null || campus == null) {
            throw new BizException(ErrorCode.SCHOOL_INVALID);
        }
        if (school.getStatus() == null || school.getStatus() != ENABLED
                || campus.getStatus() == null || campus.getStatus() != ENABLED
                || !schoolId.equals(campus.getSchoolId())) {
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
