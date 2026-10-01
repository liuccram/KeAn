-- 定时任务分布式锁（ShedLock）。
--
-- 单实例时这张表不起作用；但只要出现第二个实例（横向扩容，或滚动发布时新旧实例重叠），
-- @Scheduled 就会在两个进程里同时跑同一批任务 —— 重复发通知、completedCount 重复累加。
-- 加了 @SchedulerLock 之后，同一时刻只有一个实例真正执行，其余实例跳过本轮。
--
-- 表结构取自 ShedLock 官方文档（MySQL）。列名与长度不要随意改：
-- 它由 JdbcTemplateLockProvider 的内置 SQL 直接使用。
CREATE TABLE IF NOT EXISTS shedlock
(
    name       VARCHAR(64)  NOT NULL COMMENT '锁名，对应 @SchedulerLock(name = ...)',
    lock_until TIMESTAMP(3) NOT NULL COMMENT '持有到期时刻；到期后可被其它实例接管，避免实例崩溃后永久死锁',
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '取得锁的时刻',
    locked_by  VARCHAR(255) NOT NULL COMMENT '持有者标识（由 ShedLock 写入，便于排查是谁在跑）',
    PRIMARY KEY (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='定时任务分布式锁（ShedLock）';
