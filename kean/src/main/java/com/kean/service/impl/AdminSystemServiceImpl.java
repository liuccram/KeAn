package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kean.common.ErrorCode;
import com.kean.common.PageResult;
import com.kean.common.Pages;
import com.kean.dto.AdminChangePasswordRequest;
import com.kean.dto.AdminUserStatusRequest;
import com.kean.dto.CreateAdminRequest;
import com.kean.dto.UpdateConfigRequest;
import com.kean.entity.OperationLog;
import com.kean.entity.SysConfig;
import com.kean.entity.SysUser;
import com.kean.enums.UserRole;
import com.kean.enums.UserStatus;
import com.kean.exception.BizException;
import com.kean.mapper.OperationLogMapper;
import com.kean.mapper.SysConfigMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.AdminGuard;
import com.kean.security.AdminPasswords;
import com.kean.security.LoginUser;
import com.kean.security.TokenRevokeService;
import com.kean.service.AdminSystemService;
import com.kean.service.OperationLogService;
import com.kean.service.SysConfigService;
import com.kean.vo.AdminAccountVO;
import com.kean.vo.OperationLogVO;
import com.kean.vo.SysConfigVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

@Service
public class AdminSystemServiceImpl implements AdminSystemService {

    private static final Set<String> CONFIG_KEYS = Set.of("site.name", "register.enabled", "upload.image.enabled");

    private final SysUserMapper sysUserMapper;
    private final OperationLogMapper operationLogMapper;
    private final SysConfigMapper sysConfigMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevokeService tokenRevokeService;
    private final OperationLogService operationLogService;
    private final SysConfigService sysConfigService;

    public AdminSystemServiceImpl(
            SysUserMapper sysUserMapper,
            OperationLogMapper operationLogMapper,
            SysConfigMapper sysConfigMapper,
            PasswordEncoder passwordEncoder,
            TokenRevokeService tokenRevokeService,
            OperationLogService operationLogService,
            SysConfigService sysConfigService
    ) {
        this.sysUserMapper = sysUserMapper;
        this.operationLogMapper = operationLogMapper;
        this.sysConfigMapper = sysConfigMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenRevokeService = tokenRevokeService;
        this.operationLogService = operationLogService;
        this.sysConfigService = sysConfigService;
    }

    @Override
    public List<AdminAccountVO> listAdmins() {
        AdminGuard.require();
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, UserRole.ADMIN.name())
                        .orderByAsc(SysUser::getId))
                .stream()
                .map(this::toAdmin)
                .toList();
    }

    @Override
    @Transactional
    public AdminAccountVO createAdmin(CreateAdminRequest request) {
        AdminGuard.require();
        Long exists = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.username()));
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }
        AdminPasswords.rejectIfDefault(request.password());
        SysUser admin = new SysUser();
        admin.setRole(UserRole.ADMIN.name());
        admin.setUsername(request.username());
        admin.setPasswordHash(passwordEncoder.encode(request.password()));
        admin.setNickname(request.nickname());
        admin.setCompletedCount(0);
        admin.setRatingCount(0);
        admin.setCancelledCount(0);
        admin.setReportedCount(0);
        admin.setStatus(UserStatus.NORMAL.name());
        admin.setForbidPublish(0);
        admin.setForbidApply(0);
        admin.setMuted(0);
        admin.setMustChangePassword(1);
        sysUserMapper.insert(admin);
        operationLogService.record("ADMIN_CREATE", "ADMIN", admin.getId(), admin.getUsername());
        return toAdmin(admin);
    }

    @Override
    @Transactional
    public void changeOwnPassword(AdminChangePasswordRequest request) {
        LoginUser current = AdminGuard.require();
        SysUser admin = sysUserMapper.selectById(current.userId());
        if (admin == null || !UserRole.ADMIN.name().equals(admin.getRole())) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(request.oldPassword(), admin.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "原密码不正确");
        }
        if (request.oldPassword().equals(request.newPassword())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码不能与原密码相同");
        }
        AdminPasswords.rejectIfDefault(request.newPassword());
        admin.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        admin.setMustChangePassword(0);
        sysUserMapper.updateById(admin);
        operationLogService.record("ADMIN_CHANGE_PASSWORD", "ADMIN", admin.getId(), null);
    }

    @Override
    @Transactional
    public AdminAccountVO updateAdminStatus(Long id, AdminUserStatusRequest request) {
        LoginUser current = AdminGuard.require();
        if (id.equals(current.userId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能停用自己的账号");
        }
        SysUser admin = sysUserMapper.selectById(id);
        if (admin == null || !UserRole.ADMIN.name().equals(admin.getRole())) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        String status = request.status().trim().toUpperCase();
        if (UserStatus.BANNED.name().equals(status)) {
            long active = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getRole, UserRole.ADMIN.name())
                    .eq(SysUser::getStatus, UserStatus.NORMAL.name())
                    .ne(SysUser::getId, id));
            if (active < 1) {
                throw new BizException(ErrorCode.BAD_REQUEST, "至少保留一名启用中的管理员");
            }
            admin.setStatus(UserStatus.BANNED.name());
            sysUserMapper.updateById(admin);
            tokenRevokeService.revoke(admin.getId());
            operationLogService.record("ADMIN_BAN", "ADMIN", admin.getId(), request.remark());
        } else {
            admin.setStatus(UserStatus.NORMAL.name());
            sysUserMapper.updateById(admin);
            operationLogService.record("ADMIN_UNBAN", "ADMIN", admin.getId(), request.remark());
        }
        return toAdmin(admin);
    }

    @Override
    public PageResult<OperationLogVO> listLogs(Long adminId, String operationType, String from, String to, Long page, Long size) {
        AdminGuard.require();
        long pageNo = Pages.page(page);
        long pageSize = Pages.size(size);
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<OperationLog>().orderByDesc(OperationLog::getId);
        if (adminId != null) {
            wrapper.eq(OperationLog::getAdminId, adminId);
        }
        if (StringUtils.hasText(operationType)) {
            wrapper.eq(OperationLog::getOperationType, operationType.trim());
        }
        if (StringUtils.hasText(from)) {
            wrapper.ge(OperationLog::getCreatedAt, parseDate(from).atStartOfDay());
        }
        if (StringUtils.hasText(to)) {
            wrapper.lt(OperationLog::getCreatedAt, parseDate(to).plusDays(1).atStartOfDay());
        }
        Page<OperationLog> result = operationLogMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return new PageResult<>(result.getRecords().stream().map(this::toLog).toList(), result.getTotal(), pageNo, pageSize);
    }

    @Override
    public List<SysConfigVO> listConfig() {
        AdminGuard.require();
        return sysConfigMapper.selectList(new LambdaQueryWrapper<SysConfig>().orderByAsc(SysConfig::getId)).stream()
                .filter(item -> CONFIG_KEYS.contains(item.getConfigKey()))
                .map(item -> new SysConfigVO(item.getConfigKey(), item.getConfigValue(), item.getRemark()))
                .toList();
    }

    @Override
    @Transactional
    public List<SysConfigVO> updateConfig(UpdateConfigRequest request) {
        AdminGuard.require();
        for (UpdateConfigRequest.ConfigItem item : request.items()) {
            if (item == null || !StringUtils.hasText(item.key()) || !CONFIG_KEYS.contains(item.key().trim())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "未知配置项");
            }
            String value = item.value() == null ? "" : item.value().trim();
            if (("register.enabled".equals(item.key().trim()) || "upload.image.enabled".equals(item.key().trim()))
                    && !value.equals("0") && !value.equals("1")) {
                throw new BizException(ErrorCode.BAD_REQUEST, item.key().trim() + " 仅支持 0 或 1");
            }
            sysConfigService.set(item.key().trim(), value);
            operationLogService.record("CONFIG_UPDATE", "CONFIG", item.key(), value);
        }
        return listConfig();
    }

    private AdminAccountVO toAdmin(SysUser user) {
        return new AdminAccountVO(user.getId(), user.getUsername(), user.getNickname(), user.getStatus(), user.getLastLoginAt(), user.getCreatedAt());
    }

    private OperationLogVO toLog(OperationLog log) {
        return new OperationLogVO(
                log.getId(),
                log.getAdminId(),
                log.getAdminName(),
                log.getOperationType(),
                log.getTargetType(),
                log.getTargetId(),
                log.getResult(),
                log.getIp(),
                log.getDescription(),
                log.getCreatedAt()
        );
    }

    private LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "日期格式应为 yyyy-MM-dd");
        }
    }
}
