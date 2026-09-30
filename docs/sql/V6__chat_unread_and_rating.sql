ALTER TABLE sys_user
    ADD COLUMN rating_avg DECIMAL(3, 2) NULL COMMENT '被评价平均分' AFTER completed_count,
    ADD COLUMN rating_count INT NOT NULL DEFAULT 0 COMMENT '被评价次数' AFTER rating_avg;

ALTER TABLE chat_session
    ADD COLUMN last_content VARCHAR(200) NULL COMMENT '最后一条预览' AFTER last_message_at,
    ADD COLUMN a_unread INT NOT NULL DEFAULT 0 COMMENT 'user_a 未读' AFTER last_content,
    ADD COLUMN b_unread INT NOT NULL DEFAULT 0 COMMENT 'user_b 未读' AFTER a_unread;
