-- 文件读取归属校验所需的查询索引。
--
-- 背景：FileAccessGuard 在放行敏感文件前，需要按对象键反查归属关系。
-- 缺索引时下面两条查询会全表扫描：
--   1) chat_message.content = ?            —— 判断请求者是否为该图片所在会话的参与者
--   2) substitute_task.fulfill_photo_key = ? —— 判断请求者是否为该任务的发布者或被接受的申请人

-- chat_message.content 是 VARCHAR(2000)，utf8mb4 下整列索引会超过 InnoDB 3072 字节上限，
-- 因此改用前缀索引。图片消息的 content 就是一个 object key（形如 chat/42/uuid.jpg，约 60 字符），
-- 191 前缀足以完整覆盖；文本消息只会占用同样的前缀，不影响正确性。
ALTER TABLE chat_message
    ADD INDEX idx_chat_message_content (content(191));

-- fulfill_photo_key 是 VARCHAR(255)，utf8mb4 下 1020 字节，可直接整列索引。
-- 该列绝大多数行为 NULL，索引体积很小。
ALTER TABLE substitute_task
    ADD INDEX idx_task_fulfill_photo (fulfill_photo_key);
