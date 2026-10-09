-- =============================================================================
-- im-platform.sql —— box-im 官方建表 SQL（原样落地，独立库 im_platform）
-- =============================================================================
-- 【来源】bluexsx/box-im  master 分支
--   仓库     https://github.com/bluexsx/box-im
--   文件     https://github.com/bluexsx/box-im/blob/master/db/im-platform.sql
--   取回地址 https://raw.githubusercontent.com/bluexsx/box-im/master/db/im-platform.sql
--   文件 blob SHA  74e22fd2c8b8a541abc2f9dc3667b159ade6abf4
--   最后改动该文件的 commit  d9e7781c898319b478e5175bc03bddfb20602f5b
--                            （"增加会话置顶功能"，2026-08-13T09:05:31Z）
--   取回时间 本会话；HTTP 200，原文 8501 字节
--
--   注：jsdelivr 镜像（cdn.jsdelivr.net/gh/...）因返回 Content-Type: application/sql
--   被取回工具拒绝，改用 GitHub raw 原文，内容即 master 当前版本。
--
-- 【为什么放独立库 im_platform，不和 kean 同库】
--   1) kean 用 Flyway 全量纳管（V1..V33，见 kean/src/main/resources/db/migration/），
--      box-im 这里是一次性 SQL（upstream 根本没有 flyway_schema_history 概念）。
--      两者塞进同一个 schema，Flyway 的 history 与 box 的表会互相干扰。
--   2) 本文件全部是 CREATE TABLE，没有 IF NOT EXISTS。放在 kean 库会：
--      · 与 kean 同名表冲突（kean 自己也有同名业务表/列语义不同的表）时直接失败；
--      · 被 Flyway 的 clean / validate 流程误伤（clean 会按 schema 清库）。
--   3) box-im 后续重做 IM 时整库重建最省事：DROP DATABASE im_platform; 再重跑本文件，
--      完全不影响 kean 库的数据与迁移历史。
--
-- 【本次环境适配（逐条，仅环境，不动 box 规范）】
--   A. 文件头加 `CREATE DATABASE IF NOT EXISTS im_platform` + `USE im_platform`，
--      让本文件可像 upstream 一样「一次性整文件执行」。upstream 原文没有这两句
--      （它默认你已手选库）。
--   B. 字符集：upstream 每张表写的是 `charset = utf8mb4`（未指定 collation），
--      由服务器默认排序规则决定。本环境统一显式补 `collate = utf8mb4_general_ci`，
--      与服务器实测默认排序规则一致，因此**不改变任何既有行为**。
--   C. 引擎：upstream 已是 `engine = innodb`，保持原样，未改。
--   D. 其余一律保持原文：表名、列名、列顺序、类型与长度、default、comment、
--      index/key 名称与列顺序、unique 与普通索引的区分、大小写风格（`unique key`
--      小写、`key` 小写、engine/charset 小写）全部逐字照抄，
--      未做任何「优化 / 改名 / 合并 / 补列 / 补索引」。
--      （`unique key idx_user_name` 写在 user_name 上、`key idx_md5` 写在 md5 上，
--        看似冗余，属 upstream 原样，不动。）
--
-- 【表清单（9 张，与 upstream 一致；列数 / 索引数逐表点数）】
--   im_user              14 列  2 索引 (unique idx_user_name; key idx_nick_name)
--   im_friend            10 列  2 索引 (unique idx_user_friend_id; key idx_friend_id)
--   im_private_message    9 列  3 索引 (key idx_conv_key_seq_no / idx_send_recv_id / idx_recv_id)
--   im_group              9 列  0 索引
--   im_group_member      13 列  2 索引 (key idx_group_id; key idx_user_id)
--   im_group_message     12 列  2 索引 (key idx_group_id_seq_no / idx_send_time)
--   im_sensitive_word     5 列  0 索引
--   im_file_info         10 列  1 索引 (key idx_md5)
--   im_message_deletion   7 列  1 索引 (key idx_user_id)
--   合计 89 列 / 13 索引。原文中**没有**外键（FOREIGN KEY）、没有
--   任何 seed / INSERT 语句。
--
-- 【seed 数据说明（重要偏差）】
--   任务预期「原文若含敏感词初始数据」。实测：upstream 的 im-platform.sql
--   **不含任何 INSERT**，敏感词初始数据是 db/ 目录下**另一个独立文件**
--   `敏感词库初始化.sql`（44990 字节，全是对 im_sensitive_word 的单行 INSERT）。
--   本文件不混入该 seed（保持 im-platform.sql 逐字为建表原文）。该 seed 的
--   取回地址与用法见文件末尾注释。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS im_platform
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE im_platform;

-- -----------------------------------------------------------------------------
-- 以下 9 张表为 bluexsx/box-im@master db/im-platform.sql 原文（逐字照抄）
-- -----------------------------------------------------------------------------

create table `im_user`
(
    `id`               bigint       not null auto_increment primary key comment 'id',
    `user_name`        varchar(255) not null comment '用户名',
    `nick_name`        varchar(255) not null comment '用户昵称',
    `head_image`       varchar(255)  default '' comment '用户头像',
    `head_image_thumb` varchar(255)  default '' comment '用户头像缩略图',
    `password`         varchar(255) not null comment '密码',
    `sex`              tinyint       default 0 comment '性别 0:男 1:女',
    `is_banned`        tinyint(1) default 0 comment '是否被封禁 0:否 1:是',
    `reason`           varchar(255)  default '' comment '被封禁原因',
    `type`             smallint      default 1 comment '用户类型 1:普通用户 2:审核账户',
    `signature`        varchar(1024) default '' comment '个性签名',
    `last_login_time`  datetime      default null comment '最后登录时间',
    `created_time`     datetime      default current_timestamp comment '创建时间',
    unique key `idx_user_name` (user_name),
    key                `idx_nick_name` (nick_name)
) engine = innodb charset = utf8mb4 collate = utf8mb4_general_ci comment '用户';

create table `im_friend`
(
    `id`                bigint       not null auto_increment primary key comment 'id',
    `user_id`           bigint       not null comment '用户id',
    `friend_id`         bigint       not null comment '好友id',
    `friend_nick_name`  varchar(255) not null comment '好友昵称',
    `friend_head_image` varchar(255) default '' comment '好友头像',
    `is_dnd`            tinyint      default 0 comment '免打扰标识(do not disturb)  0:关闭   1:开启',
    `is_top`            tinyint(1)   default 0 comment '是否置顶会话',
    `deleted`           tinyint      default 0 comment '删除标识  0：正常   1：已删除',
    `created_time`      datetime     default current_timestamp comment '创建时间',
    `version`           BIGINT       default 0 comment '版本号',
    UNIQUE KEY `idx_user_friend_id` (`user_id`, `friend_id`),
    key                 `idx_friend_id` (`friend_id`)
) engine = innodb charset = utf8mb4 collate = utf8mb4_general_ci comment '好友';

create table `im_private_message`
(
    `id`        bigint      not null auto_increment primary key comment 'id',
    `local_id`  varchar(32) comment '业务id,由前端生成',
    `seq_no`    int         not null comment '序列号,单个会话消息的序号连续递增',
    `send_id`   bigint      not null comment '发送用户id',
    `recv_id`   bigint      not null comment '接收用户id',
    `conv_key`  varchar(64) not null comment '会话key，格式:userId1_userId2',
    `content`   text character set utf8mb4 comment '发送内容',
    `type`      tinyint     not null comment '消息类型 0:文字 1:图片 2:文件 3:语音 4:视频 21:提示',
    `status`    tinyint     not null comment '状态 0:未读 1:已发送 2:撤回 3:已读',
    `send_time` datetime(3) default current_timestamp(3) comment '发送时间',
    key         `idx_conv_key_seq_no`(`conv_key`,`seq_no`),
    key         `idx_send_recv_id` (`send_id`, `recv_id`,`id`),
    key         `idx_recv_id` (`recv_id`)
) engine = innodb charset = utf8mb4 collate = utf8mb4_general_ci comment '私聊消息';

create table `im_group`
(
    `id`               bigint       not null auto_increment primary key comment 'id',
    `name`             varchar(255) not null comment '群名字',
    `owner_id`         bigint       not null comment '群主id',
    `head_image`       varchar(255)  default '' comment '群头像',
    `head_image_thumb` varchar(255)  default '' comment '群头像缩略图',
    `notice`           varchar(1024) default '' comment '群公告',
    `is_banned`        tinyint(1) default 0 comment '是否被封禁 0:否 1:是',
    `reason`           varchar(255)  default '' comment '被封禁原因',
    `dissolve`         tinyint(1) default 0 comment '是否已解散',
    `created_time`     datetime      default current_timestamp comment '创建时间'
) engine = innodb charset = utf8mb4 collate = utf8mb4_general_ci comment '群';

create table `im_group_member`
(
    `id`                bigint not null auto_increment primary key comment 'id',
    `group_id`          bigint not null comment '群id',
    `user_id`           bigint not null comment '用户id',
    `user_nick_name`    varchar(255) default '' comment '用户昵称',
    `remark_nick_name`  varchar(255) default '' comment '显示昵称备注',
    `head_image`        varchar(255) default '' comment '用户头像',
    `remark_group_name` varchar(255) default '' comment '显示群名备注',
    `is_dnd`            tinyint(1) comment '免打扰标识(do not disturb)  0:关闭   1:开启',
    `is_top`            tinyint(1) default 0 comment '是否置顶会话',
    `quit`              tinyint(1) default 0 comment '是否已退出',
    `quit_time`         datetime     default null comment '退出时间',
    `created_time`      datetime     default current_timestamp comment '创建时间',
    `version`           bigint       default 0 comment '版本号',
    key                 `idx_group_id` (`group_id`),
    key                 `idx_user_id` (`user_id`)
) engine = innodb charset = utf8mb4 collate = utf8mb4_general_ci comment '群成员';

create table `im_group_message`
(
    `id`             bigint  not null auto_increment primary key comment 'id',
    `local_id`       varchar(32) comment '业务id,由前端生成',
    `group_id`       bigint  not null comment '群id',
    `seq_no`         int     not null comment '序列号,单个会话消息的序号连续递增',
    `send_id`        bigint  not null comment '发送用户id',
    `send_nick_name` varchar(255) default '' comment '发送用户昵称',
    `content`        text character set utf8mb4 comment '发送内容',
    `at_user_ids`    varchar(1024) comment '被@的用户id列表，逗号分隔',
    `receipt`        tinyint(1) default 0 comment '是否回执消息',
    `receipt_ok`     tinyint(1) default 0 comment '回执消息是否完成',
    `type`           tinyint not null comment '消息类型 0:文字 1:图片 2:文件 3:语音 4:视频 21:提示',
    `status`         tinyint      default 0 comment '状态 0:未发出  2:撤回 ',
    `send_time`      datetime(3) default current_timestamp(3) comment '发送时间',
    key              `idx_group_id_seq_no` (`group_id`,`seq_no`),
    key              `idx_send_time` (`send_time`)
) engine = innodb charset = utf8mb4 collate = utf8mb4_general_ci comment '群消息';

create table `im_sensitive_word`
(
    `id`          bigint      not null auto_increment primary key comment 'id',
    `content`     varchar(64) not null comment '敏感词内容',
    `enabled`     tinyint(1) default 0 comment '是否启用 0:未启用 1:启用',
    `creator`     bigint   default null comment '创建者',
    `create_time` datetime default current_timestamp comment '创建时间'
) engine = innodb charset = utf8mb4 collate = utf8mb4_general_ci comment '敏感词';

create table `im_file_info`
(
    `id`              bigint       not null auto_increment primary key comment 'id',
    `file_name`       varchar(255) not null comment '文件名',
    `file_path`       varchar(255) not null comment '文件地址',
    `file_size`       integer      not null comment '文件大小',
    `file_type`       tinyint      not null comment '0:普通文件 1:图片 2:视频',
    `compressed_path` varchar(255) default null comment '压缩文件路径',
    `cover_path`      varchar(255) default null comment '封面文件路径，仅视频文件有效',
    `upload_time`     datetime     default current_timestamp comment '上传时间',
    `is_permanent`    tinyint(1) default 0 comment '是否永久文件',
    `md5`             varchar(64)  not null comment '文件md5',
    key               `idx_md5` (md5)
) engine = innodb charset = utf8mb4 collate = utf8mb4_general_ci comment '文件';

create table `im_message_deletion`
(
    `id`          bigint  not null auto_increment primary key comment 'id',
    `user_id`     bigint  not null comment '用户id',
    `chat_type`   tinyint not null comment '会话类型 1:私聊 2:群聊',
    `chat_id`     bigint  not null comment '好友id、群聊id',
    `message_id`  bigint(20) comment '消息id',
    `delete_type` tinyint not null comment '删除类型 1:按消息删除 2:按会话删除',
    `delete_time` datetime default current_timestamp comment '消息删除时间',
    key           `idx_user_id` (`user_id`)
) engine = innodb charset = utf8mb4 collate = utf8mb4_general_ci comment '消息删除记录';

-- =============================================================================
-- 【可选 seed】敏感词初始数据（upstream 独立文件，未混入上方建表原文）
-- =============================================================================
-- 来源：https://raw.githubusercontent.com/bluexsx/box-im/master/db/%E6%95%8F%E6%84%9F%E8%AF%8D%E5%BA%93%E5%88%9D%E5%A7%8B%E5%8C%96.sql
-- blob SHA f6e46aa17ce4d15100bc4102008dc58857229bfd，44990 字节
-- 内容形态（原文即此形态，每行一条）：
--     INSERT INTO `im_sensitive_word` (`content`, `enabled`, `creator`) VALUES ('爱液', 1, 10000);
-- 按本任务「只产出 im-platform.sql」的边界，未合并到本文件；需要时单独执行该文件即可
-- （它只依赖 im_sensitive_word 表，需先执行本文件建表）。
-- =============================================================================
