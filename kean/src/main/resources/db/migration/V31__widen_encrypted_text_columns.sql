-- 加宽需要落库加密的文本列。
--
-- 背景：这些列原本都是 VARCHAR(500)，而应用层加密用 Base64 承载密文，会膨胀：
--     密文长度 = 3 + 4 * ceil((明文 UTF-8 字节数 + 28) / 3)
--   按接口侧 @Size(max=500)（按 UTF-16 code unit 计数）算，最坏 500 个汉字 = 1500 字节，
--   密文 2043 字符 —— 塞不进 500。后果分两种，后者更糟：
--     · MySQL 严格模式（默认 STRICT_TRANS_TABLES）：写入直接报 1406 Data too long，接口 500
--     · 非严格模式：静默截断密文 → 下次读取 GCM 认证失败 → 数据不可逆损坏
--
-- 选 2048 的依据：最坏 2043 < 2048。这些列上都没有索引，加宽不需要重建索引。
-- 行大小也已核算：substitute_task 改后约 27.9KB、report / report_appeal 各约 16.4KB、
-- review 约 8.2KB，均远低于 InnoDB 65535 字节的行上限。
--
-- ⚠️ 本迁移必须与「字段加密代码」一起上线：Flyway 会在应用启动时先执行它，
--    所以只要同批发布，配置 DATA_ENC_KEY 就是安全的。

ALTER TABLE substitute_task
    MODIFY reason      VARCHAR(2048) NULL,
    MODIFY requirement VARCHAR(2048) NULL,
    MODIFY remark      VARCHAR(2048) NULL;

ALTER TABLE report
    MODIFY description   VARCHAR(2048) NULL,
    MODIFY handle_remark VARCHAR(2048) NULL;

ALTER TABLE report_appeal
    MODIFY content       VARCHAR(2048) NOT NULL,
    MODIFY handle_remark VARCHAR(2048) NULL;

ALTER TABLE review
    MODIFY content VARCHAR(2048) NULL;
