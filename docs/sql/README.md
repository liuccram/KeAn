# `docs/sql/` —— 历史存档，**不是**数据库事实源

> ⚠️ **先读这一句：本目录的 SQL 只作历史追溯用，不要拿去建库、不要拿它对比线上结构。**
>
> 唯一事实源是 **`kean/src/main/resources/db/migration/`** —— 由 Flyway 在应用启动时
> 自动执行，版本记录在 `flyway_schema_history` 表里。

---

## 1. 为什么这个目录不再更新（以及为什么不去补齐它）

这个目录是**早期手工同步的副本**，同步某天就停了。实测现状：

| | 本目录 | 唯一事实源 `kean/src/main/resources/db/migration/` |
|---|---|---|
| 文件数 | **24**（`V1`..`V24` 共 23 份，**缺 `V8`**，另加 `im-platform.sql`） | **35**（`V1`..`V35`，无缺口） |
| 最新版本 | `V24__split_publish_apply_rating.sql` | `V35__chat_status_to_box_semantics.sql` |
| `V1` 内容 | 多出 `CREATE DATABASE IF NOT EXISTS KeBang` + `USE KeBang`（见下） | 只有 `CREATE TABLE ...`，由 Flyway 在已选定的库里执行 |
| 谁在维护 | 无人（同步已停止） | 每次改库都必须新增一个更高版本号的文件 |

**结论：不在这里补 `V25`..`V35`。** 理由很直接 —— 手工同步的副本**已经**漂移了 11 个版本，
再抄一次只是把「过期」延后，下一个迁移一提交它又立刻过期；而 Flyway 目录**天然不会过期**
（迁移不落地，应用就起不来）。所以这里改成**只声明边界**，把「第二事实源」这个坑拆掉，
而不是继续维护一份注定再次过期的副本。这与 `README.md`（"`docs/sql/` 下是早期的建表脚本存档，
仅供追溯"）和 `docs/engineering-plan.md` 的 3.5 项（"删除 `docs/sql/`，以 migration 为唯一事实源"）
同一方向。

## 2. 本目录里有什么

| 文件 | 说明 |
|---|---|
| `V1__init_base_tables.sql` | ⚠️ **危险文件**：它开头是 `CREATE DATABASE IF NOT EXISTS KeBang ... ; USE KeBang;`，这是 **Flyway 之前的旧手工建库脚本**，与迁移**同名同号但内容不同**。照它执行会建出一个名叫 **`KeBang`** 的库（生产库叫 `kean`），而且它不含后续 34 个迁移的任何改动。**只当历史看。** |
| `V2__seed_courses.sql` .. `V24__split_publish_apply_rating.sql` | 早期手工同步的迁移副本，停在 `V24`；`V8__user_email.sql` 从缺。 |
| `im-platform.sql` | **不属于 kean 库**，见第 3 节。 |

> 关于「当前结构长什么样」：直接看 `kean/src/main/resources/db/migration/` 按版本号顺序读，
> 或连上数据库 `SHOW CREATE TABLE <表名>`。**不要**用本目录的 `V1` 去理解现状。

## 3. `im-platform.sql` 属于**独立库 `im_platform`**（box-im）

这一份是 box-im（im-server / im-platform）的**官方建表 SQL**，9 张 `im_*` 表，
**逐字照抄上游，只补了环境适配**（库名声明、collation）。它和 kean 的迁移**不是一回事**：

| | kean 业务库 | `im_platform` 库 |
|---|---|---|
| 管纳方式 | **Flyway** 版本化迁移（`kean/src/main/resources/db/migration/`） | **一次性 SQL**（`im-platform.sql`），手工执行 |
| 版本记录 | `flyway_schema_history` | 无（上游没有这个概念） |
| 表 | `school` / `substitute_task` / `chat_message` … | `im_user` / `im_friend` / `im_private_message` / `im_group` / `im_group_member` / `im_group_message` / `im_sensitive_word` / `im_file_info` / `im_message_deletion` |
| 回滚 | 回滚应用代码（列可空） | `DROP DATABASE im_platform` 重建 |

**为什么必须分库**（别把两者混在一个 schema 里）：

1. `im-platform.sql` 全是 `CREATE TABLE`、**没有 `IF NOT EXISTS`**，第二次执行直接报「表已存在」；
2. Flyway 的 `clean` / `validate` 是**按 schema 操作**的 —— 混在一起时 `clean` 会把 box 的表一起清掉，
   而 box 的表又不在 Flyway 版本序列里，`validate` 也没法过；
3. 反过来，box 侧重建 IM 时最省事的做法就是整库重建，分库才能做到「重建 IM 不影响业务库」。

落库语句、执行命令与核对方法见 [`../ops/im-migration.md`](../ops/im-migration.md) §2；
上游版本对应关系（blob SHA、commit）见该文档 §4。

## 4. 以后想清理怎么办（未执行，留给决策）

`docs/engineering-plan.md` 的 3.5 项建议**删除本目录**、`README.md` 改指 migration 目录。
真要删之前必须先 `grep` 全仓引用确认没有别处依赖（当前已知引用点：
`README.md:281`、`docs/engineering-plan.md` 第 1.3④/3.5/风险表、`docs/ops/im-migration.md` 多处
指向 `docs/sql/im-platform.sql` —— **`im-platform.sql` 不随本目录一起删**，它是 box 侧的一次性脚本，
需要单独找地方放）。
