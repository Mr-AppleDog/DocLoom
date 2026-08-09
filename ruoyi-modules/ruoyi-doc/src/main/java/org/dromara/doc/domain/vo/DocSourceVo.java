package org.dromara.doc.domain.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.doc.domain.DocSource;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 文档来源视图对象 doc_source
 *
 * @author DocLoom
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = DocSource.class)
public class DocSourceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @ExcelProperty(value = "主键")
    private Long id;

    /**
     * 来源名称
     */
    @ExcelProperty(value = "来源名称")
    private String name;

    /**
     * GitHub owner
     */
    @ExcelProperty(value = "owner")
    private String owner;

    /**
     * 仓库名
     */
    @ExcelProperty(value = "仓库名")
    private String repo;

    /**
     * 分支
     */
    @ExcelProperty(value = "分支")
    private String branch;

    /**
     * 仓库内子路径
     */
    @ExcelProperty(value = "路径")
    private String path;

    /**
     * 允许的文件扩展名
     */
    @ExcelProperty(value = "扩展名")
    private String fileExts;

    /**
     * 单文件大小上限（KB）
     */
    @ExcelProperty(value = "大小上限(KB)")
    private Integer maxFileSize;

    /**
     * 是否公开 0否 1是
     */
    @ExcelProperty(value = "是否公开")
    private String publicVisible;

    /**
     * 同步方式 0手动 1定时
     */
    @ExcelProperty(value = "同步方式")
    private String syncMode;

    /**
     * 定时同步表达式
     */
    @ExcelProperty(value = "定时表达式")
    private String syncCron;

    /**
     * 最近一次同步状态 0失败 1成功 2进行中
     */
    @ExcelProperty(value = "同步状态")
    private String lastSyncStatus;

    /**
     * 最近一次同步时间
     */
    @ExcelProperty(value = "最近同步时间")
    private Date lastSyncTime;

    /**
     * 已同步文件数
     */
    @ExcelProperty(value = "文件数")
    private Integer fileCount;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;

    /**
     * 创建时间
     */
    @ExcelProperty(value = "创建时间")
    private Date createTime;

    /**
     * 更新时间
     */
    @ExcelProperty(value = "更新时间")
    private Date updateTime;

    // 注意：githubToken 不在 VO 中暴露，避免密钥外泄

}
