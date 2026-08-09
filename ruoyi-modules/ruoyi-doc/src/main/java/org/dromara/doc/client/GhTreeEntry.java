package org.dromara.doc.client;

import lombok.Data;

/**
 * GitHub 仓库树条目（同步用）
 *
 * @author DocLoom
 */
@Data
public class GhTreeEntry {

    /**
     * 仓库内相对路径
     */
    private String path;

    /**
     * blob sha（变更判定）
     */
    private String sha;

    /**
     * 字节
     */
    private Long size;

    /**
     * 扩展名（小写，不含点）
     */
    private String ext;

}
