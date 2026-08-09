package org.dromara.doc.service;

import org.dromara.doc.domain.vo.DocSourceVo;

/**
 * 文档同步 Service 接口
 *
 * @author DocLoom
 */
public interface IDocSyncService {

    /**
     * 同步指定来源：枚举远端树 → 下载变更文件 → 解析落库 → 清理已删文件 → 更新来源状态
     *
     * @param sourceId 文档来源id
     * @return 更新后的来源视图（含同步状态/信息）
     */
    DocSourceVo sync(Long sourceId);

}
