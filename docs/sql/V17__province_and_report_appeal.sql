CREATE TABLE IF NOT EXISTS province (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(32) NOT NULL,
    sort INT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_province_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='省份';

INSERT IGNORE INTO province (id, name, sort) VALUES
(1, '北京', 1),
(2, '天津', 2),
(3, '河北', 3),
(4, '山西', 4),
(5, '内蒙古', 5),
(6, '辽宁', 6),
(7, '吉林', 7),
(8, '黑龙江', 8),
(9, '上海', 9),
(10, '江苏', 10),
(11, '浙江', 11),
(12, '安徽', 12),
(13, '福建', 13),
(14, '江西', 14),
(15, '山东', 15),
(16, '河南', 16),
(17, '湖北', 17),
(18, '湖南', 18),
(19, '广东', 19),
(20, '广西', 20),
(21, '海南', 21),
(22, '重庆', 22),
(23, '四川', 23),
(24, '贵州', 24),
(25, '云南', 25),
(26, '西藏', 26),
(27, '陕西', 27),
(28, '甘肃', 28),
(29, '青海', 29),
(30, '宁夏', 30),
(31, '新疆', 31),
(32, '香港', 32),
(33, '澳门', 33),
(34, '台湾', 34);

SET @has_province_id := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'school' AND COLUMN_NAME = 'province_id'
);
SET @sql := IF(@has_province_id = 0,
    'ALTER TABLE school ADD COLUMN province_id BIGINT NULL COMMENT ''省份ID'' AFTER name',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE school
SET province_id = 15
WHERE province_id IS NULL;

SET @has_old_province := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'school' AND COLUMN_NAME = 'province'
);
SET @sql := IF(@has_old_province = 1,
    'ALTER TABLE school DROP COLUMN province',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE school
    MODIFY province_id BIGINT NOT NULL;

SET @has_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'school' AND INDEX_NAME = 'idx_school_province'
);
SET @sql := IF(@has_idx = 0,
    'ALTER TABLE school ADD KEY idx_school_province (province_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS report_appeal (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    handler_id BIGINT NULL,
    handle_remark VARCHAR(500) NULL,
    handled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_report_appeal_report (report_id),
    KEY idx_report_appeal_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='举报处理申诉';
