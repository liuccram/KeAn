# IM 迁移说明（两个库的边界与执行方法）

本文档说明「IM 按 box-im 重做」涉及的两份 SQL 各归哪个库、怎么执行、怎么回滚。
本阶段**只交付 SQL 与文档**，未改动任何 Java / Vue / TS 代码，也未连接数据库执行。

---

## 1. 两个库的边界

| | kean 库（现有业务库） | `im_platform` 库（新建） |
|---|---|---|
| 管纳方式 | **Flyway** 版本化迁移，后端启动时自动执行 | **一次性 SQL**，手工执行 |
| 脚本位置 | `kean/src/main/resources/db/migration/V1..V34` | [`docs/sql/im-platform.sql`](../sql/im-platform.sql) |
| 内容 | 课安业务表 + IM 消息能力增强（V34 只加列加索引） | box-im 自带 9 张 `im_*` 表，**原样照抄** |
| 版本记录 | `flyway_schema_history` | 无（upstream 没有这个概念） |
| 回滚手段 | 回滚应用代码即可（列可空，见第 3 节） | 整库 `DROP DATABASE im_platform` 重建 |

**为什么必须分开**：box-im 是一次性整文件 SQL（全是 `CREATE TABLE`，无 `IF NOT EXISTS`），
kean 是 Flyway 全量纳管（`V1..V33` 已占用，V34 本次新增）。两者放同一个 schema 会互踩：
重跑 box 建表会因表已存在直接失败；Flyway 的 `clean` 会按 schema 清库，把 box 的表一并清掉；
反过来 box 的表也不在 Flyway 版本序列里，无法 validate。分开后各自独立演进、独立回滚。

---

## 2. 应用方法

### 2.1 V34：Flyway 自动执行（无需手工操作）

`kean/src/main/resources/db/migration/V34__chat_seq_and_receipt.sql`
在后端重启时由 Flyway 自动执行（与 V1..V33 同一机制）。执行后可用下面语句核对：

```sql
SELECT version, description, success FROM flyway_schema_history WHERE version = '34';
```

> 本阶段**未执行**任何数据库操作（任务硬性要求）。V34 的落地发生在下次后端启动时。

### 2.2 `im-platform.sql`：手工一次性执行

该脚本**不含 `IF NOT EXISTS`**，属于一次性脚本，重复执行会报「表已存在」，属预期。
用服务器上的 MySQL 容器执行（脚本自带 `CREATE DATABASE IF NOT EXISTS im_platform` + `USE im_platform`）：

```bash
docker exec -i mysql_nzpx-mysql_nzPX-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < docs/sql/im-platform.sql
```

执行后核对 9 张表都建出来了：

```bash
docker exec -i mysql_nzpx-mysql_nzPX-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW TABLES FROM im_platform"'
```

预期输出恰好 9 行：`im_user`、`im_friend`、`im_private_message`、`im_group`、
`im_group_member`、`im_group_message`、`im_sensitive_word`、`im_file_info`、`im_message_deletion`。

### 2.3 可选：敏感词初始数据

upstream 的建表 SQL **不含**任何 seed；敏感词初始数据在 box-im 仓库的**另一个文件**
`db/敏感词库初始化.sql`（44990 字节，全是对 `im_sensitive_word` 的单行 `INSERT`：

```
INSERT INTO `im_sensitive_word` (`content`, `enabled`, `creator`) VALUES ('爱液', 1, 10000);
```

取回地址：`https://raw.githubusercontent.com/bluexsx/box-im/master/db/敏感词库初始化.sql`
（blob SHA `f6e46aa17ce4d15100bc4102008dc58857229bfd`）。

按本次任务「产出 `docs/sql/im-platform.sql`」的边界，**未**把这份 seed 合并进建表文件。
需要时先用同样方式手工执行该文件（它依赖 `im_sensitive_word` 表，须先执行 2.2）。

---

## 3. 回滚

### 3.1 V34（kean 库）

V34 **只新增列和索引，未改任何现有列的类型、未删列**，且新增列**全部允许 NULL**：

| 表 | 新增列 | 允许 NULL | 新增索引 |
|---|---|---|---|
| `chat_message` | `seq_no` | 是 | `uk_chat_message_session_seq (session_id, seq_no)` 唯一 |
| `chat_message` | `local_id` | 是 | `uk_chat_message_sender_local (sender_id, local_id)` 唯一 |
| `chat_message` | `status` | 是 | — |
| `chat_message` | `read_at` | 是 | — |
| `chat_session` | `last_seq_no` | 是 | — |
| `chat_session` | `a_read_seq` | 是 | — |
| `chat_session` | `b_read_seq` | 是 | — |

**安全回退方式：只回滚应用代码，不必删列。**
因为列可空，旧代码的 `INSERT` 不写这些列也不会失败，历史行值为 `NULL`，
不参与任何业务逻辑，唯一的副作用只是表上多了几个空列和两个唯一索引。

若确实要清干净（**非必需**，属可选的彻底回退，按相反顺序执行）：

```sql
ALTER TABLE chat_session
    DROP COLUMN b_read_seq,
    DROP COLUMN a_read_seq,
    DROP COLUMN last_seq_no;

ALTER TABLE chat_message
    DROP INDEX uk_chat_message_sender_local,
    DROP INDEX uk_chat_message_session_seq,
    DROP COLUMN read_at,
    DROP COLUMN status,
    DROP COLUMN local_id,
    DROP COLUMN seq_no;
```

> 同时需要从 `flyway_schema_history` 删除 version=34 的记录，否则 Flyway 会因
> checksum/版本缺失报错。这一步**不要手工做**，建议的做法是回退代码后保留列不动。

### 3.2 `im_platform` 库

整库删除即可完全回滚，不影响 kean 库：

```bash
docker exec -i mysql_nzpx-mysql_nzPX-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS im_platform"'
```

需要重新建时再执行第 2.2 节的命令即可（建表脚本幂等于「空库 → 9 张表」）。

---

## 4. 与 box-im 上游的版本对应

| 项 | 值 |
|---|---|
| 仓库 | `https://github.com/bluexsx/box-im` |
| 分支 | `master` |
| 文件 | `db/im-platform.sql` |
| 取回地址 | `https://raw.githubusercontent.com/bluexsx/box-im/master/db/im-platform.sql` |
| **文件 blob SHA** | `74e22fd2c8b8a541abc2f9dc3667b159ade6abf4` |
| 最后改动该文件的 commit | `d9e7781c898319b478e5175bc03bddfb20602f5b`（"增加会话置顶功能"，2026-08-13T09:05:31Z） |
| 原文大小 | 8501 字节 |
| 落地位置 | [`docs/sql/im-platform.sql`](../sql/im-platform.sql) |
| 可选的敏感词 seed | `db/敏感词库初始化.sql`，blob SHA `f6e46aa17ce4d15100bc4102008dc58857229bfd`，44990 字节（**未**合并进建表文件） |

kean V34 是**课安自己的**增强迁移，不对应 box-im 某个具体 SQL 文件；它借用的是 box-im
私聊消息的语义约定（`im_private_message` / `im_group_message` 的 `local_id`、`seq_no`、
`status`，以及会话维度已读位点），但落在课安现有的 `chat_message` / `chat_session` 表上。

---

## 5. 环境适配清单（针对 `docs/sql/im-platform.sql`）

1. 文件头新增 `CREATE DATABASE IF NOT EXISTS im_platform` + `USE im_platform`，
   使整文件可一次性执行（upstream 无这两句，默认你已手选库）。
2. 字符集：upstream 写 `charset = utf8mb4` 未指定 collation，本环境显式统一补
   `collate = utf8mb4_general_ci`（与服务器实测默认排序规则一致，不改变既有行为）。
3. 引擎：upstream 已是 `engine = innodb`，保持原样。
4. 其余一律逐字照抄：表名、列名、列顺序、类型与长度、`default`、`comment`、
   索引名与索引列、索引大小写风格（`unique key` / `key` 小写），未做任何
   「优化 / 改名 / 合并 / 补列 / 补索引」。

**上游原文没有**外键（`FOREIGN KEY`）与任何 seed 语句，`im-platform.sql` 里也没有
`IF NOT EXISTS` / `DROP TABLE`。以上均按原样保留。
