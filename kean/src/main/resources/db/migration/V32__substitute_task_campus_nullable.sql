-- 校区降级为可选项：发布代课时可以不填校区，因此放开 campus_id 的 NOT NULL。
-- 只改这一列的约束，不删除任何字段/表、不动数据；school_id 仍保持 NOT NULL
-- （发布时由服务端从发布者资料回填，请求里不接受学校）。
-- 索引 idx_task_campus 与行数据都不受影响。
ALTER TABLE substitute_task
    MODIFY COLUMN campus_id BIGINT NULL;
