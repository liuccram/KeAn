package com.kean.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kean.config.AdminProperties;
import com.kean.entity.SysUser;
import com.kean.enums.UserRole;
import com.kean.enums.UserStatus;
import com.kean.mapper.SysUserMapper;
import com.kean.security.AdminPasswords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final SysUserMapper sysUserMapper;
    private final AdminProperties adminProperties;
    private final PasswordEncoder passwordEncoder;

    public AdminInitializer(
            SysUserMapper sysUserMapper,
            AdminProperties adminProperties,
            PasswordEncoder passwordEncoder
    ) {
        this.sysUserMapper = sysUserMapper;
        this.adminProperties = adminProperties;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long adminCount = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, UserRole.ADMIN.name())
        );
        if (adminCount == null || adminCount == 0) {
            seedAdmin();
        }
        markDefaultPasswordAdmins();
    }

    private void seedAdmin() {
        AdminPasswords.assertSeedPassword(adminProperties.getPassword());
        if (!StringUtils.hasText(adminProperties.getUsername())) {
            throw new IllegalStateException("未配置 ADMIN_USERNAME");
        }
        SysUser existed = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, adminProperties.getUsername())
        );
        if (existed != null) {
            log.warn("用户名 {} 已存在且不是管理员，跳过种子管理员", adminProperties.getUsername());
            return;
        }
        SysUser admin = new SysUser();
        admin.setRole(UserRole.ADMIN.name());
        admin.setUsername(adminProperties.getUsername());
        admin.setPasswordHash(passwordEncoder.encode(adminProperties.getPassword().trim()));
        admin.setNickname(adminProperties.getNickname());
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
        log.info("已创建种子管理员 {}，首次登录须修改密码", admin.getUsername());
    }

    private void markDefaultPasswordAdmins() {
        List<SysUser> admins = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, UserRole.ADMIN.name())
        );
        for (SysUser admin : admins) {
            if (admin.getMustChangePassword() != null && admin.getMustChangePassword() == 1) {
                continue;
            }
            if (passwordEncoder.matches(AdminPasswords.FORBIDDEN_DEFAULT, admin.getPasswordHash())) {
                admin.setMustChangePassword(1);
                sysUserMapper.updateById(admin);
                log.warn("管理员 {} 仍使用默认口令，已标记必须改密", admin.getUsername());
            }
        }
    }
}
