ALTER TABLE review
    ADD COLUMN target_role VARCHAR(20) NOT NULL DEFAULT 'APPLICANT' COMMENT '被评身份 PUBLISHER/APPLICANT' AFTER to_user_id;

UPDATE review r
    INNER JOIN substitute_task t ON t.id = r.task_id
SET r.target_role = CASE
    WHEN r.to_user_id = t.publisher_id THEN 'PUBLISHER'
    ELSE 'APPLICANT'
END;

ALTER TABLE sys_user
    ADD COLUMN publish_completed_count INT NOT NULL DEFAULT 0 COMMENT '作为发布者完成次数' AFTER rating_count,
    ADD COLUMN publish_rating_avg DECIMAL(3, 2) NULL COMMENT '作为发布者被评均分' AFTER publish_completed_count,
    ADD COLUMN publish_rating_count INT NOT NULL DEFAULT 0 COMMENT '作为发布者被评次数' AFTER publish_rating_avg,
    ADD COLUMN apply_rating_avg DECIMAL(3, 2) NULL COMMENT '作为代课者被评均分' AFTER publish_rating_count,
    ADD COLUMN apply_rating_count INT NOT NULL DEFAULT 0 COMMENT '作为代课者被评次数' AFTER apply_rating_avg;

UPDATE sys_user u
LEFT JOIN (
    SELECT to_user_id AS uid, ROUND(AVG(rating), 2) AS avg_r, COUNT(*) AS cnt
    FROM review
    WHERE target_role = 'PUBLISHER'
    GROUP BY to_user_id
) p ON p.uid = u.id
SET u.publish_rating_avg = p.avg_r,
    u.publish_rating_count = IFNULL(p.cnt, 0);

UPDATE sys_user u
LEFT JOIN (
    SELECT to_user_id AS uid, ROUND(AVG(rating), 2) AS avg_r, COUNT(*) AS cnt
    FROM review
    WHERE target_role = 'APPLICANT'
    GROUP BY to_user_id
) a ON a.uid = u.id
SET u.apply_rating_avg = a.avg_r,
    u.apply_rating_count = IFNULL(a.cnt, 0);

UPDATE sys_user u
LEFT JOIN (
    SELECT publisher_id AS uid, COUNT(*) AS cnt
    FROM substitute_task
    WHERE status = 'COMPLETED'
    GROUP BY publisher_id
) t ON t.uid = u.id
SET u.publish_completed_count = IFNULL(t.cnt, 0);
