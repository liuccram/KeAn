ALTER TABLE sys_user
    ADD COLUMN school_change_count INT NOT NULL DEFAULT 0 COMMENT '学校已修改次数，最多3次' AFTER campus_id;
