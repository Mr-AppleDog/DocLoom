package org.dromara.doc.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文档检索命中结果
 *
 * @author DocLoom
 */
@Data
public class DocSearchHitVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * doc_file 主键
     */
    private Long docFileId;

    /**
     * 来源id
     */
    private Long sourceId;

    /**
     * 来源名称
     */
    private String sourceName;

    /**
     * 仓库内路径
     */
    private String path;

    /**
     * 文件名
     */
    private String name;

    /**
     * 扩展名
     */
    private String ext;

    /**
     * 命中片段（已高亮，含 mark 标签）
     */
    private String fragment;

    /**
     * 命中评分（ES 模式有，LIKE 降级为 null）
     */
    private Float score;

}
