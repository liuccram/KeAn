-- 课安 V34：私聊消息能力增强（连续序号 seq_no、幂等 local_id、消息状态、已读回执）
--
-- 背景：IM 按 box-im 规范重做。box 侧私聊消息表 im_private_message 的核心约定是
-- 「单个会话内 seq_no 连续递增」+「local_id 由前端生成用于幂等」+「status 表达
-- 未读/已发送/撤回/已读」，已读位点则落在会话维度的「读到的 seq_no」上。
-- 课安现有 chat_message / chat_session 没有这些列，本迁移把等价能力补上。
--
-- 设计约束（刻意为之）：
--   1) 新增列**全部允许 NULL**，不加 NOT NULL、不带 DEFAULT 语义。
--      目的：平滑回滚——老代码不写这些列也不会插入失败；将来撤掉功能时，
--      直接回滚应用代码即可，历史行的列为 NULL 不参与任何逻辑，无需删列。
--   2) 只 ADD COLUMN / ADD INDEX，**不改任何现有列的类型、不删列、不动 content
--      与 school_id 相关列**（content 仍是 VARCHAR(2000)，见 V1 第 130 行）。
--   3) 唯一索引在本迁移里用 NULL 语义表达「历史数据不需要满足唯一性」：
--      MySQL 唯一索引对 NULL 不去重，历史行这些列都是 NULL，互不冲突；
--      只有新写入的非 NULL 值才受唯一约束保护。
--
-- 影响的表（含 V1 现状，便于核对）：
--   chat_message  V1 第 125-134 行：id / session_id / sender_id / msg_type /
--                 content VARCHAR(2000) / deleted / created_at，
--                 已有索引 idx_chat_message_session_time (session_id, created_at)
--                 V25 追加索引：idx_chat_message_content (content(191)) 前缀索引
--   chat_session  V1 第 113-123 行：id / user_a_id / user_b_id / task_id /
--                 last_message_at / created_at / updated_at，
--                 已有 uk_chat_session_users (user_a_id, user_b_id)、
--                      idx_chat_session_task (task_id)
--                 V6 追加：last_content / a_unread / b_unread
--
-- 命名说明：索引名统一放表名前缀，避免和 V1/V6 已有索引撞名；
--   本库沿用 utf8mb4 / utf8mb4_unicode_ci（V1 全库一致），本迁移不引入任何
--   字符集或排序规则变更。

-- ---------------------------------------------------------------------------
-- 1) chat_message：序号、幂等标识、状态、已读时间
-- ---------------------------------------------------------------------------
ALTER TABLE chat_message
    ADD COLUMN seq_no BIGINT NULL COMMENT '会话内消息序号，单会话内连续递增；历史行为 NULL' AFTER session_id,
    ADD COLUMN local_id VARCHAR(64) NULL COMMENT '客户端生成的幂等标识，用于发送重试去重；历史行为 NULL' AFTER seq_no,
    ADD COLUMN status VARCHAR(16) NULL COMMENT '消息状态：SENT已发送 / READ已读 / RECALLED已撤回；历史行为 NULL' AFTER deleted,
    ADD COLUMN read_at DATETIME NULL COMMENT '接收方读取时间；未读或历史行为 NULL' AFTER status;

-- (session_id, seq_no)：拉取某会话 seq_no 之后增量消息，同时保证会话内序号不重复
ALTER TABLE chat_message
    ADD UNIQUE KEY uk_chat_message_session_seq (session_id, seq_no);

-- (sender_id, local_id)：同一发送者的 local_id 唯一，发送重试不会落两条
ALTER TABLE chat_message
    ADD UNIQUE KEY uk_chat_message_sender_local (sender_id, local_id);

-- ---------------------------------------------------------------------------
-- 2) chat_session：会话内最新序号 + 双方已读位点
--    用「读到的 seq_no」表示已读，而不是给每条消息写 read 标记，
--    与 box-im 的会话位点做法一致；a/b 对应用户 ID 较小/较大的一方，
--    与 V1 的 user_a_id(较小) / user_b_id(较大) 约定一致。
-- ---------------------------------------------------------------------------
ALTER TABLE chat_session
    ADD COLUMN last_seq_no BIGINT NULL COMMENT '本会话当前最大 seq_no，发消息时递增；历史会话为 NULL' AFTER last_content,
    ADD COLUMN a_read_seq BIGINT NULL COMMENT 'user_a 已读到的 seq_no；NULL 表示未产生过已读位点' AFTER last_seq_no,
    ADD COLUMN b_read_seq BIGINT NULL COMMENT 'user_b 已读到的 seq_no；NULL 表示未产生过已读位点' AFTER a_read_seq;
