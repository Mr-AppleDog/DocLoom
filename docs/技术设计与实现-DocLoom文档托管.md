# DocLoom 文档托管 — 技术设计与实现

> 版本：v1.0　|　日期：2026-08-10　|　状态：实现期文档（随代码演进）
>
> **定位**：本文讲"实际怎么建的、难点在哪、关键代码是什么"，与同目录《概要设计-DocLoom文档托管.md》(v0.2，讲设计期 what/why) **互补**，不重复。
>
> **范围**：仅 DocLoom **自研部分**——后端 `ruoyi-modules/ruoyi-doc` 模块（包 `org.dromara.doc`）、前端公开阅读面 `src/views/doc-public`、管理页 `src/views/doc/source`、k3s 部署与 CI 流水线、ES 中间件。**不含** RuoYi-Vue-Plus 脚手架本身（Sa-Token / MyBatis-Plus / 多租户 / SnailJob 等框架能力不在本文展开，只在被 DocLoom 复用时点一句）。

---

## 1. 项目定位与全景

### 1.1 是什么

DocLoom 是一个建在 RuoYi-Vue-Plus 5.X 之上的**文档托管与检索站**：在站内配置任意 GitHub 仓库来源，拉取其指定路径下的文档（md / txt / html；docx / pdf 待 M2b Tika），站内渲染并支持中文关键字命中检索，并对外提供**公开匿名访问**的阅读门户。

一句话定位：**把散落在各 GitHub 仓库里的文档，织成一张站内可检索、可匿名阅读的布。**（"DocLoom" = 织机。）

### 1.2 全景图（as-built）

```
┌── 公开阅读面（匿名）──────────────────┐  ┌── 管理面（登录）──────────────┐
│  /p/doc  来源树·渲染·检索·命令面板     │  │  /doc  来源 CRUD·同步·状态     │
│  constantRoutes 静态路由 + whiteList   │  │  sys_menu 动态菜单 + Sa-Token  │
└──────────────┬───────────────────────┘  └──────────────┬─────────────────┘
               │ /prod-api/doc/public/**  security.excludes│ /prod-api/doc/** @SaCheckPermission
┌──────────────┴──────────────────────────────────────────┴─────────────────┐
│                       ruoyi-doc（org.dromara.doc）                            │
│  PublicDocController          DocSourceController / DocSyncController          │
│   (匿名+@RateLimiter)          (Sa-Token+@RepeatSubmit+@Log)                 │
│        │                              │                                       │
│  PublicDocServiceImpl          DocSourceServiceImpl / DocSyncServiceImpl       │
│   (TenantHelper.ignore)         (running 去重 + sha 判变更 + upsert)          │
│        │                              │              │                        │
│        │                       GithubClient      DocParser(md/txt/html)        │
│        │                       (trees+raw)             │                        │
│        └──── IDocSearchService ◀──────────────────────┘ (双写/删/重建/检索)     │
│                  │ ElasticsearchClient (8.18.8, SB 管版本)                     │
│        │   MyBatis-Plus                                                          │
│        └──→ MySQL(doc_source / doc_file，真源)   ←→  ES docloom_doc (检索面)    │
│  DocSyncScheduler(@Scheduled cron, 跨租户逐来源)  DocSyncJobExecutor(SnailJob 待切) │
└─────────────────────────────┬───────────────────────────────────────────────┘
                              ▼  出站（不受 LAN 限制，与 webhook 入站不同）
        GitHub REST Trees API + raw.githubusercontent.com
        Elasticsearch 8.17.7 + IK（/opt/middleware compose，单节点明文 9200）
```

**部署形态**：k3s 单节点 `192.168.149.128`，命名空间 `docloom`，后端 1 pod（Spring Boot，`localhost:5000/docloom-backend:sha-xxx`），前端 1 pod（nginx，NodePort 30080）。访问 `http://192.168.149.128:30080/`。

---

## 2. 设计思想

这是本文的核心。DocLoom 的每一个实现选择都源自下面几条思想，理解了它们，代码就是这些思想的自然落地。

### 2.1 双面分层：一套数据，两种访问边界

同一份 `doc_file` 数据，对**登录管理员**和**匿名访客**呈现两套完全分离的访问边界：

- **管理面** `/doc/**`：Sa-Token + RBAC（`@SaCheckPermission("doc:source:*")`），可 CRUD 来源、触发同步、重建索引。
- **公开阅读面** `/doc/public/**`：注入 `security.excludes` **免鉴权**，匿名可读，但只暴露 `public_visible=1` 的来源，且每个端点加 `@RateLimiter` 防滥用。

边界划在**路径前缀**上，而非在方法里写 `if (匿名)`——这让鉴权策略集中、可审计，且前端可以走独立静态路由 `/p/doc` 不进登录墙。

### 2.2 MySQL 为真源，ES 为可丢弃的检索面

`doc_file.raw_text` 是**唯一真源**；ES 索引 `docloom_doc` 只是检索加速层，可随时从 MySQL 全量重建（`reindexAll`），故障时检索自动降级 MySQL `LIKE`。

这条思想把"数据所有权"和"检索能力"解耦：ES 挂了不影响文档在线阅读（阅读面读 MySQL），也不影响同步落库（同步双写 ES 是 best-effort，失败跳过）。这是面对"单节点 ES 随中间件栈部署、无 HA"这一现实约束的务实答案——宁可降级，不可停服。

### 2.3 文档即快照，不做历史版本

`doc_file` 每 `(source_id, path)` 仅一行，无版本表。每次同步用 sha 判变更，变了就 update，没变就跳过。

代价是失去版本回溯能力——但这是**已确认的非目标**。对"把仓库文档站内化阅读"的核心场景，最新一份就够了，历史版本带来的表膨胀、diff 渲染、存储成本都不值。**用一个明确的"不做"换来了模型的极简。**

### 2.4 同步幂等 + best-effort：应对国内 GitHub 间歇不稳

国内节点访问 github.com 间歇性 TLS 超时、连接重置是 DocLoom 的头号外部约束。同步引擎因此被设计成**幂等且容错**：

- **幂等**：以 `(source_id, path)` 为唯一键，sha 未变跳过，变了 upsert，重复触发不产生脏数据。
- **容错**：单文件下载/解析失败 → `catch` 跳过 + 计入 skipped，不中断整批；ES 双写失败 → 跳过；最后用 `last_sync_msg` 把"成功 N 个、跳过 M 个、不支持格式 X"回写来源状态。

这保证"拉到多少算多少，失败不影响已有数据展示"，也解释了为什么展示从不依赖实时拉取。

### 2.5 sha 判变更 + 差集删：force-push 不错位

GitHub 仓库被 force-push 后，blob sha 会整体错位。DocLoom **以 path 为唯一键、以 sha 判变更**，删除走**差集**：

- 枚举远端全部候选 path → `remotePaths` 集合；
- 查本地该来源全部 path → `local`；
- `local.filter(f -> !remotePaths.contains(f.path))` 即"远端已删、本地需删"的差集，物理删 + 同步删 ES。

不以 sha 为键，所以 force-push 不会把"同一路径、新 sha"误判成两个文件；差集删保证远端删了什么本地就删什么。这是从"仓库可能被改写"这一前提倒推出来的设计。

### 2.6 前端渲染 md，而非后端渲染

Markdown 的渲染放在前端（`markdown-it` + `highlight.js` + `mermaid` + `DOMPurify`），后端 `DocParser` 只做两件事：抽 `raw_text`（喂 ES 索引）+ 原样存 `raw_md`（下发前端）。

为什么不后端 flexmark 渲染成 HTML 再下发？因为**代码高亮、Mermaid 图、后续交互（TOC/scroll-spy/命令面板）都依赖前端能力**，后端渲染会把交互入口堵死。后端只管"能被检索的纯文本"，前端管"好看的渲染体"，职责切在"可检索 vs 可视化"这条线上。

### 2.7 跨租户匿名访问的上下文处理

RuoYi-Plus 是多租户框架，所有查询默认带 `tenant_id` 过滤。但匿名访客**没有租户上下文**——直接查会因 `tenant_id is null` 查不到任何数据。

DocLoom 的解法是：公开面所有查询包在 `TenantHelper.ignore(() -> ...)` 里**跨租户**查，再用 `public_visible=1` 做业务层过滤；而**定时同步**（`DocSyncScheduler`）要写库，则在每个来源的同步前用 `TenantHelper.dynamic(tenantId, () -> ...)` **显式回填该来源的租户**再写，保证 `tenant_id` 正确。

一句话：**读时 ignore（跨租户可见），写时 dynamic（按来源回填）**。这是多租户框架里做"公开匿名面"必须想清楚的事。

### 2.8 best-effort 贯穿 + 优雅降级

ES 在 DocLoom 里**全程不阻断主流程**：

- 启动期 `EsIndexInitializer`（`ApplicationRunner`）建索引失败 → 记日志、不阻断启动，留待首次检索时重试；
- 同步双写 ES 失败 → 跳过，不影响落库；
- 检索时 ES 不可用 → `indexReady=false` → 自动降级 MySQL `LIKE`，连高亮片段都用 `buildLikeFragment` 手工包 `<mark>` 保持一致体验；
- `reindexAll` 全量重建按 500 条一批，单批失败跳过继续。

这种"核心路径不强依赖外部组件"的取舍，让 DocLoom 在 ES 单节点、无 SLA 的部署下依然稳。

---

## 3. 模块结构

### 3.1 后端包结构（`ruoyi-modules/ruoyi-doc`，包 `org.dromara.doc`）

```
org.dromara.doc
├── config
│   ├── DocLoomConfig                 # @EnableConfigurationProperties 汇总
│   ├── DocLoomGithubProperties       # docloom.github.* (default-token/timeout)
│   ├── DocLoomEsProperties           # docloom.es.* (enabled/index-name/analyzers/max-results)
│   └── EsIndexInitializer            # ApplicationRunner，启动幂等建索引
├── client
│   ├── GithubClient                  # Hutool 调 trees/raw API
│   └── GhTreeEntry                   # path/sha/size/ext POJO
├── parser
│   ├── DocParser                     # md/txt/html → ParseResult
│   ├── ParseResult                   # rawText/rawMd/renderHtml
│   └── UnsupportedFormatException    # docx/pdf 等暂不支持，跳过用
├── domain
│   ├── DocSource                     # 来源（@EncryptField token / @Version / @TableLogic）
│   ├── DocFile                       # 文档（无 @Version 无 @TableLogic → 物理删）
│   ├── bo/DocSourceBo
│   └── vo/{DocSourceVo, DocSourceTestVo, DocFileVo, DocFileViewVo, DocSearchHitVo}
├── mapper
│   ├── DocSourceMapper
│   └── DocFileMapper
├── service
│   ├── IDocSourceService / impl      # 来源 CRUD + 连通性校验
│   ├── IDocSyncService / impl        # 同步引擎（同步执行 + @Async）
│   ├── IPublicDocService / impl      # 公开阅读面（跨租户 + public_visible）
│   └── IDocSearchService / impl      # ES 索引/删/重建/检索 + LIKE 降级
├── controller
│   ├── DocSourceController           # 管理面 来源 CRUD/test
│   ├── DocSyncController              # 管理面 同步/reindex
│   └── PublicDocController            # 公开面 sources/tree/view/search
└── job
    ├── DocSyncScheduler              # @Scheduled 回退方案
    └── DocSyncJobExecutor            # SnailJob @JobExecutor（待 Server 上线切回）
```

### 3.2 前端结构（`plus-ui-5.X/src`）

```
src
├── api/doc
│   ├── public.ts                     # 公开面 4 接口 + 类型
│   └── source/{index.ts,types.ts}    # 管理面 CRUD + test + sync
└── views
    ├── doc/source/index.vue          # 管理页（登录墙内）
    └── doc-public
        ├── reader/index.vue          # 公开阅读器主页
        └── components/DocViewer.vue   # 渲染组件
```

路由：`router/index.ts` 的 `constantRoutes` 加静态路由 `/p/doc`（`hidden:true`，免登录）；`permission.ts` 的 `whiteList` 加 `/p/doc` 与 `/p/doc/**`。

---

## 4. 关键实现与代码

### 4.1 GitHub 客户端 `GithubClient`

**设计**：用 Hutool `HttpUtil`（框架内建，免引 HTTP 客户端）。**Trees API 一次递归枚举整棵树**（`recursive=1`，省 rate limit），按 path 前缀 + 扩展名白名单过滤；下载走 `raw.githubusercontent.com` 直链（CDN，比 git 协议稳）。PAT 走"来源级优先、全局兜底"。

**难点**：① 树被 GitHub 截断（`truncated=true`）要回传前端提示；② raw URL 的 path 要 URL 编码但**保留 `/`**、空格转 `%20`；③ 401/403/404 要给可读中文。

**关键代码**：

```java
// 一次枚举整棵树，按 path 前缀 + 扩展名白名单过滤
public List<GhTreeEntry> listFiles(String owner, String repo, String branch, String path,
                                   String fileExts, String token,
                                   java.util.function.Consumer<Boolean> truncatedConsumer) {
    String url = String.format("%s/repos/%s/%s/git/trees/%s?recursive=1", API_BASE, owner, repo, branch);
    JSONObject resp = getJson(url, token);
    boolean truncated = resp.getBool("truncated", false);
    if (truncatedConsumer != null) {
        truncatedConsumer.accept(truncated);
    }
    JSONArray tree = resp.getJSONArray("tree");
    Set<String> exts = parseExts(fileExts);
    String prefix = normalizePath(path);
    List<GhTreeEntry> result = new ArrayList<>();
    if (tree != null) {
        for (Object o : tree) {
            JSONObject item = (JSONObject) o;
            if (!"blob".equals(item.getStr("type"))) continue;        // 只取文件 blob，跳过目录 tree
            String p = item.getStr("path");
            if (!matchPath(p, prefix)) continue;                      // path 前缀过滤
            String ext = FileUtil.extName(p);
            if (ext == null) continue;
            if (!exts.isEmpty() && !exts.contains(ext.toLowerCase())) continue;  // 扩展名白名单
            GhTreeEntry entry = new GhTreeEntry();
            entry.setPath(p);
            entry.setSha(item.getStr("sha"));
            entry.setSize(item.getLong("size"));
            entry.setExt(ext.toLowerCase());
            result.add(entry);
        }
    }
    return result;
}
```

```java
// raw 下载 + URL 编码（保留 /，空格→%20）
public byte[] downloadRaw(String owner, String repo, String branch, String path, String token) {
    String encodedPath = encodePath(path);
    String url = String.format("%s/%s/%s/%s/%s", RAW_BASE, owner, repo, branch, encodedPath);
    HttpRequest req = HttpRequest.get(url)
        .header("Accept", "application/octet-stream")
        .header("User-Agent", USER_AGENT)
        .timeout(timeoutMs());
    String auth = resolveToken(token);
    if (StringUtils.isNotBlank(auth)) {
        req.header("Authorization", "Bearer " + auth);
    }
    HttpResponse res = req.execute();
    int status = res.getStatus();
    if (status == 404) throw new ServiceException("文件不存在：" + path);
    if (status < 200 || status >= 300) throw new ServiceException("下载失败(" + status + ")：" + path);
    return res.bodyBytes();
}

// URL 编码路径段：URLEncoder 会把 / 也编码成 %2F、空格变成 +，都得还原
private String encodePath(String path) {
    if (path == null || path.isEmpty()) return path;
    return URLEncoder.encode(path, StandardCharsets.UTF_8).replace("%2F", "/").replace("+", "%20");
}

// 来源级 token 优先，否则全局兜底
private String resolveToken(String perSource) {
    return StringUtils.isNotBlank(perSource) ? perSource : props.getDefaultToken();
}
```

### 4.2 同步引擎 `DocSyncServiceImpl`（心脏）

**设计**：同步执行 `sync(sourceId)` 走完整流程；`@Async syncAsync` 立即返回后台跑。`ConcurrentHashMap.newKeySet()` 做 per-source 去重防重复触发。

**流程**：枚举→逐条 sha 比对（未变跳过、超 size 跳过）→下载→解析→upsert（按 path selectOne 判 insert/update）→双写 ES→差集物理删→状态回写。

**难点**：① sha 判变更要在**下载前**做（省下载）；② upsert 不能靠 DB 唯一键报错回退（要显式 selectOne 判存在）；③ 差集删后要同步删 ES；④ 整个流程任何异常都要回写 `last_sync_status=0`，否则前端状态卡在"进行中"。

**关键代码**：

```java
// 进行中的来源去重，防止前端连点 / 定时与手动撞车
private final Set<Long> running = ConcurrentHashMap.newKeySet();

@Override
public DocSourceVo sync(Long sourceId) {
    if (running.contains(sourceId)) {
        throw new ServiceException("该来源正在同步中，请稍后再试");
    }
    DocSource source = docSourceMapper.selectById(sourceId);
    if (source == null) throw new ServiceException("文档来源不存在");
    running.add(sourceId);
    try {
        return doSync(source);
    } finally {
        running.remove(sourceId);
    }
}

private DocSourceVo doSync(DocSource source) {
    Date now = new Date();
    Long sourceId = source.getId();
    try {
        updateStatus(sourceId, "2", now, "同步中");   // 2=进行中
        String token = source.getGithubToken();
        String branch = StringUtils.isBlank(source.getBranch())
            ? githubClient.fetchDefaultBranch(source.getOwner(), source.getRepo(), token)
            : source.getBranch();
        List<GhTreeEntry> entries = githubClient.listFiles(
            source.getOwner(), source.getRepo(), branch, source.getPath(),
            source.getFileExts(), token, null);

        long maxBytes = (source.getMaxFileSize() == null ? 10240 : source.getMaxFileSize()) * 1024L;
        Set<String> remotePaths = new HashSet<>();
        int synced = 0, skipped = 0;
        Set<String> unsupportedExts = new TreeSet<>();

        for (GhTreeEntry entry : entries) {
            remotePaths.add(entry.getPath());
            try {
                DocFile existing = docFileMapper.selectOne(Wrappers.<DocFile>lambdaQuery()
                    .select(DocFile::getId, DocFile::getPath, DocFile::getSha)
                    .eq(DocFile::getSourceId, sourceId)
                    .eq(DocFile::getPath, entry.getPath()));
                // sha 未变则跳过下载（在下载前判断，省 rate limit）
                if (existing != null && entry.getSha() != null && entry.getSha().equals(existing.getSha())) {
                    continue;
                }
                byte[] bytes = githubClient.downloadRaw(source.getOwner(), source.getRepo(), branch, entry.getPath(), token);
                if (bytes.length > maxBytes) { skipped++; continue; }   // 超 size 跳过
                ParseResult parse = docParser.parse(entry.getExt(), bytes);
                long size = bytes.length;
                Long docId;
                if (existing != null) {
                    docId = existing.getId();
                    docFileMapper.update(null, Wrappers.<DocFile>lambdaUpdate()
                        .set(DocFile::getSha, entry.getSha())
                        .set(DocFile::getSize, size)
                        .set(DocFile::getRawText, parse.getRawText())
                        .set(DocFile::getRawMd, parse.getRawMd())
                        .set(DocFile::getRenderHtml, parse.getRenderHtml())
                        .set(DocFile::getSyncTime, now)
                        .eq(DocFile::getId, existing.getId()));
                } else {
                    DocFile f = new DocFile();
                    f.setSourceId(sourceId); f.setPath(entry.getPath());
                    f.setName(nameFromPath(entry.getPath())); f.setExt(entry.getExt());
                    f.setSha(entry.getSha()); f.setSize(size);
                    f.setRawText(parse.getRawText()); f.setRawMd(parse.getRawMd());
                    f.setRenderHtml(parse.getRenderHtml()); f.setSyncTime(now);
                    docFileMapper.insert(f);
                    docId = f.getId();
                }
                // 双写 ES（best-effort，失败不影响同步主流程）
                DocFile toIndex = new DocFile();
                toIndex.setId(docId); toIndex.setSourceId(sourceId);
                toIndex.setPath(entry.getPath()); toIndex.setName(nameFromPath(entry.getPath()));
                toIndex.setExt(entry.getExt()); toIndex.setSha(entry.getSha()); toIndex.setSize(size);
                toIndex.setRawText(parse.getRawText()); toIndex.setRawMd(parse.getRawMd());
                toIndex.setRenderHtml(parse.getRenderHtml()); toIndex.setSyncTime(now);
                docSearchService.indexFile(toIndex, source.getName());
                synced++;
            } catch (UnsupportedFormatException ufe) {
                unsupportedExts.add(ufe.getExt());   // docx/pdf 等，跳过但不报错
                skipped++;
            } catch (Exception e) {
                skipped++;   // 单文件失败不中断整批
            }
        }

        // 删除远端已不存在的本地文件（物理删，DocFile 无逻辑删除）
        List<DocFile> local = docFileMapper.selectList(Wrappers.<DocFile>lambdaQuery()
            .select(DocFile::getId, DocFile::getPath)
            .eq(DocFile::getSourceId, sourceId));
        List<Long> toDelete = local.stream()
            .filter(f -> !remotePaths.contains(f.getPath()))   // 差集
            .map(DocFile::getId)
            .toList();
        if (!toDelete.isEmpty()) {
            docFileMapper.deleteByIds(toDelete);
            docSearchService.deleteFiles(toDelete);   // 同步删 ES
        }

        long count = docFileMapper.selectCount(Wrappers.<DocFile>lambdaQuery().eq(DocFile::getSourceId, sourceId));
        StringBuilder msg = new StringBuilder("成功同步 ").append(synced).append(" 个");
        if (skipped > 0) msg.append("，跳过 ").append(skipped).append(" 个");
        if (!unsupportedExts.isEmpty()) msg.append("，不支持格式：").append(String.join(",", unsupportedExts));
        updateStatus(sourceId, "1", now, msg.toString());   // 1=成功
        updateCount(sourceId, (int) count);
        return docSourceMapper.selectVoById(sourceId);
    } catch (Exception e) {
        updateStatus(sourceId, "0", now, "同步失败：" + e.getMessage());   // 0=失败，必须回写
        return docSourceMapper.selectVoById(sourceId);
    }
}
```

### 4.3 文档解析 `DocParser`

**设计**：M2 用**纯 JDK** 解析 md/txt/html，零依赖。md→存 `rawText`+`rawMd`（同值，前端渲染）；txt→`rawText`；html→`renderHtml` 原样 + `stripTags` 抽 `rawText`。docx/doc/pdf 等**抛 `UnsupportedFormatException`**，由同步引擎 catch 后跳过、计入 unsupportedExts——不报错、不中断。

**难点**：UTF-8 BOM 要剥（否则正文首字符是 `﻿`，检索会脏）。

**关键代码**：

```java
public ParseResult parse(String ext, byte[] bytes) {
    String e = ext == null ? "" : ext.toLowerCase().replaceFirst("^\\.", "");
    ParseResult r = new ParseResult();
    if (MD_EXTS.contains(e)) {                  // md/markdown/mdx
        String text = decode(bytes);
        r.setRawText(text);
        r.setRawMd(text);                       // 前端 markdown-it 渲染
    } else if (TXT_EXTS.contains(e)) {          // txt/text/log
        r.setRawText(decode(bytes));
    } else if (HTML_EXTS.contains(e)) {         // htm/html
        String text = decode(bytes);
        r.setRenderHtml(text);
        r.setRawText(stripTags(text));          // 去标签抽正文，喂检索
    } else {
        throw new UnsupportedFormatException(e); // docx/doc/pdf 等 → 同步层 catch 跳过
    }
    return r;
}

private String decode(byte[] bytes) {
    if (bytes == null || bytes.length == 0) return "";
    // 去 UTF-8 BOM，否则首字符 ﻿ 污染检索
    int start = 0;
    if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
        start = 3;
    }
    return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
}
```

> **M2b 待办**：接入 Apache Tika（`tika-core` + `tika-parsers`），对 docx/doc/pdf 调 `toPlainText()` → `rawText`、`toHtml()` → `renderHtml`，把 `UnsupportedFormatException` 这条分支替换掉。

### 4.4 ES 索引与检索 `DocSearchServiceImpl`

**设计**：用 Spring Boot 管理版本的 `ElasticsearchClient`（`spring-boot-starter-data-elasticsearch`，SB 自动配，但**关掉 Spring Data ES 仓储扫描**）。索引**代码驱动建**（`EsIndexInitializer` 启动幂等），字段 `rawText`/`name` 用 IK 双分词器（索引 `ik_max_word` 细粒度、查询 `ik_smart` 智能切）。检索 `multi_match([rawText^2, name])` + 高亮 `<mark>` + 分页；ES 故障降级 MySQL `LIKE`。

**难点**：① `indexReady` AtomicBoolean 做就绪标志，避免每次检索都 ping；② 降级路径要自己造高亮片段（`buildLikeFragment`）保持体验一致；③ `reindexAll` 全量重建要先删旧索引再建，按 500 一批 bulk。

**关键代码**：

```java
private final AtomicBoolean indexReady = new AtomicBoolean(false);

@Override
public void ensureIndex() {
    if (!properties.isEnabled() || indexReady.get()) return;
    try {
        boolean exists = client.indices().exists(e -> e.index(properties.getIndexName())).value();
        if (!exists) createIndex();
        indexReady.set(true);
    } catch (Exception e) {
        log.warn("[DocLoom] ES 索引就绪检查失败（检索将降级 MySQL LIKE）: {}", e.getMessage());
        indexReady.set(false);   // 下次检索再重试
    }
}

// 创建索引 + IK 字段映射
private void createIndex() throws Exception {
    String idx = properties.getIndexName();
    String ia = properties.getIndexAnalyzer();   // ik_max_word
    String sa = properties.getSearchAnalyzer();  // ik_smart
    client.indices().create(c -> c
        .index(idx)
        .mappings(m -> m
            .properties("sourceId", p -> p.long_(b -> b))
            .properties("sourceName", p -> p.keyword(b -> b))
            .properties("path", p -> p.keyword(b -> b))
            .properties("name", p -> p.text(b -> b.analyzer(ia).searchAnalyzer(sa)))
            .properties("ext", p -> p.keyword(b -> b))
            .properties("rawText", p -> p.text(b -> b.analyzer(ia).searchAnalyzer(sa)))
            .properties("contentUpdatedAt", p -> p.date(b -> b))
            .properties("syncTime", p -> p.date(b -> b))
        ));
}

// ES 检索：multi_match + 高亮 <mark> + 分页
private TableDataInfo<DocSearchHitVo> esSearch(String kw, Collection<Long> sourceFilter, int pageNum, int pageSize)
    throws Exception {
    int from = (pageNum - 1) * pageSize;
    String sa = properties.getSearchAnalyzer();
    final boolean hasFilter = sourceFilter != null && !sourceFilter.isEmpty();
    SearchResponse<Map> resp = client.search(s -> s
        .index(properties.getIndexName())
        .from(from).size(pageSize)
        .source(src -> src.filter(f -> f.includes("sourceId", "sourceName", "path", "name", "ext")))
        .query(q -> q.bool(b -> {
            b.must(m -> m.multiMatch(mm -> mm.query(kw).fields("rawText^2", "name").analyzer(sa)));
            if (hasFilter) {   // 按来源过滤（公开检索限定 public_visible=1 的来源集合）
                List<FieldValue> values = new ArrayList<>();
                for (Long sid : sourceFilter) values.add(FieldValue.of(sid));
                b.filter(f -> f.terms(t -> t.field("sourceId").terms(tv -> tv.value(values))));
            }
            return b;
        }))
        .highlight(h -> h
            .fields("rawText", hf -> hf.fragmentSize(150).numberOfFragments(3)
                .preTags(List.of("<mark>")).postTags(List.of("</mark>")))
            .fields("name", hf -> hf.numberOfFragments(0)
                .preTags(List.of("<mark>")).postTags(List.of("</mark>")))
        ), Map.class);
    // ... 组装 DocSearchHitVo
}

// MySQL LIKE 降级（ES 不可用时），TenantHelper.ignore 跨租户
private TableDataInfo<DocSearchHitVo> likeSearch(String kw, Collection<Long> sourceFilter, int pageNum, int pageSize) {
    return TenantHelper.ignore(() -> {
        boolean hasFilter = sourceFilter != null && !sourceFilter.isEmpty();
        long total = docFileMapper.selectCount(Wrappers.<DocFile>lambdaQuery()
            .like(DocFile::getRawText, kw)
            .in(hasFilter, DocFile::getSourceId, sourceFilter));
        int from = (pageNum - 1) * pageSize;
        List<DocFile> files = docFileMapper.selectList(Wrappers.<DocFile>lambdaQuery()
            .select(DocFile::getId, DocFile::getSourceId, DocFile::getPath, DocFile::getName,
                DocFile::getExt, DocFile::getRawText)
            .like(DocFile::getRawText, kw)
            .in(hasFilter, DocFile::getSourceId, sourceFilter)
            .orderByDesc(DocFile::getSyncTime)
            .last("limit " + from + "," + pageSize));
        // ... 组装，fragment 用 buildLikeFragment 手工包 <mark>
    });
}

// LIKE 降级时构造命中片段：取关键字首次出现的前后窗口，mark 高亮
private String buildLikeFragment(String rawText, String kw) {
    if (rawText == null) return null;
    String text = rawText;
    int idx = text.toLowerCase().indexOf(kw.toLowerCase());
    if (idx < 0) {
        text = text.length() > 150 ? text.substring(0, 150) + "…" : text;
    } else {
        int start = Math.max(0, idx - 60);
        int end = Math.min(text.length(), idx + kw.length() + 60);
        text = (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
    }
    return text.replaceAll("(?i)" + Pattern.quote(kw), "<mark>$0</mark>");
}
```

`search()` 入口的三段降级逻辑：

```java
@Override
public TableDataInfo<DocSearchHitVo> search(String kw, Collection<Long> sourceFilter, int pageNum, int pageSize) {
    if (StringUtils.isBlank(kw)) return new TableDataInfo<>(Collections.emptyList(), 0L);
    // ... 参数规整 ...
    if (!properties.isEnabled()) return likeSearch(kw, sourceFilter, pageNum, pageSize);   // 配置关 ES
    try {
        ensureIndex();
        if (!indexReady.get()) return likeSearch(kw, sourceFilter, pageNum, pageSize);    // ES 未就绪
        return esSearch(kw, sourceFilter, pageNum, pageSize);
    } catch (Exception e) {
        log.warn("[DocLoom] ES 检索失败，降级 MySQL LIKE: {}", e.getMessage());
        return likeSearch(kw, sourceFilter, pageNum, pageSize);                            // 运行期故障
    }
}
```

### 4.5 公开阅读面 `PublicDocServiceImpl`

**设计**：所有方法包在 `TenantHelper.ignore` 里跨租户查；`public_visible=1` 是硬过滤；`viewFile` 按 ext 分发 `type=md/html/text/unsupported`；`search` 先把"允许检索的来源集合"算出来（指定 sourceId 时校验其 public，否则查全部 public 来源 id），再交给 `IDocSearchService.search`。

**难点**：公开检索**必须**在 service 层限定来源集合——不能直接把前端传的 `sourceId` 透传给 ES，否则匿名访客可借 `sourceId` 检索到非公开来源的文档。

**关键代码**：

```java
@Override
public List<DocSourceVo> listPublicSources() {
    return TenantHelper.ignore(() -> docSourceMapper.selectVoList(
        Wrappers.<DocSource>lambdaQuery()
            .eq(DocSource::getPublicVisible, "1")
            .orderByDesc(DocSource::getCreateTime)));
}

@Override
public DocFileViewVo viewFile(Long id) {
    return TenantHelper.ignore(() -> {
        DocFile f = docFileMapper.selectById(id);
        if (f == null) throw new ServiceException("文档不存在");
        if (!isPublic(f.getSourceId())) throw new ServiceException("文档不可访问");  // 防越权
        DocFileViewVo v = new DocFileViewVo();
        v.setId(f.getId()); v.setSourceId(f.getSourceId());
        v.setPath(f.getPath()); v.setName(f.getName()); v.setExt(f.getExt());
        String ext = f.getExt() == null ? "" : f.getExt().toLowerCase();
        if ("md".equals(ext) || "markdown".equals(ext)) {
            v.setType("md");
            v.setRaw(f.getRawMd() != null ? f.getRawMd() : f.getRawText());
        } else if ("html".equals(ext) || "htm".equals(ext)) {
            v.setType("html");
            v.setHtml(f.getRenderHtml() != null ? f.getRenderHtml() : f.getRawText());
        } else if ("txt".equals(ext) || "text".equals(ext) || "log".equals(ext)) {
            v.setType("text");
            v.setRaw(f.getRawText());
        } else {
            v.setType("unsupported");
        }
        return v;
    });
}

@Override
public TableDataInfo<DocSearchHitVo> search(String kw, Long sourceId, int pageNum, int pageSize) {
    return TenantHelper.ignore(() -> {
        // 公开检索仅限 public_visible=1 的来源——不能透传前端 sourceId，否则越权
        Set<Long> allowed;
        if (sourceId != null) {
            if (!isPublic(sourceId)) return new TableDataInfo<>(Collections.emptyList(), 0L);
            allowed = Collections.singleton(sourceId);
        } else {
            List<DocSource> pubs = docSourceMapper.selectList(Wrappers.<DocSource>lambdaQuery()
                .select(DocSource::getId).eq(DocSource::getPublicVisible, "1"));
            allowed = pubs.stream().map(DocSource::getId).collect(Collectors.toSet());
        }
        if (allowed.isEmpty()) return new TableDataInfo<>(Collections.emptyList(), 0L);
        return docSearchService.search(kw, allowed, pageNum, pageSize);
    });
}
```

### 4.6 调度与异步 `DocSyncScheduler` / `syncAsync`

**设计**：定时全量同步本欲用 SnailJob（`DocSyncJobExecutor @JobExecutor`），但 Server 镜像受 daocloud 白名单拦截、本机无 docker 无法自建，故**回退 Spring `@Scheduled`**。`@EnableScheduling` 由 `ruoyi-common-job` 的 `SnailJobConfig` 在 `snail-job.enabled=true` 时激活；`DocSyncJobExecutor` 保留待 Server 上线切回。

**难点**：定时任务是**系统级**线程，无租户上下文——直接 `sync` 会因 `tenant_id` 缺失写脏。解法：先 `TenantHelper.ignore` 跨租户枚举所有来源，再对每个来源用 `TenantHelper.dynamic(s.getTenantId(), ...)` **显式回填租户**后同步。失败隔离不影响其他来源。

**关键代码**：

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class DocSyncScheduler {

    private final IDocSyncService docSyncService;
    private final DocSourceMapper docSourceMapper;

    /**
     * 跨租户枚举所有来源，逐来源在其自身租户上下文内同步，失败隔离。
     * cron 可经 docloom.sync.cron 覆盖，默认每小时 :30。
     */
    @Scheduled(cron = "${docloom.sync.cron:0 30 * * * ?}")
    public void syncAll() {
        List<DocSource> sources = TenantHelper.ignore(() -> docSourceMapper.selectList(
            Wrappers.<DocSource>lambdaQuery().select(DocSource::getId, DocSource::getTenantId)));
        log.info("[DocLoom] 定时同步开始，共 {} 个来源", sources.size());
        int ok = 0, fail = 0;
        for (DocSource s : sources) {
            try {
                // 在来源自身租户上下文内同步，保证 DB 写入 tenant_id 正确
                TenantHelper.dynamic(s.getTenantId(), () -> {
                    docSyncService.sync(s.getId());
                });
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("[DocLoom] 来源 {} 同步失败: {}", s.getId(), e.getMessage());
            }
        }
        log.info("[DocLoom] 定时同步完成 成功{} 失败{}", ok, fail);
    }
}
```

手动同步走 `@Async` 立即返回（避 nginx 60s 超时），前端轮询状态：

```java
@Async
@Override
public void syncAsync(Long sourceId) {
    try {
        this.sync(sourceId);   // 复用 running 去重 + 状态回写 + ES 双写
    } catch (Exception e) {
        log.warn("[DocLoom] 异步同步来源 {} 失败: {}", sourceId, e.getMessage());
    }
}
```

### 4.7 控制器双面

**设计**：管理面用 `@SaCheckPermission` + `@RepeatSubmit`（3s 防连点）+ `@Log`；公开面用 `@RateLimiter(limitType=IP)`，四个端点分级限流（sources 20 / tree 60 / view 120 / search 30，每 60s）。

**关键代码**：

```java
// —— 管理面：同步触发（异步立即返回）+ 索引重建 ——
@SaCheckPermission("doc:source:sync")
@RepeatSubmit(interval = 3000)
@Log(title = "文档同步", businessType = BusinessType.OTHER)
@PostMapping("/{sourceId}")
public R<String> sync(@NotNull @PathVariable Long sourceId) {
    docSyncService.syncAsync(sourceId);
    return R.ok("同步任务已提交");
}

@SaCheckPermission("doc:source:sync")
@Log(title = "文档索引重建", businessType = BusinessType.OTHER)
@PostMapping("/reindex")
public R<Integer> reindex() {
    return R.ok(docSearchService.reindexAll());
}

// —— 公开面：匿名 + 分级 IP 限流 ——
@RateLimiter(count = 20, time = 60, limitType = LimitType.IP)
@GetMapping("/sources")
public R<List<DocSourceVo>> sources() { ... }

@RateLimiter(count = 60, time = 60, limitType = LimitType.IP)
@GetMapping("/tree/{sourceId}")
public R<List<DocFileVo>> tree(@NotNull @PathVariable Long sourceId) { ... }

@RateLimiter(count = 120, time = 60, limitType = LimitType.IP)
@GetMapping("/view/{id}")
public R<DocFileViewVo> view(@NotNull @PathVariable Long id) { ... }

@RateLimiter(count = 30, time = 60, limitType = LimitType.IP)
@GetMapping("/search")
public TableDataInfo<DocSearchHitVo> search(@RequestParam(required = false) String kw,
                                            @RequestParam(required = false) Long sourceId,
                                            @RequestParam(defaultValue = "1") int pageNum,
                                            @RequestParam(defaultValue = "10") int pageSize) { ... }
```

> 注意：`/doc/public/search` 返回的是 `TableDataInfo`（`{rows,total,code,msg}`）而非 `R<T>` 包装，前端 `searchPublic` 因此需显式类型转型。

### 4.8 领域模型 `DocSource` / `DocFile`

**设计**：`DocSource` 继承 `TenantEntity`，`githubToken` 用 `@EncryptField` 加密存储、`@Version` 乐观锁、`@TableLogic` 逻辑删；**`DocFile` 故意不加 `@Version`/`@TableLogic`**——每 (source,path) 单行、sha 判变更直接 update、差集**物理删**。

**关键代码**：

```java
// DocSource：来源配置
@Data @EqualsAndHashCode(callSuper = true)
@TableName("doc_source")
public class DocSource extends TenantEntity {
    @TableId(value = "id") private Long id;
    private String name;
    private String owner;
    private String repo;
    private String branch;
    private String path;
    @EncryptField                       // PAT 加密存储，真值经 @EncryptField 加解密
    private String githubToken;
    private String fileExts;
    private Integer maxFileSize;
    private String publicVisible;       // 0否 1是
    private String syncMode;             // 0手动 1定时
    private String syncCron;
    private String lastSyncStatus;      // 0失败 1成功 2进行中
    private Date lastSyncTime;
    private String lastSyncMsg;
    private Integer fileCount;
    private String remark;
    @Version private Long version;       // 乐观锁
    @TableLogic private Long delFlag;     // 逻辑删
}

// DocFile：同步文档——无 @Version 无 @TableLogic → 物理删
@Data @EqualsAndHashCode(callSuper = true)
@TableName("doc_file")
public class DocFile extends TenantEntity {
    @TableId(value = "id") private Long id;
    private Long sourceId;
    private String path;
    private String name;
    private String ext;
    private String sha;                  // GitHub blob sha，变更判定
    private Long size;
    private String rawText;              // 抽取正文（检索源 + LIKE 兜底源）
    private String renderHtml;           // Office/PDF 渲染HTML（md 不存）
    private String rawMd;               // md 原文（前端 markdown-it 渲染）
    private Date contentUpdatedAt;
    private Date syncTime;
    // 注意：无 @Version、无 @TableLogic —— 删除即物理删
}
```

---

## 5. 数据模型与索引

### 5.1 MySQL 表

**`doc_source`**（`script/sql/docloom_doc.sql`，M1）：标准 RuoYi 多租户 + 审计列。`github_token varchar(512)` 存加密 PAT，`version`/`del_flag` 配合 `@Version`/`@TableLogic`。无唯一键——owner+repo 去重靠应用层。菜单 2000–2006（目录 + 来源管理 + 5 个按钮权限）+ role 1 授权。

**`doc_file`**（`script/sql/docloom_doc_m2.sql`，M2）——**唯一键前缀长度是关键坑**：

```sql
CREATE TABLE `doc_file` (
    `id`                bigint     NOT NULL COMMENT '主键',
    `tenant_id`         varchar(20)  NULL DEFAULT '000000' COMMENT '租户编号',
    `source_id`         bigint     NOT NULL COMMENT '文档来源id',
    `path`              varchar(1024) NOT NULL COMMENT '仓库内相对路径',
    `name`              varchar(255) NULL DEFAULT NULL COMMENT '文件名',
    `ext`               varchar(16)  NULL DEFAULT NULL COMMENT '扩展名',
    `sha`               varchar(64)  NULL DEFAULT NULL COMMENT 'GitHub blob sha',
    `size`              bigint     NULL DEFAULT 0 COMMENT '字节',
    `raw_text`          MEDIUMTEXT  NULL DEFAULT NULL COMMENT '抽取正文（检索源）',
    `render_html`       LONGTEXT    NULL DEFAULT NULL COMMENT 'Office/PDF 渲染HTML（md不存）',
    `raw_md`            MEDIUMTEXT  NULL DEFAULT NULL COMMENT 'md原文（前端渲染）',
    `content_updated_at` datetime  NULL DEFAULT NULL COMMENT '仓库内文件修改时间',
    `sync_time`         datetime   NULL DEFAULT NULL COMMENT '本地同步时间',
    -- ... 审计列 ...
    PRIMARY KEY (`id`) USING BTREE,
    -- 唯一键只取 path 前 255 字符：utf8mb4 下 path(1024) 全长会超 InnoDB 3072 字节限制
    UNIQUE KEY `uk_source_path` (`tenant_id`, `source_id`, `path`(255)) USING BTREE,
    KEY `idx_source_id` (`source_id`) USING BTREE
) ENGINE = InnoDB COMMENT = '同步文档';
```

> **坑**：`path` 列定义为 `varchar(1024)`，但唯一键只取前 255 字符。utf8mb4 每字符 4 字节，若取全 1024：`tenant_id(20)*4 + source_id(8) + path(1024)*4 ≈ 4192 字节 > 3072`，建表会报错。截断到 255 是**必须的**——代价是路径超 255 字符的文件无法靠唯一键去重，需应用层兜底（当前 `DocSyncServiceImpl` 用 `selectOne by path` 判存在，不依赖 DB 唯一键报错，所以实际不受影响）。
>
> **`doc_file` 无 `del_flag`**：与 `doc_source` 的 RuoYi 惯例不一致，因为 `DocFile` 不加 `@TableLogic`，删除即物理删——契合"文档即快照、差集删"的设计。

`doc_source` 在 M2 还 `ALTER ADD last_sync_msg varchar(500)`（增量迁移，放 M2 脚本末尾），菜单 2007「触发同步」(`doc:source:sync`)。

### 5.2 Elasticsearch 索引 `docloom_doc`

**代码驱动建索引**（`EsIndexInitializer` 启动幂等 + `DocSearchServiceImpl.createIndex`），不靠 ES 侧预置 mapping：

| 字段 | 类型 | 分析器 |
|---|---|---|
| sourceId | long | — |
| sourceName | keyword | — |
| path | keyword | — |
| name | text | ik_max_word 索引 / ik_smart 查询 |
| ext | keyword | — |
| rawText | text | ik_max_word 索引 / ik_smart 查询 |
| contentUpdatedAt | date | — |
| syncTime | date | — |

> ES 仅为检索面，可由 MySQL `raw_text` 全量重建（`reindexAll` 按 500 一批 bulk）；同步时双写，删除时同步删；ES 故障降级 LIKE。

---

## 6. 前端实现

### 6.1 `DocViewer.vue` —— 渲染管线（前端心脏）

**设计**：按 `view.type` 分派——`md` 走 `markdown-it` + `DOMPurify`、`html` 走 `DOMPurify`、`text` 走 `{{ }}`（Vue 自动转义）、`unsupported` 走空态。markdown-it 的 `highlight` 回调**拦截 ` ```mermaid ` 围栏**输出占位 `<div class="mermaid">`，DOM 更新后 `nextTick` 跑 `mermaid.run` 替换为 SVG。

**难点**：① mermaid 渲染时序——必须在 `nextTick` 后才能拿到 `.mermaid` 元素；`startOnLoad:false` 防自动渲染与手动 `run` 冲突；`securityLevel:'strict'` 自带 SVG 消毒。② mermaid 源码要先 `escapeHtml` 再包进 div，否则源码里的 `<` 会破坏 DOM。③ 高亮 `<mark>` 的放行策略分两处：md/html 渲染用 DOMPurify 默认白名单；搜索命中片段用 `ALLOWED_TAGS:['mark']` 只放行 `mark`。

**关键代码**：

```vue
<template>
  <div class="doc-viewer">
    <div v-if="!view" class="doc-empty">从左侧选一篇文档开始阅读</div>
    <template v-else>
      <div v-if="view.type === 'md'" ref="containerRef" class="markdown-body" v-html="rendered"></div>
      <div v-else-if="view.type === 'html'" class="html-body" v-html="rendered"></div>
      <pre v-else-if="view.type === 'text'" class="text-body">{{ view.raw }}</pre>
      <div v-else class="doc-unsupported">
        <el-empty description="该格式暂不支持在线预览（docx / doc / pdf 等稍后支持）" :image-size="80" />
      </div>
    </template>
  </div>
</template>
```

```ts
mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'strict' });

const md = new MarkdownIt({
  html: false,          // 阻止 markdown-it 原生 HTML 透传，统一走 DOMPurify
  linkify: true,
  breaks: false,
  highlight(str: string, lang: string): string {
    // mermaid 围栏：输出占位 div，渲染后由 mermaid.run 替换为 SVG
    if (lang === 'mermaid') {
      return `<div class="mermaid">${escapeHtml(str)}</div>`;
    }
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(str, { language: lang }).value}</code></pre>`;
      } catch { /* ignore */ }
    }
    return `<pre class="hljs"><code>${escapeHtml(str)}</code></pre>`;
  }
});

// md/html 分支都经 DOMPurify；text 分支用 {{ }} 不需消毒
const rendered = computed<string>(() => {
  const v = props.view;
  if (!v) return '';
  if (v.type === 'md')  return DOMPurify.sanitize(md.render(v.raw || ''));
  if (v.type === 'html') return DOMPurify.sanitize(v.html || '');
  return '';
});

// watch + nextTick：DOM 更新后跑 mermaid.run + 代码块复制按钮 + TOC 锚点上报
watch(rendered, () => {
  nextTick(async () => {
    if (!containerRef.value) return;
    const els = containerRef.value.querySelectorAll<HTMLElement>('.mermaid');
    if (els.length) {
      try { await mermaid.run({ nodes: Array.from(els) }); }
      catch { /* 单图语法错忽略，避免阻塞其他图 */ }
    }
    containerRef.value.querySelectorAll('pre').forEach(attachCopyButton);
    // 标题加锚点 id + 上报 TOC（去重，同名标题递增后缀）
    const heads = containerRef.value.querySelectorAll<HTMLElement>('h1, h2, h3, h4');
    const list: { id: string; level: number; text: string }[] = [];
    const used = new Set<string>();
    heads.forEach((h) => {
      const text = (h.textContent || '').trim();
      let id = text.toLowerCase().replace(/[^\w一-龥]+/g, '-').replace(/^-+|-+$/g, '') || 'section';
      let uid = id; let n = 2;
      while (used.has(uid)) uid = `${id}-${n++}`;
      used.add(uid); h.id = uid;
      list.push({ id: uid, level: Number(h.tagName.slice(1)), text });
    });
    emit('toc', list);
  });
});
```

### 6.2 `reader/index.vue` —— 阅读器主页

**设计**：三栏（来源树 + 文档区 + TOC）。后端返回扁平 `PublicFileVO[]`，前端 `buildTree` 按 `curPath` 拼嵌套树。异步同步提交即返回 + `setTimeout` 递归轮询 `getSource` 状态（3s/次、最多 40 次≈120s），`onUnmounted` 清理。还含 scroll-spy（`IntersectionObserver` rootMargin `-75%`）、暗色切换、拖拽排序持久化、命令面板。

**关键代码**：

```ts
// buildTree：扁平路径 → 嵌套树
function buildTree(files: PublicFileVO[]): TreeNode[] {
  const root: TreeNode[] = [];
  const map = new Map<string, TreeNode>();
  for (const f of files) {
    const parts = (f.path || '').split('/').filter((p) => p.length > 0);
    let curArr = root;
    let curPath = '';
    parts.forEach((seg, i) => {
      curPath = curPath ? `${curPath}/${seg}` : seg;
      const isFile = i === parts.length - 1;
      let node = map.get(curPath);
      if (!node) {
        node = { label: seg, path: curPath, children: isFile ? undefined : [] };
        if (isFile) node.fileId = f.id;
        map.set(curPath, node);
        curArr.push(node);
      }
      if (!isFile && node.children) curArr = node.children;
    });
  }
  return root;
}

// 搜索命中片段 v-html + DOMPurify（仅允许 <mark>）
function safeFrag(f: string): string {
  return f ? DOMPurify.sanitize(f, { ALLOWED_TAGS: ['mark'] }) : '';
}

// 异步同步：提交即返回 + 轮询状态(3s) + onUnmounted 清理
let syncTimer: ReturnType<typeof setTimeout> | null = null;
const handleSync = async (row: DocSourceVO) => {
  syncingId.value = row.id;
  try {
    await syncSource(row.id);   // 立即返回"同步任务已提交"
    proxy?.$modal.msg('同步进行中…');
    await pollSyncStatus(row);  // 轮询直到完成/失败/超时
    await getList();
  } finally {
    syncingId.value = null;
  }
};

const pollSyncStatus = (row: DocSourceVO) =>
  new Promise<void>((resolve) => {
    let n = 0; const max = 40;   // 最多约 120s
    const tick = async () => {
      n++;
      try {
        const res = await getSource(row.id);
        const d = res.data;
        const target = sourceList.value.find((s) => s.id === row.id);
        if (target && d) {
          target.lastSyncStatus = d.lastSyncStatus;
          target.lastSyncMsg = d.lastSyncMsg;
          target.lastSyncTime = d.lastSyncTime;
          target.fileCount = d.fileCount;
        }
        const st = d?.lastSyncStatus;
        if (st !== '2' || n >= max) {   // 离开"进行中(2)"即结束
          if (st === '1') proxy?.$modal.msgSuccess('同步完成：' + (d?.lastSyncMsg || '成功'));
          else if (st === '0') proxy?.$modal.msgError('同步未完成：' + (d?.lastSyncMsg || '失败'));
          else proxy?.$modal.msgWarning('同步仍在进行，请稍后刷新查看');
          resolve(); return;
        }
        syncTimer = setTimeout(tick, 3000);
      } catch { resolve(); }
    };
    syncTimer = setTimeout(tick, 3000);
  });

onUnmounted(() => { if (syncTimer) clearTimeout(syncTimer); });
```

### 6.3 API 与类型 `public.ts` / `source/*`

**设计**：`DocSourceVO` 故意**不含 `githubToken`**——密钥不下发前端，编辑时表单 `githubToken` 留空则后端保留原值。`searchPublic` 因后端返回 `TableDataInfo` 非 `R<T>` 包装，需 `as unknown as Promise<TableResult<...>>` 显式转型。

```ts
export function searchPublic(params: SearchPublicParams): Promise<TableResult<DocSearchHitVO>> {
  return request({
    url: '/doc/public/search', method: 'get', params
  }) as unknown as Promise<TableResult<DocSearchHitVO>>;
}
```

### 6.4 路由与白名单

```ts
// router/index.ts constantRoutes（静态路由，不进登录墙、不走动态菜单）
{
  path: '/p/doc',
  component: () => import('@/views/doc-public/reader/index.vue'),
  hidden: true
},

// permission.ts whiteList（路由守卫白名单）
const whiteList = ['/login', '/register', '/social-callback', '/register*', '/register/*', '/p/doc', '/p/doc/**'];
```

> `/p/doc`（精确）+ `/p/doc/**`（通配子路径）都要加；`hidden:true` 使其不进侧边栏。

---

## 7. 部署与 CI/CD

### 7.1 k3s 部署（`deploy/gitops/`，kustomize base + overlays/staging）

**设计**：base 写逻辑镜像名 `docloom-backend:placeholder`，staging overlay 用 `images` 改写为 `localhost:5000/docloom-backend` + CI 动态 newTag。探针用 **TCP socket** 而非 HTTP actuator——规避 Sa-Token 可能拦截 `/actuator/health` 致 401 反复重启。

**后端 Deployment**（`base/deployment.yaml`，节选）：

```yaml
spec:
  replicas: 1
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }   # 单副本零停机：先起新再停旧
  template:
    spec:
      securityContext:
        seccompProfile: { type: RuntimeDefault }
      containers:
        - name: docloom-backend
          image: docloom-backend:placeholder              # staging overlay 改写
          imagePullPolicy: IfNotPresent
          ports: [{ name: http, containerPort: 8080 }]
          env:
            - { name: SPRING_PROFILES_ACTIVE, value: prod }
          readinessProbe:                                  # TCP 探针，规避 sa-token 拦 actuator
            tcpSocket: { port: 8080 }
            initialDelaySeconds: 30
            periodSeconds: 5
            failureThreshold: 60                           # 300s 容忍窗口，给 SB 启动留时间
          livenessProbe:
            tcpSocket: { port: 8080 }
            initialDelaySeconds: 60
            periodSeconds: 10
          resources:
            requests: { cpu: 200m, memory: 512Mi }
            limits:   { cpu: "1",   memory: 1Gi }
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: { drop: [ALL] }                  # 后端 Java 容器可 drop ALL
```

> **坑（前端 nginx，非本 deployment）**：前端 nginx 容器**不能 drop ALL capabilities**——它需 `CAP_CHOWN` 给非 root 用户 chown 缓存目录，drop 后 CrashLoop。前端 manifest 已去掉容器级 securityContext，只保留 pod 级 seccomp。这是"不同容器不同安全姿态"的实例。

**staging overlay**（`overlays/staging/kustomization.yaml`）：

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: docloom
resources: [../../base]
images:
  - name: docloom-backend
    newName: localhost:5000/docloom-backend
    newTag: placeholder          # CI 用 Python 脚本原地替换为 sha-xxxxx
```

### 7.2 CI/CD 流水线（`.github/workflows/ci-cd.yml`）

**设计**：`runs-on: self-hosted`（VM 上的 runner）。push master → docker build → push `localhost:5000` → Python 脚本改写 staging `newTag` → `kubectl kustomize` 渲染校验 → `kubectl apply -k` **直推部署**（绕开 Argo fetch 不稳）→ `rollout status` → best-effort force-push `deploy/staging`（6 次重试、`continue-on-error`）。

**关键代码**：

```yaml
on:
  push:
    branches: [master]
  workflow_dispatch:
permissions:
  contents: write
jobs:
  build-publish-gitops:
    runs-on: self-hosted
    env:
      IMAGE: localhost:5000/docloom-backend
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: '0' }                  # 完整历史，用于 git rev-parse --short HEAD
      - name: 计算镜像 tag
        id: tag
        run: echo "sha=sha-$(git rev-parse --short HEAD)" >> "$GITHUB_OUTPUT"
      - name: 构建镜像
        run: docker build -t $IMAGE:${{ steps.tag.outputs.sha }} .
      - name: 推送到本地 registry
        run: docker push $IMAGE:${{ steps.tag.outputs.sha }}
      - name: 更新 GitOps 镜像标签
        env: { TAG: ${{ steps.tag.outputs.sha }} }
        run: |
          python3 - <<'PY'
          import os
          from pathlib import Path
          tag = os.environ["TAG"]
          p = Path("deploy/gitops/overlays/staging/kustomization.yaml")
          lines = p.read_text(encoding="utf-8").splitlines()
          hit = False
          for i, line in enumerate(lines):
              if line.strip().startswith("newTag:"):
                  indent = line[: len(line) - len(line.lstrip())]
                  lines[i] = f"{indent}newTag: {tag}"
                  hit = True; break
          if not hit: raise SystemExit("ERROR: kustomization.yaml 里找不到 newTag")
          p.write_text("\n".join(lines) + "\n", encoding="utf-8")
          print(f"newTag -> {tag}")
          PY
      - name: 渲染校验
        run: |
          export PATH=/usr/local/bin:$PATH
          kubectl kustomize deploy/gitops/overlays/staging > /tmp/rendered.yaml
          test -s /tmp/rendered.yaml
          grep -q "image: localhost:5000/docloom-backend:${{ steps.tag.outputs.sha }}" /tmp/rendered.yaml \
            || { echo "渲染后镜像未替换为本次 sha"; exit 1; }
      - name: kubectl 直推部署(apply -k, 绕开 Argo fetch 不稳)
        env: { KUBECONFIG: /home/mrlu/.kube/config }
        run: |
          export PATH=/usr/local/bin:$PATH
          kubectl create namespace docloom 2>/dev/null || true
          kubectl apply -k deploy/gitops/overlays/staging
          kubectl rollout status deployment/docloom-backend -n docloom --timeout=300s
      - name: 回写 deploy/staging(GitOps 记录, best-effort)
        continue-on-error: true
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add deploy/gitops/overlays/staging/kustomization.yaml
          git commit -m "deploy(staging): ${{ steps.tag.outputs.sha }}"
          for i in $(seq 1 6); do
            if git push --force origin HEAD:deploy/staging; then echo "push OK 第${i}次"; exit 0; fi
            sleep 15
          done
          echo "deploy/staging push 失败(非关键,已用 kubectl 直推部署)" >&2
```

> **为什么 kubectl 直推**：Argo CD 已装（v3.5，两个 App），但 repo-server fetch GitHub 间歇 TLS 超时，会用旧 manifest 回退打架。故**自动同步关闭**，部署交给 workflow 的 `kubectl apply -k`（本地可靠）；`deploy/staging` force-push 仅作 GitOps 状态记录，非关键。`image-updater` 试过装但已大重构不认旧注解，已卸载弃用。

### 7.3 ES 中间件

ES 8.17.7 加入 `/opt/middleware` docker-compose（单节点、`xpack.security` 关、明文 9200、`es_data` 卷），**IK 8.17.7 走 `release.infinilabs.com` CDN**（非 github，故稳，构建时装）。后端 `application.yml`：

```yaml
security:
  excludes:
    # ... 其它 ...
    - /doc/public/**           # 公开阅读面免鉴权

spring:
  elasticsearch:
    uris: http://192.168.149.128:9200
    connection-timeout: 5s
    socket-timeout: 30s
  data:
    elasticsearch:
      repositories:
        enabled: false         # 不用 Spring Data ES 仓储，只用原生 ElasticsearchClient
management:
  health:
    elasticsearch:
      enabled: false           # 关 actuator ES 健康探测，避免 ES 不可用时 health 端点 DOWN

docloom:
  es:
    enabled: true
    index-name: docloom_doc
    index-analyzer: ik_max_word     # 索引期细粒度
    search-analyzer: ik_smart       # 查询期智能切分
    max-results: 100
  sync:
    cron: "0 30 * * * ?"        # 定时同步，默认每小时 :30
```

> **SB 版本对齐**：`spring-boot-starter-data-elasticsearch` 由 SB 管版本，绑定 `elasticsearch-java 8.18.8`，而 ES 服务端是 8.17.7——客户端略新于服务端，8.x 小版本向后兼容，实测可用。

---

## 8. 难点与坑总结

| # | 难点/坑 | 对策 | 体现于 |
|---|---|---|---|
| 1 | 国内节点 GitHub 间歇不稳（TLS 超时/连接重置/checkout 失败） | 同步幂等+best-effort；部署走 kubectl 直推不依赖 Argo fetch；deploy/staging push 带重试+continue-on-error；IK 走 infinilabs CDN 非 github | sync 引擎、ci-cd.yml、ES 镜像 |
| 2 | doc_file 唯一键 `path(1024)` 在 utf8mb4 下超 InnoDB 3072 字节 | 唯一键只取 `path(255)` 前缀；应用层 selectOne by path 判存在，不依赖 DB 唯一键报错 | `docloom_doc_m2.sql` |
| 3 | 前端 nginx 容器不能 drop ALL（需 CAP_CHOWN chown 缓存） | 前端 manifest 去掉容器级 securityContext（保留 pod 级 seccomp）；后端 Java 容器照常 drop ALL | base/deployment.yaml |
| 4 | SnailJob Server 镜像受 daocloud 白名单拦截、本机无 docker | 定时同步回退 Spring `@Scheduled`；`DocSyncJobExecutor` 保留待切；`@EnableScheduling` 由 SnailJobConfig 提供 | DocSyncScheduler |
| 5 | 多租户框架做匿名公开面：查询默认带 tenant_id 过滤 | 读用 `TenantHelper.ignore` 跨租户查 + `public_visible` 业务过滤；写用 `TenantHelper.dynamic` 按来源回填租户 | PublicDocServiceImpl、DocSyncScheduler |
| 6 | mermaid 渲染时序 + XSS | markdown-it highlight 拦截围栏输出占位 div + escapeHtml；`nextTick` 后 `mermaid.run`；`securityLevel:'strict'`；最终 DOMPurify | DocViewer.vue |
| 7 | md 渲染 XSS | `html:false` 阻原生透传 + DOMPurify；搜索片段 `ALLOWED_TAGS:['mark']` 只放行 mark | DocViewer.vue、reader |
| 8 | 异步同步阻塞 nginx 60s | `@Async` 立即返回 + 前端 `setTimeout` 轮询 `getSource` 状态 + `onUnmounted` 清理 | syncAsync、reader |
| 9 | ES 单节点无 SLA | 全程 best-effort：启动建索引失败不阻断、双写失败跳过、检索降级 LIKE、`reindexAll` 分批；`indexReady` AtomicBoolean | DocSearchServiceImpl、EsIndexInitializer |
| 10 | k8s HTTP 探针被 sa-token 拦 actuator 致 401 反复重启 | 探针用 TCP socket 而非 HTTP；`failureThreshold:60` 留 300s 启动窗口 | base/deployment.yaml |
| 11 | force-push 致 blob sha 错位 | 以 path 为唯一键、sha 只判变更；删除走 remotePaths 差集，不以 sha 为键 | DocSyncServiceImpl |
| 12 | raw URL path 含特殊字符 | URLEncoder 编码后还原 `%2F→/`、`+→%20` | GithubClient.encodePath |
| 13 | UTF-8 BOM 污染检索正文 | `decode()` 剥 BOM 首三字节 | DocParser |
| 14 | Argo repo-server fetch 旧 manifest 回退打架 | Argo 自动同步关闭，部署交 workflow kubectl 直推 | ci-cd.yml |
| 15 | 公开检索越权（匿名借 sourceId 查非公开来源） | service 层先算"允许检索的来源集合"（public_visible=1），不透传前端 sourceId | PublicDocServiceImpl.search |
| 16 | GitHub rate limit（无 token 60 req/h） | 全局 PAT 兜底（`docloom.github.default-token`）+ 来源级 `@EncryptField` 覆盖；trees 一次枚举 | GithubClient.resolveToken |
| 17 | webhook 方案不可行（LAN 无公网入口） | 评估后弃用，同步主路径走 `@Scheduled` 轮询（详见 §9） | 已 `git checkout` 丢弃 |

---

## 9. 演进与待办

### 9.1 已完成

| 里程碑 | 内容 | 后端 commit | 前端 commit |
|---|---|---|---|
| M1 | 骨架 + `doc_source` CRUD + 连通性校验 | 4e4f6ac | 0fe7942 |
| M2 | 同步引擎（trees+raw+解析+upsert+差集删+ES 双写） | c92e275 | 8b64e1f |
| M3 | 公开阅读面（`/doc/public/**` 匿名 + DocViewer + `/p/doc`） | f52d490 | 8deaceb |
| M4 | ES+IK 检索（索引双写 + 公开检索 + 高亮 + LIKE 降级） | 71113ff | 0089e1d |
| M5 | 异步同步+轮询 / `@Scheduled` 定时 / Mermaid / 限流 | 81b964c+fce4636 | 87332e7 |
| — | 前端视觉重做（书册风 + `--dl-*` token，去 AI 味） | — | a3f759e |
| — | 前端阅读页交互升级（TOC / 暗色 / 命令面板 / 拖拽排序） | — | 75b0e0d |

> M1–M5 + 前端视觉/交互增强**全部已推送**（两仓 `master == origin/master`，工作区干净，已 CI 部署）。

### 9.2 弃用

- **push webhook**：某次会话曾动笔（`DocLoomWebhookProperties` + `DocWebhookController` + `syncAsyncInTenant`，HMAC-SHA256 验签 + 常量时间比较 + 跨租户异步同步，可编译），但**评估后 `git checkout`+`git clean` 丢弃**——当前 k3s 部署为 LAN NodePort `192.168.149.128:30080`，无公网 HTTPS 入口，GitHub 够不到 `/doc/webhook/github` 端点。同步主路径继续走 `@Scheduled` 轮询。将来上公网 ingress/隧道再启用（设计要点见 §4.6 思路，可重写）。

### 9.3 待办

- ⬜ **M2b Tika**：docx/doc/pdf 接入 Apache Tika 抽正文（`toPlainText`→`rawText`、`toHtml`→`renderHtml`），替换 `DocParser` 的 `UnsupportedFormatException` 分支。当前这几类直接跳过，知识库残缺。
- ⬜ **PAT 注入**：全局 `GITHUB_TOKEN` env 或来源级 `@EncryptField`（无 token 走匿名 60 req/h，大仓库撞限流）。
- ⬜ **OSS 大文件**：VM MinIO（`mrlu`/`mrlumrlu`），超大 HTML/原档入 OSS，DB 存 ref。
- ⬜ **SnailJob Server**（可选）：镜像源加白名单或别机构建推 `:5000`/本机装 docker；切回时 `snail-job.enabled` 保持 true、`@EnableScheduling` 已在、`DocSyncJobExecutor` 已在，停 `@Scheduled` 即可。
- 💡 **RAG 知识库**（已评估，待启动）：DocLoom 已具备 RAG 的 70%（抓取/解析/存储/检索/展示/鉴权/部署），ES 8.17 原生支持 `dense_vector`+kNN+RRF 混合检索，可加：`doc_chunk` 分块 + embedding（智谱 `embedding-3`）写 ES 同索引新字段 + BM25(kNN) 混合 + GLM 问答 + 引用回链。前提是语料够大、先完成 M2b Tika。

---

> 本文随代码演进更新。设计期 rationale 见同目录《概要设计-DocLoom文档托管.md》。
