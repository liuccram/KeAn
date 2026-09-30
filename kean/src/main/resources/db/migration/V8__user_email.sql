ALTER TABLE sys_user
    ADD COLUMN email VARCHAR(64) NULL COMMENT 'QQ 邮箱，注册验证用' AFTER phone,
    ADD UNIQUE KEY uk_sys_user_email (email);
