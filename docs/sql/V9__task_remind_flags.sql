ALTER TABLE substitute_task
    ADD COLUMN photo_reminded TINYINT NOT NULL DEFAULT 0 COMMENT '是否已发送拍照提醒' AFTER cancelled_by,
    ADD COLUMN class_reminded TINYINT NOT NULL DEFAULT 0 COMMENT '是否已发送上课前提醒' AFTER photo_reminded,
    ADD COLUMN complete_reminded TINYINT NOT NULL DEFAULT 0 COMMENT '是否已发送确认完成提醒' AFTER class_reminded;
