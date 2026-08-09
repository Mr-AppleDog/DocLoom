package org.dromara.doc.service;

import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.doc.domain.DocFile;
import org.dromara.doc.domain.vo.DocSearchHitVo;

import java.util.Collection;

/**
 * 文档检索 Service：ES 索引/删除/重建 + 检索（ES 故障自动降级 MySQL LIKE）
 *
 * @author DocLoom
 */
public interface IDocSearchService {

    /**
     * 幂等确保索引存在（best-effort，ES 不可用不抛异常）
     */
    void ensureIndex();

    /**
     * 索引/更新单个文档（best-effort，失败不影响 MySQL 同步）
     *
     * @param file       已落库的 doc_file（id/sourceId/path/name/ext/rawText/syncTime 等已赋值）
     * @param sourceName 来源名称（冗余入索引，便于检索结果直接展示）
     */
    void indexFile(DocFile file, String sourceName);

    /**
     * 批量删除文档（best-effort）
     */
    void deleteFiles(Collection<Long> docFileIds);

    /**
     * 按来源删除全部文档（best-effort，来源删除时调用）
     */
    void deleteBySource(Long sourceId);

    /**
     * 全量重建索引（从 MySQL raw_text），返回已索引条数
     */
    int reindexAll();

    /**
     * 检索
     *
     * @param kw           关键字
     * @param sourceFilter 限定来源集合（null/空=不限）
     * @param pageNum      页码（1 起）
     * @param pageSize     每页条数
     * @return 命中列表 + 总数（ES 故障自动降级 MySQL LIKE）
     */
    TableDataInfo<DocSearchHitVo> search(String kw, Collection<Long> sourceFilter, int pageNum, int pageSize);

}
