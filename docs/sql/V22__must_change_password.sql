-- 管理员首次登录须改密
ALTER TABLE sys_user
    ADD COLUMN must_change_password TINYINT NOT NULL DEFAULT 0 COMMENT '1=须修改密码后才能使用管理端' AFTER muted;
