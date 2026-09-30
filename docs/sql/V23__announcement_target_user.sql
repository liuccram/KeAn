ALTER TABLE announcement
    ADD COLUMN target_user_id BIGINT NULL COMMENT '指定用户，scope=USER 时使用' AFTER school_id,
    ADD INDEX idx_announcement_target_user (target_user_id);
