ALTER TABLE report_appeal
    ADD COLUMN images_json VARCHAR(2000) NULL COMMENT '申诉图片' AFTER content;
