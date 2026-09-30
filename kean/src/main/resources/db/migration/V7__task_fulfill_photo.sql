ALTER TABLE substitute_task
    ADD COLUMN fulfill_photo_key VARCHAR(255) NULL COMMENT '代课者履约现场照片 object key' AFTER applicant_confirmed;
