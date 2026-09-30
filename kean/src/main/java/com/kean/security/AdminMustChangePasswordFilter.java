package com.kean.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kean.common.ErrorCode;
import com.kean.common.Result;
import com.kean.entity.SysUser;
import com.kean.enums.UserRole;
import com.kean.mapper.SysUserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class AdminMustChangePasswordFilter extends OncePerRequestFilter {

    private final SysUserMapper sysUserMapper;
    private final ObjectMapper objectMapper;

    public AdminMustChangePasswordFilter(SysUserMapper sysUserMapper, ObjectMapper objectMapper) {
        this.sysUserMapper = sysUserMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        if (!path.startsWith("/api/admin")) {
            return true;
        }
        return "PUT".equalsIgnoreCase(request.getMethod()) && "/api/admin/me/password".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof LoginUser loginUser) || !UserRole.ADMIN.name().equals(loginUser.role())) {
            filterChain.doFilter(request, response);
            return;
        }
        SysUser admin = sysUserMapper.selectById(loginUser.userId());
        if (admin != null && admin.getMustChangePassword() != null && admin.getMustChangePassword() == 1) {
            response.setStatus(ErrorCode.MUST_CHANGE_PASSWORD.getHttpStatus().value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Result.fail(ErrorCode.MUST_CHANGE_PASSWORD));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
