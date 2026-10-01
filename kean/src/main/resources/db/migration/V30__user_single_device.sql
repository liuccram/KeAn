ALTER TABLE sys_user
    ADD COLUMN single_device TINYINT NOT NULL DEFAULT 0 COMMENT '1=仅允许一台设备在线' AFTER private_account;
