-- 同一任务最多一条 ACCEPTED。先把历史脏数据收口到任务上记录的那条。
UPDATE substitute_application a
    INNER JOIN substitute_task t ON t.id = a.task_id
SET a.status = 'REJECTED'
WHERE a.status = 'ACCEPTED'
  AND (t.accepted_application_id IS NULL OR a.id <> t.accepted_application_id);

ALTER TABLE substitute_application
    ADD COLUMN accepted_task_id BIGINT
        GENERATED ALWAYS AS (IF(status = 'ACCEPTED', task_id, NULL)) STORED
        COMMENT '仅 ACCEPTED 时等于 task_id，保证一任务只匹配一人',
    ADD UNIQUE KEY uk_application_accepted_task (accepted_task_id);
