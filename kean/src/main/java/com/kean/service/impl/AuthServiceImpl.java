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
