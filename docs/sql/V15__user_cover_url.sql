ALTER TABLE sys_user
    ADD COLUMN cover_url VARCHAR(512) NULL COMMENT '我的页背景图' AFTER avatar_url;
