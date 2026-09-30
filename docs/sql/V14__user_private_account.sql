ALTER TABLE sys_user
    ADD COLUMN private_account TINYINT NOT NULL DEFAULT 0 COMMENT '1隐私账号 0公开' AFTER muted_until;
