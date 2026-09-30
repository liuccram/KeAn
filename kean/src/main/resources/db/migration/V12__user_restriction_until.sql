ALTER TABLE sys_user
    ADD COLUMN forbid_publish_until DATETIME NULL COMMENT '禁发截止时间' AFTER forbid_publish,
    ADD COLUMN forbid_apply_until DATETIME NULL COMMENT '禁申截止时间' AFTER forbid_apply,
    ADD COLUMN muted_until DATETIME NULL COMMENT '禁言截止时间' AFTER muted;
