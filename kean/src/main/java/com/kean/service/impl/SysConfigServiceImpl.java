package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kean.common.ErrorCode;
import com.kean.entity.SysConfig;
import com.kean.exception.BizException;
import com.kean.mapper.SysConfigMapper;
import com.kean.service.SysConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SysConfigServiceImpl implements SysConfigService {

    private final SysConfigMapper sysConfigMapper;

    public SysConfigServiceImpl(SysConfigMapper sysConfigMapper) {
        this.sysConfigMapper = sysConfigMapper;
    }

    @Override
    public boolean registerEnabled() {
        String value = get("register.enabled");
        return value == null || "1".equals(value.trim());
    }

    @Override
    public boolean imageUploadEnabled() {
        String value = get("upload.image.enabled");
        return value == null || "1".equals(value.trim());
    }

    @Override
    public String get(String key) {
        SysConfig config = find(key);
        return config == null ? null : config.getConfigValue();
    }

    @Override
    @Transactional
    public void set(String key, String value) {
        SysConfig config = find(key);
        if (config == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知配置项");
        }
        config.setConfigValue(value);
        sysConfigMapper.updateById(config);
    }

    private SysConfig find(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key.trim()));
    }
}
