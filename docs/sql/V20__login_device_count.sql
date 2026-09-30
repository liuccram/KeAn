ALTER TABLE login_device
    ADD COLUMN login_count INT NOT NULL DEFAULT 1 COMMENT '登录次数' AFTER ip;

UPDATE login_device k
JOIN (
    SELECT user_id,
           device_name,
           IFNULL(ip, '') AS ip_key,
           COUNT(*) AS cnt,
           SUBSTRING_INDEX(GROUP_CONCAT(id ORDER BY last_seen_at DESC, id DESC), ',', 1) AS keep_id
    FROM login_device
    WHERE deleted = 0
    GROUP BY user_id, device_name, IFNULL(ip, '')
    HAVING COUNT(*) > 1
) s ON k.id = CAST(s.keep_id AS UNSIGNED)
SET k.login_count = s.cnt;

UPDATE login_device d
JOIN (
    SELECT user_id,
           device_name,
           IFNULL(ip, '') AS ip_key,
           SUBSTRING_INDEX(GROUP_CONCAT(id ORDER BY last_seen_at DESC, id DESC), ',', 1) AS keep_id
    FROM login_device
    WHERE deleted = 0
    GROUP BY user_id, device_name, IFNULL(ip, '')
    HAVING COUNT(*) > 1
) s ON d.user_id = s.user_id
   AND d.device_name = s.device_name
   AND IFNULL(d.ip, '') = s.ip_key
SET d.deleted = 1
WHERE d.deleted = 0
  AND d.id <> CAST(s.keep_id AS UNSIGNED);
