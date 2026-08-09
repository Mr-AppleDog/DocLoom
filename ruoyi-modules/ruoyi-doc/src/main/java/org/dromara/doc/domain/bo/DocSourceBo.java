package org.dromara.doc.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.doc.domain.DocSource;

/**
 * 文档来源业务对象 doc_source
 *
 * @author DocLoom
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = DocSource.class, reverseConvertGenerate = false)
public class DocSourceBo extends BaseEntity {

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 来源名称
     */
    @NotBlank(message = "来源名称不能为空", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /**
     * GitHub owner
     */
    @NotBlank(message = "仓库owner不能为空", groups = {AddGroup.class, EditGroup.class})
    private String owner;

    /**
     * 仓库名
     */
    @NotBlank(message = "仓库名不能为空", groups = {AddGroup.class, EditGroup.class})
    private String repo;

    /**
     * 分支（留空表示使用仓库默认分支）
     */
    private String branch;

    /**
     * 仓库内子路径
     */
    private String path;

    /**
     * GitHub PAT
     */
    private String githubToken;

    /**
     * 允许的文件扩展名
     */
    private String fileExts;

    /**
     * 单文件大小上限（KB）
     */
    private Integer maxFileSize;

    /**
     * 是否公开 0否 1是
     */
    private String publicVisible;

    /**
     * 同步方式 0手动 1定时
     */
    private String syncMode;

    /**
     * 定时同步表达式
     */
    private String syncCron;

    /**
     * 备注
     */
    private String remark;

}
