package com.kean.service.impl;

import com.kean.entity.OperationLog;
import com.kean.mapper.OperationLogMapper;
import com.kean.security.LoginUser;
import com.kean.security.SecurityUtils;
import com.kean.service.OperationLogService;
import com.kean.utils.IpUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class OperationLogServiceImpl implements OperationLogService {

    private static final Logger log = LoggerFactory.getLogger(OperationLogServiceImpl.class);

    private final OperationLogMapper operationLogMapper;

    public OperationLogServiceImpl(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    @Override
    public void record(String operationType, String targetType, Object targetId, String description) {
        try {
            LoginUser admin = SecurityUtils.currentUserOrNull();
            if (admin == null) {
                return;
            }
            OperationLog row = new OperationLog();
            row.setAdminId(admin.userId());
            row.setAdminName(admin.username());
            row.setOperationType(operationType);
            row.setTargetType(targetType);
            row.setTargetId(targetId == null ? null : String.valueOf(targetId));
            row.setResult("SUCCESS");
            row.setIp(clientIp());
            row.setDescription(description);
            operationLogMapper.insert(row);
        } catch (Exception ex) {
            log.warn("写入操作日志失败: {}", ex.getMessage());
        }
    }

    private String clientIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        return request == null ? null : IpUtils.clientIp(request);
    }
}
