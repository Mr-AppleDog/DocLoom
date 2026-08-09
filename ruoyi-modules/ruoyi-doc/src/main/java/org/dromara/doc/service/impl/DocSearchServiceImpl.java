package org.dromara.doc.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.doc.config.DocLoomEsProperties;
import org.dromara.doc.domain.DocFile;
import org.dromara.doc.domain.DocSource;
import org.dromara.doc.domain.vo.DocSearchHitVo;
import org.dromara.doc.mapper.DocFileMapper;
import org.dromara.doc.mapper.DocSourceMapper;
import org.dromara.doc.service.IDocSearchService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 文档检索 Service 实现：ES 索引/删除/重建 + 检索（ES 故障自动降级 MySQL LIKE）
 * <p>
 * 所有 ES 调用均 best-effort：失败时索引操作记日志跳过、检索降级 MySQL LIKE，不影响主流程。
 *
 * @author DocLoom
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DocSearchServiceImpl implements IDocSearchService {

    private final ElasticsearchClient client;
    private final DocLoomEsProperties properties;
    private final DocFileMapper docFileMapper;
    private final DocSourceMapper docSourceMapper;

    /**
     * 索引是否就绪（ensureIndex 成功后置 true；任一调用异常置 false 以便下次重试）
     */
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    @Override
    public void ensureIndex() {
        if (!properties.isEnabled() || indexReady.get()) {
            return;
        }
        try {
            boolean exists = client.indices().exists(e -> e.index(properties.getIndexName())).value();
            if (!exists) {
                createIndex();
            }
            indexReady.set(true);
        } catch (Exception e) {
            log.warn("[DocLoom] ES 索引就绪检查失败（检索将降级 MySQL LIKE）: {}", e.getMessage());
            indexReady.set(false);
        }
    }

    /**
     * 创建索引 + 字段映射（IK 分词器）
     */
    private void createIndex() throws Exception {
        String idx = properties.getIndexName();
        String ia = properties.getIndexAnalyzer();
        String sa = properties.getSearchAnalyzer();
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
            )
        );
    }

    @Override
    public void indexFile(DocFile file, String sourceName) {
        if (!properties.isEnabled()) {
            return;
        }
        ensureIndex();
        if (!indexReady.get() || file == null || file.getId() == null) {
            return;
        }
        try {
            Map<String, Object> doc = buildDoc(file, sourceName);
            client.index(i -> i
                .index(properties.getIndexName())
                .id(String.valueOf(file.getId()))
                .document(doc));
        } catch (Exception e) {
            log.warn("[DocLoom] ES 索引文档失败 id={} best-effort 跳过: {}", file.getId(), e.getMessage());
        }
    }

    @Override
    public void deleteFiles(Collection<Long> docFileIds) {
        if (!properties.isEnabled() || docFileIds == null || docFileIds.isEmpty()) {
            return;
        }
        ensureIndex();
        if (!indexReady.get()) {
            return;
        }
        try {
            List<BulkOperation> ops = new ArrayList<>();
            for (Long id : docFileIds) {
                ops.add(deleteOp(String.valueOf(id)));
            }
            client.bulk(b -> b.index(properties.getIndexName()).operations(ops));
        } catch (Exception e) {
            log.warn("[DocLoom] ES 批量删除失败 best-effort 跳过: {}", e.getMessage());
        }
    }

    @Override
    public void deleteBySource(Long sourceId) {
        if (!properties.isEnabled() || sourceId == null) {
            return;
        }
        ensureIndex();
        if (!indexReady.get()) {
            return;
        }
        try {
            client.deleteByQuery(d -> d
                .index(properties.getIndexName())
                .query(q -> q.term(t -> t.field("sourceId").value(FieldValue.of(sourceId)))));
        } catch (Exception e) {
            log.warn("[DocLoom] ES 按来源删除失败 sourceId={} best-effort: {}", sourceId, e.getMessage());
        }
    }

    @Override
    public int reindexAll() {
        if (!properties.isEnabled()) {
            return 0;
        }
        // 删除旧索引（不存在则忽略）
        try {
            boolean exists = client.indices().exists(e -> e.index(properties.getIndexName())).value();
            if (exists) {
                client.indices().delete(d -> d.index(properties.getIndexName()));
            }
        } catch (Exception e) {
            log.warn("[DocLoom] 删除旧索引失败（首次重建可忽略）: {}", e.getMessage());
        }
        indexReady.set(false);
        ensureIndex();
        if (!indexReady.get()) {
            throw new ServiceException("ES 不可用，无法重建索引");
        }
        // 来源 id→name 映射（跨租户）
        Map<Long, String> nameMap = new HashMap<>();
        List<DocSource> sources = docSourceMapper.selectList(Wrappers.<DocSource>lambdaQuery()
            .select(DocSource::getId, DocSource::getName));
        for (DocSource s : sources) {
            nameMap.put(s.getId(), s.getName());
        }
        int total = 0;
        int from = 0;
        int batch = 500;
        while (true) {
            List<DocFile> page = docFileMapper.selectList(Wrappers.<DocFile>lambdaQuery()
                .select(DocFile::getId, DocFile::getSourceId, DocFile::getPath, DocFile::getName,
                    DocFile::getExt, DocFile::getRawText, DocFile::getSyncTime, DocFile::getContentUpdatedAt)
                .last("limit " + from + "," + batch));
            if (page.isEmpty()) {
                break;
            }
            List<BulkOperation> ops = new ArrayList<>();
            for (DocFile f : page) {
                Map<String, Object> doc = buildDoc(f, nameMap.get(f.getSourceId()));
                ops.add(indexOp(String.valueOf(f.getId()), doc));
            }
            try {
                client.bulk(b -> b.index(properties.getIndexName()).operations(ops));
            } catch (Exception e) {
                log.warn("[DocLoom] 全量重建 batch from={} 失败 best-effort 继续: {}", from, e.getMessage());
            }
            total += page.size();
            from += batch;
            if (page.size() < batch) {
                break;
            }
        }
        log.info("[DocLoom] 全量重建索引完成，共 {} 条", total);
        return total;
    }

    @Override
    public TableDataInfo<DocSearchHitVo> search(String kw, Collection<Long> sourceFilter, int pageNum, int pageSize) {
        if (StringUtils.isBlank(kw)) {
            return new TableDataInfo<>(Collections.emptyList(), 0L);
        }
        if (pageSize <= 0) {
            pageSize = 10;
        }
        if (pageNum < 1) {
            pageNum = 1;
        }
        int max = properties.getMaxResults();
        if (pageSize > max) {
            pageSize = max;
        }
        if (!properties.isEnabled()) {
            return likeSearch(kw, sourceFilter, pageNum, pageSize);
        }
        try {
            ensureIndex();
            if (!indexReady.get()) {
                return likeSearch(kw, sourceFilter, pageNum, pageSize);
            }
            return esSearch(kw, sourceFilter, pageNum, pageSize);
        } catch (Exception e) {
            log.warn("[DocLoom] ES 检索失败，降级 MySQL LIKE: {}", e.getMessage());
            return likeSearch(kw, sourceFilter, pageNum, pageSize);
        }
    }

    /**
     * ES 检索：multi_match([rawText^2, name], ik_smart) + 高亮 + 分页
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private TableDataInfo<DocSearchHitVo> esSearch(String kw, Collection<Long> sourceFilter, int pageNum, int pageSize)
        throws Exception {
        int from = (pageNum - 1) * pageSize;
        String sa = properties.getSearchAnalyzer();
        final boolean hasFilter = sourceFilter != null && !sourceFilter.isEmpty();
        SearchResponse<Map> resp = client.search(s -> s
            .index(properties.getIndexName())
            .from(from)
            .size(pageSize)
            .source(src -> src.filter(f -> f.includes("sourceId", "sourceName", "path", "name", "ext")))
            .query(q -> q.bool(b -> {
                b.must(m -> m.multiMatch(mm -> mm.query(kw).fields("rawText^2", "name").analyzer(sa)));
                if (hasFilter) {
                    List<FieldValue> values = new ArrayList<>();
                    for (Long sid : sourceFilter) {
                        values.add(FieldValue.of(sid));
                    }
                    b.filter(f -> f.terms(t -> t.field("sourceId").terms(tv -> tv.value(values))));
                }
                return b;
            }))
            .highlight(h -> h
                .fields("rawText", hf -> hf.fragmentSize(150).numberOfFragments(3).preTags(List.of("<mark>")).postTags(List.of("</mark>")))
                .fields("name", hf -> hf.numberOfFragments(0).preTags(List.of("<mark>")).postTags(List.of("</mark>")))
            ), Map.class);

        long total = resp.hits().total() == null ? 0 : resp.hits().total().value();
        List<DocSearchHitVo> rows = new ArrayList<>();
        for (Hit<Map> hit : resp.hits().hits()) {
            DocSearchHitVo vo = new DocSearchHitVo();
            if (hit.id() != null) {
                try {
                    vo.setDocFileId(Long.valueOf(hit.id()));
                } catch (NumberFormatException ignore) {
                    // 非数字 _id 忽略
                }
            }
            Map src = hit.source();
            if (src != null) {
                vo.setSourceId(asLong(src.get("sourceId")));
                vo.setSourceName(asString(src.get("sourceName")));
                vo.setPath(asString(src.get("path")));
                vo.setName(asString(src.get("name")));
                vo.setExt(asString(src.get("ext")));
            }
            vo.setFragment(firstHighlight(hit, "rawText", "name"));
            vo.setScore(hit.score() == null ? null : hit.score().floatValue());
            rows.add(vo);
        }
        return new TableDataInfo<>(rows, total);
    }

    /**
     * MySQL LIKE 降级检索（ES 不可用时）
     */
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
            Set<Long> sids = new HashSet<>();
            for (DocFile f : files) {
                if (f.getSourceId() != null) {
                    sids.add(f.getSourceId());
                }
            }
            Map<Long, String> nameMap = new HashMap<>();
            if (!sids.isEmpty()) {
                List<DocSource> srcs = docSourceMapper.selectList(Wrappers.<DocSource>lambdaQuery()
                    .select(DocSource::getId, DocSource::getName)
                    .in(DocSource::getId, sids));
                for (DocSource s : srcs) {
                    nameMap.put(s.getId(), s.getName());
                }
            }
            List<DocSearchHitVo> rows = new ArrayList<>();
            for (DocFile f : files) {
                DocSearchHitVo vo = new DocSearchHitVo();
                vo.setDocFileId(f.getId());
                vo.setSourceId(f.getSourceId());
                vo.setSourceName(nameMap.get(f.getSourceId()));
                vo.setPath(f.getPath());
                vo.setName(f.getName());
                vo.setExt(f.getExt());
                vo.setFragment(buildLikeFragment(f.getRawText(), kw));
                rows.add(vo);
            }
            return new TableDataInfo<>(rows, total);
        });
    }

    /**
     * 构建 ES 文档 Map
     */
    private Map<String, Object> buildDoc(DocFile file, String sourceName) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("sourceId", file.getSourceId());
        doc.put("sourceName", sourceName);
        doc.put("path", file.getPath());
        doc.put("name", file.getName());
        doc.put("ext", file.getExt());
        doc.put("rawText", file.getRawText());
        if (file.getSyncTime() != null) {
            doc.put("syncTime", file.getSyncTime().getTime());
        }
        if (file.getContentUpdatedAt() != null) {
            doc.put("contentUpdatedAt", file.getContentUpdatedAt().getTime());
        }
        return doc;
    }

    /**
     * 构建批量索引操作
     */
    private BulkOperation indexOp(String idStr, Map<String, Object> doc) {
        return BulkOperation.of(b -> b.index(i -> i
            .index(properties.getIndexName()).id(idStr).document(doc)));
    }

    /**
     * 构建批量删除操作
     */
    private BulkOperation deleteOp(String idStr) {
        return BulkOperation.of(b -> b.delete(d -> d
            .index(properties.getIndexName()).id(idStr)));
    }

    private String firstHighlight(Hit<?> hit, String... fields) {
        Map<String, List<String>> hl = hit.highlight();
        if (hl == null) {
            return null;
        }
        for (String f : fields) {
            List<String> frags = hl.get(f);
            if (frags != null && !frags.isEmpty()) {
                return String.join(" … ", frags);
            }
        }
        return null;
    }

    /**
     * LIKE 降级时构造命中片段：取关键字首次出现的前后窗口，并用 mark 标签高亮
     */
    private String buildLikeFragment(String rawText, String kw) {
        if (rawText == null) {
            return null;
        }
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

    private Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String asString(Object o) {
        return o == null ? null : o.toString();
    }

}
