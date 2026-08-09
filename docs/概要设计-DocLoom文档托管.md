# DocLoom 文档托管功能 — 概要设计

> 版本：v0.2　|　日期：2026-08-09　|　状态：草案（已纳入公开访问 / ES / 无版本三项决定）

---

## 1. 引言

### 1.1 背景
DocLoom 基于 RuoYi-Vue-Plus 5.X（Spring Boot 3 + Vue3）构建，已部署于 k3s（命名空间 `docloom`，访问 `192.168.149.128:30080`）。现需新增"文档托管"能力：在本站配置任意 GitHub 仓库，拉取其指定目录下的文档（md / doc / docx 等），在站内渲染并支持关键字命中检索，并对外提供**公开匿名访问**的阅读门户。

### 1.2 目标
1. 管理端可配置 GitHub 仓库来源（owner/repo、分支、子路径、可选 PAT）。
2. 按配置同步仓库指定路径下的文档到本地，支持 `md / docx / doc / pdf / txt` 等格式解析与渲染。
3. 登录管理员可管理来源/触发同步；**任何匿名访客**可浏览文档树、在线查看渲染、关键字检索命中高亮。
4. 检索基于 **Elasticsearch**（IK 中文分词），支持中文关键字。

### 1.3 范围与非目标
- **范围内**：来源 CRUD、同步、渲染、ES 检索、公开匿名阅读门户、权限接入。
- **非目标**：在线编辑回写 GitHub、版本差异对比、**文档历史版本**（已确认不做，`doc_file` 仅保留最新一份）、独立多租户门户。
- **已确认决定**：① 允许公开匿名访问；② 引入 Elasticsearch；③ 不做历史版本。

### 1.4 术语
| 术语 | 含义 |
|---|---|
| 文档来源(doc_source) | 一条 GitHub 仓库 + 路径配置，对应"一个仓库的 doc" |
| 同步(sync) | 拉取仓库路径下文件、解析正文、落库 + 写 ES 索引 |
| 管理面 | 登录后访问的来源管理/同步控制 |
| 公开阅读面 | 匿名访问的浏览/检索/渲染 |

---

## 2. 总体设计

### 2.1 架构总览（双访问面）
```
┌─── 公开阅读面(匿名) ───────────────┐  ┌── 管理面(登录) ──────────────┐
│  /p/doc  文档树·渲染·检索            │  │  /doc  来源管理·同步·状态      │
└──────────────┬──────────────────────┘  └──────────────┬────────────────┘
               │ /prod-api/doc/public/** (security.excludes 免鉴权)  │ /prod-api/doc/** (Sa-Token)
┌──────────────┴──────────────────────────────────────────┴────────────────┐
│                        ruoyi-doc (新增后端模块)                              │
│  AdminController(Source/Sync)        PublicController(File/Search/View)     │
│       │                                      │                              │
│   Service ── GitHubSyncService ─ DocParser ─ SearchService(ES)              │
│       │            │                 │              │                       │
│       └ MyBatis-Plus ── MySQL(doc_*)  │   └─ Tika/flexmark ─┘   └─ ES Client │
│       │  (数据源/真源)                  (raw_text + html)        (检索索引)    │
│   └─ ruoyi-common-encrypt(PAT) / oss(大文件) / redis(缓存) / job(定时) ─┘    │
└─────────────────────────────────┬──────────────────────────────────────────┘
                                  ▼
                  GitHub REST Trees/Contents API + raw.githubusercontent.com
                  Elasticsearch 8 (新加入 /opt/middleware compose)
```

### 2.2 技术选型
| 关注点 | 选型 | 理由 |
|---|---|---|
| GitHub API 调用 | Hutool `HttpUtil`（已内建） | 免引新 HTTP 客户端；支持 ETag 条件请求 |
| 文件枚举 | `GET /repos/{o}/{r}/git/trees/{sha}?recursive=1` | 一次拿到整棵树，省 rate limit |
| 文件下载 | `raw.githubusercontent.com/{o}/{r}/{branch}/{path}` | 直链、CDN、较 git 协议稳 |
| Office/PDF 解析 | Apache Tika | 单依赖覆盖 doc/docx/pdf/html，输出 text+html |
| Markdown 解析 | flexmark-java(后端抽正文) + 前端 markdown-it(渲染) | md 前端渲染保代码高亮/Mermaid |
| **检索** | **Elasticsearch 8 + IK 分词** | 中文友好、高亮/分页原生支持；MySQL 退为数据源 |
| ES 客户端 | `co.elastic.clients:elasticsearch-java` 8.x | Spring Boot 3 兼容 |
| PAT 存储 | `ruoyi-common-encrypt` `@EncryptField` | 复用现成加密 |
| 大文件/原档 | `ruoyi-common-oss`（MinIO） | DB 存正文+HTML，原档入 OSS |
| 定时同步 | ruoyi-job / SnailJob | 复用框架任务调度 |
| 缓存 | Redis（`ruoyi-common-redis`） | 缓存 GitHub tree、ETag、渲染 HTML |
| 公开接口鉴权豁免 | `security.excludes` 注入 `/doc/public/**` | RuoYi-Plus 原生 anon 白名单 |

### 2.3 模块划分
- **后端**：新增 `ruoyi-modules/ruoyi-doc`，包 `org.dromara.doc`，分层 controller（分 admin/public 两组）/domain(bo/vo)/mapper/service(impl)；在 `ruoyi-admin` 与根 `pom` 注册；依赖 `elasticsearch-java`、`tika-core`+`tika-parsers`、`flexmark-all`。
- **前端**：新增 `src/api/doc/*`、`src/views/doc/*`（admin）与公开阅读页 `src/views/doc-public/*`；管理菜单走 `sys_menu`（DB 驱动）；公开页用静态路由（不进登录墙）。

---

## 3. 功能设计

### 3.1 用例
| 角色 | 用例 |
|---|---|
| 管理员（登录） | 来源 CRUD、连通性测试、手动同步、查看同步状态、控制是否公开 |
| 匿名访客 | 浏览公开来源文档树、在线查看渲染、关键字检索命中高亮 |

### 3.2 功能模块
1. **来源管理**（管理面）：CRUD + 连通性测试（校验 owner/repo/branch/path 可达 + 预览文件数）+ `public_visible` 开关。
2. **同步引擎**：枚举→下载→解析→upsert MySQL→写 ES→更新状态；手动/定时；幂等。
3. **公开浏览**（阅读面）：仅暴露 `public_visible=1` 的来源；目录树 + 渲染。
4. **检索**（阅读面）：ES `multi_match` + IK + 高亮 + 分页。
5. **权限**：管理面 Sa-Token + RBAC；公开面免鉴权但做限流防滥用。

---

## 4. 数据设计

### 4.1 ER
```
doc_source 1 ──── * doc_file          MySQL(doc_*) = 数据源/真源
                     │
                     └──→ Elasticsearch 索引 docloom_doc (检索面, 可由 MySQL 重建)
```

### 4.2 表结构（MySQL，单租户可带 tenant_id 预留）

**doc_source（文档来源）**
| 列 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| name | varchar(128) | 来源名称 |
| owner | varchar(64) | GitHub owner |
| repo | varchar(64) | 仓库名 |
| branch | varchar(64) | 分支 |
| path | varchar(512) | 仓库内子路径，空=根 |
| github_token | varchar(512) | 来源级 PAT，`@EncryptField`；空则用全局兜底 |
| file_exts | varchar(256) | 允许扩展名，逗号分隔，默认 `md,docx,doc,pdf,txt` |
| max_file_size | int | 单文件上限(KB)，默认 10240 |
| public_visible | char(1) | 1=对外公开（匿名可读），0=仅管理面可见 |
| sync_mode | char(1) | 0=手动 1=定时 |
| sync_cron | varchar(64) | 定时表达式 |
| last_sync_status | char(1) | 0=失败 1=成功 2=进行中 |
| last_sync_time | datetime | 最近成功同步时间 |
| file_count | int | 已同步文件数 |
| tenant_id + 审计列 | | RuoYi 标准 |

**doc_file（同步文档，无历史版本——每 (source_id,path) 仅一行）**
| 列 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| source_id | bigint FK | 来源 |
| path | varchar(1024) | 仓库内相对路径 |
| name | varchar(255) | 文件名 |
| ext | varchar(16) | 扩展名 |
| sha | varchar(64) | GitHub blob sha，变更判定 |
| size | bigint | 字节 |
| raw_text | MEDIUMTEXT | 抽取正文（ES 索引重建源 + LIKE 兜底源） |
| render_html | LONGTEXT / OSS ref | Office/PDF 渲染 HTML；md 不存 |
| raw_md | MEDIUMTEXT | md 原文（前端 markdown-it 渲染） |
| content_updated_at | datetime | 仓库内文件修改时间 |
| sync_time | datetime | 本地同步时间 |
| tenant_id + 审计列 | | |
| 索引 | | `uk(source_id,path)` 唯一；`idx(source_id)` |

### 4.3 Elasticsearch 索引 `docloom_doc`
| 字段 | 类型 | 分析器 |
|---|---|---|
| docFileId | long(keyword) | — |
| sourceId | long | keyword |
| sourceName | keyword | — |
| path | keyword | — |
| name | text | ik_max_word 索引 / ik_smart 查询 |
| ext | keyword | — |
| rawText | text | ik_max_word 索引 / ik_smart 查询 |
| contentUpdatedAt | date | — |
| syncTime | date | — |

> ES 仅为检索面，可随时由 MySQL `raw_text` 全量重建；同步时双写，删除时同步删 ES。

---

## 5. 接口设计

> 管理面 `R<T>`/`TableDataInfo` + `@SaCheckPermission`；公开面免鉴权（`security.excludes` 含 `/doc/public/**`）。

### 5.1 来源管理 `/doc/source`（管理面·登录）
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/list` | `doc:source:list` | 分页查询 |
| GET | `/{id}` | `doc:source:query` | 详情 |
| POST | `/` | `doc:source:add` | 新增 |
| PUT | `/` | `doc:source:edit` | 编辑 |
| DELETE | `/{ids}` | `doc:source:remove` | 删除（级联清 doc_file + ES） |
| POST | `/test` | `doc:source:query` | 连通性校验，返回可达+文件数预览 |

### 5.2 同步 `/doc/sync`（管理面·登录）
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/{sourceId}` | `doc:source:sync` | 触发一次同步 |
| GET | `/status/{sourceId}` | `doc:source:query` | 查同步状态/进度 |

### 5.3 公开阅读 `/doc/public`（公开面·匿名）
| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/sources` | 匿名 | 列出 `public_visible=1` 的来源(id/name) |
| GET | `/tree/{sourceId}` | 匿名 | 该来源文档目录树 |
| GET | `/view/{id}` | 匿名 | 渲染体：md→`{type:'md',raw}`；office/pdf→`{type:'html',html}` |
| GET | `/search`?kw=&sourceId=&page= | 匿名 | ES 检索，返回命中列表+高亮片段+分页 |

---

## 6. 关键流程

### 6.1 同步流程
```
触发(手动/定时)
  ▼
1. GET /repos/{o}/{r}/git/trees/{branch}?recursive=1   (带 ETag 缓存)
     失败→按 contents API 逐级列目录兜底
  ▼
2. 过滤：path 前缀 + 扩展名白名单 + size 上限
  ▼
3. 候选文件比对本地 sha：未变跳过；变/新增→ GET raw 下载
  ▼
4. 解析：
     md   → flexmark 抽正文 raw_text；raw_md 原样存
     其它 → Tika toPlainText(raw_text) + toHtml(render_html)
  ▼
5. upsert doc_file（uk(source_id,path)）；写/更新 ES doc
  ▼
6. 远端已删除文件→删 doc_file + 删 ES（按 sha 集合差集）
  ▼
7. 更新 source.file_count / last_sync_status / last_sync_time
```

### 6.2 浏览渲染流程（公开面）
```
匿名访客 → /doc/public/sources 选来源
  → /doc/public/tree/{sourceId} 构建目录树
  → 点文件 /doc/public/view/{id}
       md     → 返回 raw_md → markdown-it + highlight.js + Mermaid + DOMPurify
       office → 返回 render_html → DOMPurify 后 v-html
```

### 6.3 检索流程（公开面·ES）
```
kw 输入
  ▼ ES multi_match([rawText^2, name], analyzer=ik_smart, highlight <mark>)
  ▼ ES 短词/空命中兜底 → MySQL LIKE '%kw%'（按 raw_text）
  → 返回 命中列表(name/path/片段/sourceName) + 分页
```

---

## 7. 前端设计

### 7.1 页面
- 管理（`src/views/doc/`，登录墙内）：`source/index.vue`（来源 CRUD + 测试 + 同步 + 状态）、`sync-log`（可选）。
- 公开阅读（`src/views/doc-public/`，静态路由免登录）：`reader/index.vue`（左侧文档树 + 右侧 DocViewer + 顶栏检索 + 检索结果抽屉）。

### 7.2 组件
- 复用 `Pagination`、`RightToolbar` 等现成组件。
- 新增 `DocViewer.vue`（按 ext 分发 md / html，DOMPurify 清洗）。
- Markdown：`markdown-it` + `highlight.js` + `mermaid`（按需） + `dompurify`。

### 7.3 路由与菜单
- 管理页：走 `sys_menu`（一级"文档托管" → 来源管理），权限 `doc:source:*`。
- 公开页：plus-ui `router/index.ts` 的 `constantRoutes` 增加静态路由 `/p/doc`（`hidden:false`，免登录），不走动态菜单。

---

## 8. 非功能性设计

| 维度 | 措施 |
|---|---|
| GitHub 限流 | 全局 PAT 兜底(来源级可覆盖)，60→5000 req/h；tree 一次枚举；ETag 条件请求；Redis 缓存 |
| GitHub 访问不稳（已知坑） | 同步幂等+重试+best-effort；失败不影响已有数据展示；优先 raw CDN |
| 大文件 | 扩展名白名单 + `max_file_size`；超大 HTML/原档入 OSS，DB 存 ref |
| 公开面安全 | 渲染 HTML 前端 DOMPurify 防 XSS；公开检索/接口加限流(`ruoyi-common-ratelimiter`)防滥用；公开仅暴露 `public_visible=1` |
| 多租户 | 表带 `tenant_id` 预留 |
| ES 可用性 | ES 单节点随中间件栈部署；索引可由 MySQL 全量重建；ES 故障时检索降级 MySQL LIKE |
| 性能 | tree/HTML 缓存；ES 检索带分页 |

---

## 9. 部署与配置

- **k3s**：复用 `docloom` 命名空间与流水线（GH Actions self-hosted runner → docker build → push `localhost:5000` → `kubectl apply -k`）。新模块随 backend 镜像构建，无需新工作负载。
- **新增中间件 — Elasticsearch**：加入 `/opt/middleware/docker-compose.yml`（与 mysql/redis 同栈），单节点、`discovery.type=single-node`，密码入 `/opt/middleware/.env`，数据卷 `esdata`；安装 **IK 分词插件**（与 ES 版本匹配，构建时 `elasticsearch-plugin install`）。访问 `192.168.149.128:9200`。
- **后端配置**（`application.yml`）：
  ```yaml
  docloom:
    github:
      default-token: ${GITHUB_TOKEN:}      # 全局兜底 PAT
      connect-timeout: 10s
      read-timeout: 30s
      max-retries: 3
    parser:
      tika-max-chars: 1000000
  spring:
    elasticsearch:
      uris: http://192.168.149.128:9200
      username: ${ES_USER:elastic}
      password: ${ES_PASSWORD:mrlu}
  security:
    excludes:
      - /doc/public/**        # ← 公开阅读面免鉴权
  ```
- **SQL**：建表 `doc_source`/`doc_file`（含 `tenant_id`/审计列）+ `sys_menu`/`sys_role_menu` 初始化，置 `script/sql/`。
- **MinIO**：v1 正文+HTML 落 DB 即可；启用 MinIO 存原档为增强项（当前未配，留默认）。

---

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| 国内节点 GitHub 间歇不稳 | 同步幂等+重试+best-effort；展示不依赖实时拉取；优先 raw CDN |
| GitHub 限流 | 全局 PAT + tree 一次枚举 + ETag + Redis 缓存 |
| docx/pdf 解析耗时长 | 同步走异步任务，不阻塞接口；结果缓存 |
| md 渲染 XSS | DOMPurify 清洗；禁危险内嵌 html |
| ES 运维/分词 | IK 插件随容器构建；索引可由 MySQL 重建；故障降级 LIKE |
| 公开面被滥用 | 检索/接口限流；公开仅显 `public_visible=1` |
| 仓库 force-push 致 sha 错位 | 以 path 为唯一键、sha 判变更，删除走差集 |

---

## 11. 里程碑

| 期 | 内容 |
|---|---|
| M1 骨架 | 新模块脚手架 + `doc_source` CRUD + 连通性校验 + 前端管理页 |
| M2 同步 | tree 枚举 + 下载 + md/docx 解析 + upsert + 手动同步 |
| M3 渲染与公开阅读面 | `DocViewer`(md/office) + 公开路由 `/p/doc` + 匿名浏览接口 |
| M4 ES 检索 | 加 ES 中间件 + IK + 索引 + 双写 + 检索接口 + 前端命中高亮 |
| M5 增强 | 定时同步、OSS 大文件、Mermaid/代码高亮、同步日志、限流加固 |

---

## 12. 已确认决定
1. ✅ 允许公开匿名访问 → 拆管理面/公开阅读面，`security.excludes` 放行 `/doc/public/**`，前端静态路由 `/p/doc`。
2. ✅ 引入 Elasticsearch + IK → 检索面 ES，MySQL 退为数据源，可降级 LIKE。
3. ✅ 不做历史版本 → `doc_file` 每 `(source_id,path)` 单行。
4. PAT：默认"全局环境变量兜底 + 来源级可选覆盖"（如需改请告知）。
