package org.dromara.doc.domain.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.doc.domain.DocFile;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 同步文档视图对象 doc_file（仅元数据，正文/HTML 由 /view 接口单独取）
 *
 * @author DocLoom
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = DocFile.class)
public class DocFileVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @ExcelProperty(value = "主键")
    private Long id;

    @ExcelProperty(value = "来源id")
    private Long sourceId;

    @ExcelProperty(value = "路径")
    private String path;

    @ExcelProperty(value = "文件名")
    private String name;

    @ExcelProperty(value = "扩展名")
    private String ext;

    @ExcelProperty(value = "字节")
    private Long size;

    @ExcelProperty(value = "仓库修改时间")
    private Date contentUpdatedAt;

    @ExcelProperty(value = "同步时间")
    private Date syncTime;

    @ExcelProperty(value = "创建时间")
    private Date createTime;

    @ExcelProperty(value = "更新时间")
    private Date updateTime;

    // 不含 rawText/rawMd/renderHtml，避免列表/树传输大字段

}
