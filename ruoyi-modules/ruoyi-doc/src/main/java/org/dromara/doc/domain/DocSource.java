package org.dromara.doc.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.encrypt.annotation.EncryptField;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 文档来源对象 doc_source
 *
 * @author DocLoom
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("doc_source")
public class DocSource extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 来源名称
     */
    private String name;

    /**
     * GitHub owner
     */
    private String owner;

    /**
     * 仓库名
     */
    private String repo;

    /**
     * 分支
     */
    private String branch;

    /**
     * 仓库内子路径，空表示根目录
     */
    private String path;

    /**
     * GitHub Personal Access Token（加密存储）
     */
    @EncryptField
    private String githubToken;

    /**
     * 允许的文件扩展名，逗号分隔
     */
    private String fileExts;

    /**
     * 单文件大小上限（KB）
     */
    private Integer maxFileSize;

    /**
     * 是否对外公开 0否 1是
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
     * 最近一次同步状态 0失败 1成功 2进行中
     */
    private String lastSyncStatus;

    /**
     * 最近一次同步时间
     */
    private Date lastSyncTime;

    /**
     * 已同步文件数
     */
    private Integer fileCount;

    /**
     * 备注
     */
    private String remark;

    /**
     * 版本
     */
    @Version
    private Long version;

    /**
     * 删除标志
     */
    @TableLogic
    private Long delFlag;

}
