# 课安

校园临时代课互助平台。管理端已按架构图落地：用户、代课、举报、学校目录、公告、统计、系统配置。

## 部署关系

- **后端**：跑在主机，`http://127.0.0.1:8080`（对手机暴露主机 WLAN 地址，由 `.env` 配置）
- **用户端 / 管理端**：在主机浏览器访问（H5 `localhost:5173`，管理端 `localhost:5174`）
- **MySQL / Redis**：部署在内网虚拟机，地址由 `.env` 的 `MYSQL_HOST` / `REDIS_HOST` 提供

## 环境

后端三个 Spring Profile：`dev` / `test` / `prod`，对应 `kean/src/main/resources/application-{dev,test,prod}.yml`。

把 `.env.example` 复制为 `.env.dev`、`.env.test`、`.env.prod` 后自己填写。启动时先读 `.env`，再被 `.env.{profile}` 覆盖。

前端 Vite 模式：`development` / `test` / `production`，对应各端的 `.env.development`、`.env.test`、`.env.production`，填写 `VITE_API_BASE_URL`。

### 数据库归属

两套库用途不同，别混：

| 库 | 位置 | 用途 |
|---|---|---|
| `kean` | 生产服务器 | 生产数据 |
| `KeBang` | 内网开发机的 MySQL | 本地开发 |

生产服务器上只有 `kean` 一个业务库；`KeBang` 只存在于开发机（那台机器上另有几个与本项目无关的库）。**`KeBang` 里不是生产数据。**

一个容易忽略的点：服务器 MySQL 的 `lower_case_table_names=1`，库名实际以小写 `kean` 存储，因此 `.env.prod` 里写 `Kean` 也能连上。若将来迁到该配置为 `0` 的实例（Linux 默认值），`Kean` 与 `kean` 会被当成两个库 —— 建议把 `MYSQL_DATABASE` 统一写成小写 `kean`。

## 启动

后端（默认开发环境）：

```powershell
cd kean
.\run.ps1
```

测试 / 生产：`.\run.ps1 -Profile test` 或 `.\run.ps1 -Profile prod`。

用户端 H5：

```bash
cd uni-kean
npm install
npm run dev:h5
```

浏览器打开 `http://localhost:5173/`。H5 开发代理到本机 `http://127.0.0.1:8080`。测试包：`npm run build:h5:test`，生产包：`npm run build:h5`。

管理端：

```bash
cd web-kean
npm install
npm run dev
```

浏览器打开 `http://localhost:5174/login`。测试包：`npm run build:test`，生产包：`npm run build`。请设置不少于 12 位的 `ADMIN_PASSWORD`（禁止默认口令）。若库中管理员仍是旧默认口令，首次登录会强制改密。

接口说明：`docs/api/auth.md`、`docs/api/dict.md`、`docs/api/tasks.md`、`docs/api/applications.md`、`docs/api/notifications.md`  
管理端规划：`docs/admin/plan.md`  
管理端接口（待实现）：`docs/api/admin.md`  
建表 SQL：`docs/sql/V1__init_base_tables.sql`  
课程种子：`docs/sql/V2__seed_courses.sql`  
山东高校种子：`docs/sql/V4__seed_shandong_schools.sql`  
用户学校修改次数：`docs/sql/V5__user_school_change_count.sql`
