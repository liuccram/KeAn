CREATE TABLE login_device (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    jti VARCHAR(64) NOT NULL,
    device_name VARCHAR(128) NOT NULL DEFAULT '未知设备',
    ip VARCHAR(64) NULL,
    last_seen_at DATETIME NULL,
    expire_at DATETIME NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_login_device_jti (jti),
    KEY idx_login_device_user (user_id)
) COMMENT '登录设备';
