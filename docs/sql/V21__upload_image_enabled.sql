INSERT INTO sys_config (config_key, config_value, remark)
VALUES ('upload.image.enabled', '1', '是否开放上传图片')
ON DUPLICATE KEY UPDATE remark = VALUES(remark);
