CREATE TABLE announcement (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title         VARCHAR(100)  NOT NULL,
    content       VARCHAR(2000) NOT NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED / OFFLINE',
    scope         VARCHAR(16)   NOT NULL DEFAULT 'ALL' COMMENT 'ALL / SCHOOL',
    school_id     BIGINT        NULL,
    publisher_id  BIGINT        NOT NULL,
    published_at  DATETIME      NULL,
    deleted       TINYINT       NOT NULL DEFAULT 0,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_announcement_status (status, published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台公告';
