ALTER TABLE substitute_task
    ADD COLUMN require_photo TINYINT NOT NULL DEFAULT 0 COMMENT '是否要求现场拍照：0否 1是' AFTER computer_lab;

UPDATE substitute_task
SET require_photo = 1
WHERE require_photo = 0;
