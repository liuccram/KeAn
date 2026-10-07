-- 校区改为用户手动输入（选填）：新增文本列，两列都允许 NULL。
-- 旧数据不动：原 campus_id 保留不删、不改值；展示时优先用 campus_text，
-- 文本为空才回退到 campus_id 关联出来的旧校区名，所以历史任务/资料不会突然变空。
-- 注意 V32 仍然必需：新数据不再写 campus_id，必须允许该列为 NULL。
ALTER TABLE sys_user
    ADD COLUMN campus_text VARCHAR(50) NULL COMMENT '用户手输校区，选填，最长 50 字' AFTER campus_id;

ALTER TABLE substitute_task
    ADD COLUMN campus_text VARCHAR(50) NULL COMMENT '发布者手输校区，选填，最长 50 字' AFTER campus_id;
