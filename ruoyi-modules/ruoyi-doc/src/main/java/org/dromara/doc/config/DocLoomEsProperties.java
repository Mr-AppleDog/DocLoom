package org.dromara.doc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DocLoom Elasticsearch 检索配置
 *
 * @author DocLoom
 */
@Data
@ConfigurationProperties(prefix = "docloom.es")
public class DocLoomEsProperties {

    /**
     * 是否启用 ES 检索（false 时索引操作 no-op，检索直接降级 MySQL LIKE）
     */
    private boolean enabled = true;

    /**
     * 索引名
     */
    private String indexName = "docloom_doc";

    /**
     * 索引期分词器（IK 细粒度）
     */
    private String indexAnalyzer = "ik_max_word";

    /**
     * 检索期分词器（IK 智能切分）
     */
    private String searchAnalyzer = "ik_smart";

    /**
     * 单次检索最大返回条数
     */
    private int maxResults = 100;

}
