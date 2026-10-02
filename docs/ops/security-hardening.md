# 安全加固清单

覆盖四件事：**口令轮换 / 数据库最小权限 / 端口收敛 / 备份加密 / 传输 TLS**。

> 本文件是**可执行的运维清单**，**不含任何口令明文**，需要填值的地方一律用 `<...>` 占位。
> 每项都给出「做什么 → 怎么验证」。现状依据为代码与配置走查。

## 0. 现状结论

| 项 | 现状 |
|---|---|
| 口令存储 | 密码用 **BCrypt 哈希** ✓；其余敏感字段**全部明文** ✗（email、phone、登录 IP、私信正文…） |
| 接口脱敏 | 进行中（他人邮箱/手机号不再返回） |
| 数据库账号权限 | **待确认** ✗（应用账号权限范围未收敛） |
| 依赖端口 | MySQL `13306` / Redis `26739` / RustFS `19000`；其中 **19000 曾确认可从公网访问** ✗ |
| 备份 | 策略已在 `docs/ops/rustfs.md` 说明，但**未加密、未自动化、未演练恢复** ✗ |
| 传输 | 小程序强制 HTTPS（必须做）；MySQL / Redis 连接是否启用 TLS **待确认** ✗ |

---

## 1. 口令轮换（最高优先）

### 为什么必须做

`.env.prod` 的内容曾在一次排查中被终端打印出来（我的失误），以下变量应视为**已泄露**：

`MYSQL_PASSWORD`、`REDIS_PASSWORD`、`JWT_SECRET`、`STORAGE_SECRET_KEY`、`ADMIN_PASSWORD`、`MAIL_PASSWORD`

### 轮换清单

| 变量 | 用途 | 轮换方式 | 影响 |
|---|---|---|---|
| `MAIL_PASSWORD` | 发邮箱验证码 | QQ 邮箱后台重新生成授权码 → 更新 `.env.prod` → 重启 | 重启前验证码发不出 |
| `STORAGE_SECRET_KEY` | 对象存储签名 | RustFS 控制台新建密钥 → 更新 env → 重启 | 旧预签名 URL 失效（本项目由后端签名，影响小） |
| `ADMIN_PASSWORD` | 管理员初始口令 | 管理端「修改密码」；忘了则改 env 后重启（首登强制改密） | 无 |
| `REDIS_PASSWORD` | 应用连 Redis | `redis-cli CONFIG SET requirepass '<新口令>'` → `CONFIG REWRITE` → 更新 env → 重启 | 短暂不可用 |
| `MYSQL_PASSWORD` | 应用连库 | `ALTER USER '<user>'@'%' IDENTIFIED BY '<新口令>';` → 更新 env → 重启 | 短暂不可用 |
| `JWT_SECRET` | 签发登录态 | 换成新的随机串（≥ 32 字节）→ 更新 env → 重启 | ⚠️ **所有用户被强制登出**，需提前公告 |

### 建议顺序

1. 先换**影响面小**的：`MAIL_PASSWORD` → `STORAGE_SECRET_KEY` → `ADMIN_PASSWORD`
2. 再换需要重启的：`REDIS_PASSWORD` → `MYSQL_PASSWORD`
3. 最后换 `JWT_SECRET`（全员登出），选低峰时段

### 验证

- 后端能正常启动，`GET /health/ready` 返回 200
- 旧口令连库失败：`mysql -h <host> -u <user> -p<旧口令>` → `Access denied`
- 客户端登录一次并打开「我的」页，确认资料能刷新（JWT 正常）

---

## 2. 数据库最小权限

### 先查现状

```sql
SHOW GRANTS FOR CURRENT_USER();
```

### 目标（简单版：单一应用账号）

应用跑在业务库 `kean` 上，且 **Flyway 需要 DDL**，所以：

```sql
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '<app_user>'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES
  ON kean.* TO '<app_user>'@'%';
FLUSH PRIVILEGES;
```

**明确不要给**：`GRANT OPTION`、`SUPER`、`FILE`、`PROCESS`、`SHUTDOWN`、`*.*` 级别权限、其它库的权限。

### 更严格的做法（可选）

拆两个账号：应用用**只有 DML** 的账号，迁移用单独的 DDL 账号（`spring.flyway.user` / `spring.flyway.password`）。
代价是多维护一套口令，收益是应用被攻破时也改不了表结构。

### 验证

```sql
SHOW GRANTS FOR '<app_user>'@'%';                  -- 只应看到 kean.* 上的那几个权限
SELECT Super_priv, File_priv FROM mysql.user WHERE user = '<app_user>';   -- 都应为 N
```

再用应用账号试一条越权语句（应失败）：

```sql
CREATE DATABASE probe_db;   -- 期望：Access denied
```

---

## 3. 端口收敛（安全组 + 主机防火墙）

### 目标

| 端口 | 应允许来源 |
|---|---|
| `443` / `80` | 公网 |
| `8080`（后端） | 仅 Nginx / 反代所在主机 |
| `13306`（MySQL） | 仅后端所在主机；你自己的管理 IP（按需临时开） |
| `26739`（Redis） | 仅后端所在主机 |
| `19000`（RustFS S3 API） | 仅后端所在主机（**不要对公网**） |

### 做法

- 云厂商**安全组**先收紧到上表
- 主机**防火墙**再兜一层（`ufw` / `firewalld`）

### 验证（从**另一台**机器执行，均应超时/拒绝）

```bash
nc -vz <服务器IP> 13306
nc -vz <服务器IP> 26739
nc -vz <服务器IP> 19000
```

---

## 4. 备份加密 + 自动化

### 目标

1. 每日全量，保留 N 天
2. **加密后**再落到异地
3. **每季度做一次恢复演练**（这条最重要 —— 没演练过的备份等于没有）

### 示例脚本

```bash
#!/usr/bin/env bash
set -euo pipefail
DAY=$(date +%F)
OUT="/var/backups/kean-${DAY}.sql.gz.enc"

mysqldump --single-transaction --routines --triggers \
  -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" \
  "$MYSQL_DATABASE" | gzip -9 | age -r "$BACKUP_PUBLIC_KEY" -o "$OUT"

rclone copy "$OUT" remote:kean-backups/          # 异地上传
find /var/backups -name 'kean-*.sql.gz.enc' -mtime +7 -delete   # 本地只留 7 天
```

> ⚠️ 解密私钥**不要**放在同一台机器上。可用 `age` 或 `gpg`，二选一。

### 恢复演练（每季度）

```bash
age -d -i /path/to/key.txt kean-<日期>.sql.gz.enc | gunzip | \
  mysql -h <测试库host> -u root -p <测试库名>

# 核对：Flyway 版本、省份/学校/校区/用户/任务 条数
```

---

## 5. 传输 TLS

### 5.1 对外（**必需**）

微信小程序**强制要求 HTTPS**，所以这条不是可选项：

- Nginx 配 `443` + 有效证书（Let's Encrypt 即可），`80` 跳 `443`
- 验证：`curl -I https://<域名>/health` → 200；`curl -I http://<域名>/health` → 301

### 5.2 后端 → MySQL / Redis（建议，可后置）

- **MySQL**：JDBC URL 追加 `useSSL=true&requireSSL=true`（自签证书时再加 `verifyServerCertificate=false`）
- **Redis**：需服务端启用 TLS（`tls-port`、`tls-cert-file`…），改造成本较高
- 判断：依赖都在内网时，这条属于纵深防御，优先级**低于前三项**

---

## 6. 总验证清单

- [ ] 6 个口令全部轮换，旧口令已失效
- [ ] `SHOW GRANTS` 只看到 `kean.*` 上的 DML + DDL
- [ ] 从外网探测 `13306` / `26739` / `19000` 均不可达
- [ ] 备份脚本跑通，产物是**加密**的，且异地上传成功
- [ ] 完成一次恢复演练，数据条数核对一致
- [ ] `https://<域名>/health` 正常，http 自动跳转 https

> 本文档只覆盖**安全加固**。业务合规项（用户协议与隐私政策正文、注册同意勾选、账号注销、内容审核）另行处理。

---

## 7. 敏感文本字段加密（AES-256-GCM）

已对 8 个「只读不查」的文本字段做应用层加密：`substitute_task.reason/requirement/remark`、
`report.description/handle_remark`、`report_appeal.content/handle_remark`、`review.content`。

### 启用步骤
1. **确认 V31 迁移已应用**（Flyway 随启动自动执行；它把这些列从 VARCHAR(500) 加宽到 2048 ——
   Base64 密文最坏 2043 字符，不加宽会报 1406 或被静默截断，后者会导致密文不可逆损坏）
2. 生成密钥：`openssl rand -base64 32`
3. 写入 `.env.prod` 的 `DATA_ENC_KEY`，重启后端
4. 验证：发一条代课任务后 `SELECT reason FROM substitute_task ORDER BY id DESC LIMIT 1;`
   应看到 `v1:...`；而 `GET /api/tasks/{id}` 必须返回**明文**（证明读取解密生效）

### 行为与边界
- **未配置密钥 = 不加密**：明文透传 + 启动 WARN，不阻断启动；密钥非法同样透传 + WARN
- **存量明文无需迁移**：解密时凡是不以 `v1:` 开头的一律原样返回，旧记录会在下次写入时自动变密文
- **不可在这些字段上检索/排序**：加密后按内容查询会失效。实体字段上已留注释；新增功能时注意
- **密钥丢失 = 这些字段永久不可读** ⚠️ 密钥必须进备份；轮换时用新的版本前缀分批重加密
- 尚未加密：`chat_message.content`（图片消息的 content 是对象键，被聊天图片鉴权按等值查询使用，
  需按「只加密文字消息、图片键保持明文」的方案单独做）与 `chat_session.last_content`（正文预览，
  会泄露消息内容，需与前者一并处理）
