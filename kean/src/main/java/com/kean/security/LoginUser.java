package com.kean.security;

public record LoginUser(Long userId, String username, String role, String jti) {
}
