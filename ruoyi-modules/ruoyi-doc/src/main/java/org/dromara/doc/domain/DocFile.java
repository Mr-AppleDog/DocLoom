package org.dromara.doc.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 同步文档对象 doc_file
 *
 * @author DocLoom
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("doc_file")
public class DocFile extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 文档来源id
     */
    private Long sourceId;

    /**
     * 仓库内相对路径
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
     * GitHub blob sha
     */
    private String sha;

    /**
     * 字节
     */
    private Long size;

    /**
     * 抽取正文（检索源）
     */
    private String rawText;

    /**
     * Office/PDF 渲染HTML（md不存）
     */
    private String renderHtml;

    /**
     * md原文（前端渲染）
     */
    private String rawMd;

    /**
     * 仓库内文件修改时间
     */
    private Date contentUpdatedAt;

    /**
     * 本地同步时间
     */
    private Date syncTime;

}
