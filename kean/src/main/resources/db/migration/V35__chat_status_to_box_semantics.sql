-- 课安 V35：把 V34 加进来的两列改成 box-im 的原始语义
--
-- 背景：V34 给 chat_message 补了 seq_no / local_id / status / read_at，但 status 沿用了
-- 课安自己的枚举字符串（'SENT'/'READ'/'RECALLED'），local_id 也放宽成了 VARCHAR(64)。
-- box-im 的 db/im-platform.sql（表 im_private_message）里这两列的原始定义是：
--     `local_id` varchar(32) comment '业务id,由前端生成'
--     `status`   tinyint not null comment '状态 0:未读 1:已发送 2:撤回 3:已读'
-- 本轮按 box 规范对齐：status 改 TINYINT、local_id 收窄到 VARCHAR(32)。
--
-- 本迁移只动这两列（外加必要的默认值/注释），不新增列、不删列、不动索引定义
-- （uk_chat_message_session_seq / uk_chat_message_sender_local 仍由 V34 建好，改列类型
-- 不会影响它们），也不碰 chat_message 里其它任何列。
--
-- 兼容性前提：chat_message 当前为 0 行（生产同样为空表），因此不存在需要保留的历史
-- 字符串状态；但本迁移仍然写成对"已有数据"安全的形式：
--   * 先校验再改，宁可让迁移失败也不静默丢数据；
--   * 所有 DDL 都用 information_schema 做前置判断，重复执行不会报错（幂等安全）。
--
-- 幂等安全说明：V34 是纯 ADD COLUMN，加出来的列不存在"精确类型"判断的歧义，
-- 所以这里可以直接对 information_schema.columns 做精确比较：
--   status   : 仅当 COLUMN_TYPE='varchar(16)'（V34 原样）时才 MODIFY
--   local_id : 仅当 COLUMN_TYPE='varchar(64)'（V34 原样）时才 MODIFY
-- 若列已经是目标类型（迁移被手工重跑过），下面的语句整体是空操作。

-- ---------------------------------------------------------------------------
-- 1) chat_message.status：VARCHAR(16) 字符串枚举 → TINYINT 数值枚举（box 语义）
--    0 = 未读
--    1 = 已发送（新消息的初始状态，也是本列的 DEFAULT）
--    2 = 撤回（本轮不实现撤回功能，仅保留取值位，后续阶段再用）
--    3 = 已读（接收方 markRead 时把 seq_no <= 位点 的消息置为该值）
--    TINYINT 不写 UNSIGNED，等价于 MySQL 默认的有符号 TINYINT，与 box 的 tinyint 一致；
--    取值范围足够容纳 0..3，也让将来可能的负值哨兵有意义。
-- ---------------------------------------------------------------------------

-- 1.1 先把已有数据里的非 box 取值归一化到 1（已发送）。
--     表当前为空，这条 UPDATE 影响 0 行；但保留它是为了"对已有数据安全"：
--     万一有库已经有 V34 之后写入的 'SENT'/'READ' 之类字符串，直接 MODIFY 成 TINYINT
--     会被 MySQL 静默转成 0（非法数值的告警式截断），而 0 在 box 语义里是"未读"，
--     会把消息误标成未读。所以先显式归一：NULL 以及不在 0..3 集合里的值 → 1。
--     '1'/'2'/'3'（MySQL 能无损解析成 0..3）会被保留，符合 box 取值。
--     这条语句重复执行是幂等的（第二次不再有匹配行）。
UPDATE chat_message
SET status = '1'
WHERE status IS NULL
   OR status NOT IN ('0', '1', '2', '3');

-- 1.2 改类型：TINYINT NOT NULL DEFAULT 1 + box 取值注释。
--     NOT NULL 需要把 NULL 收掉，上面 1.1 已经做完（NULL → 1，语义即"已发送"）。
SET @kean_stmt = (
    SELECT IF(
        COUNT(*) = 1,
        'ALTER TABLE chat_message MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1 '
            'COMMENT ''消息状态（对照 box-im im_private_message.status）0:未读 1:已发送 2:撤回 3:已读；新消息写入 1，接收方 markRead 置 3，2 本轮不产生''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_message'
      AND column_name = 'status'
      AND column_type = 'varchar(16)'
);
PREPARE kean_stmt FROM @kean_stmt;
EXECUTE kean_stmt;
DEALLOCATE PREPARE kean_stmt;

-- ---------------------------------------------------------------------------
-- 2) chat_message.local_id：VARCHAR(64) → VARCHAR(32)（box 原样）
--    加列时（V34）表里是空的，本轮同样为空；但收窄前仍然校验一次长度，
--    超长就主动失败（不静默截断），让 DBA 决定怎么处理。
-- ---------------------------------------------------------------------------

-- 2.1 长度校验：有任何超过 32 字符的 local_id 就主动报错中断迁移。
--     用 SIGNAL 而不是静默 UPDATE：截断会让唯一索引 (sender_id, local_id) 出现
--     "两条不同 local_id 截断后相同"的假冲突，属于必须人工确认的破坏性变更。
SET @kean_stmt = (
    SELECT IF(
        MAX(CHAR_LENGTH(local_id)) > 32,
        'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''V35 aborted: chat_message.local_id has values longer than 32 chars, please normalize before narrowing to VARCHAR(32)''',
        'SELECT 1'
    )
    FROM chat_message
    WHERE local_id IS NOT NULL
);
PREPARE kean_stmt FROM @kean_stmt;
EXECUTE kean_stmt;
DEALLOCATE PREPARE kean_stmt;

-- 2.2 改类型：保留 NULL 语义（历史行 / 不带 localId 的老客户端都可能为 NULL，
--     唯一索引对 NULL 不去重，V34 的幂等设计不变）。
--     字符集/排序规则沿用表默认 utf8mb4 / utf8mb4_unicode_ci，与本库其它列一致。
SET @kean_stmt = (
    SELECT IF(
        COUNT(*) = 1,
        'ALTER TABLE chat_message MODIFY COLUMN local_id VARCHAR(32) NULL '
            'COMMENT ''业务id，由客户端生成，用于发送幂等去重（对照 box-im im_private_message.local_id varchar(32)）；服务端未收到时自行生成；历史行为 NULL''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_message'
      AND column_name = 'local_id'
      AND column_type = 'varchar(64)'
);
PREPARE kean_stmt FROM @kean_stmt;
EXECUTE kean_stmt;
DEALLOCATE PREPARE kean_stmt;

-- ---------------------------------------------------------------------------
-- 3) 索引复核（不做任何变更，仅说明为什么不需要动）
--    uk_chat_message_sender_local (sender_id, local_id)：索引列长度随列收窄自动生效，
--        唯一性语义不变，无需重建。
--    uk_chat_message_session_seq (session_id, seq_no)：与本次两列无关。
--    两个索引在 V34 里已经建好且为唯一索引，所以"同一 (sender_id, local_id) 重复提交"
--    在数据库层依然有兜底，服务层捕获 DuplicateKey 后查回已存在的那条即可。
--
-- 附注：V34 里 status / local_id 的列注释写的是 V34 当时的字符串语义与 varchar(64)，
-- 已经过时。V34 是已经在生产执行过的迁移，改它会破坏 Flyway 的 checksum 校验，
-- 所以这里刻意不改 V34：以本迁移（V35）的列定义与注释为准。
-- ---------------------------------------------------------------------------
