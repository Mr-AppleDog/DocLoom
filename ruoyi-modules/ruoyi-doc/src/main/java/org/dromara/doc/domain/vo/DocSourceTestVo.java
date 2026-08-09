package org.dromara.doc.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * GitHub 仓库来源连通性校验结果
 *
 * @author DocLoom
 */
@Data
public class DocSourceTestVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 仓库是否可达
     */
    private Boolean reachable;

    /**
     * 路径下符合扩展名过滤的候选文件数
     */
    private Integer fileCount;

    /**
     * GitHub 返回的树是否被截断（仓库过大）
     */
    private Boolean truncated;

    /**
     * 实际生效的分支（入参为空时为仓库默认分支）
     */
    private String branch;

    /**
     * 提示信息
     */
    private String message;

}
