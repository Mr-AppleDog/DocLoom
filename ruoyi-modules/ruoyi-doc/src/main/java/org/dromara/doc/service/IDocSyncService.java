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

    /**
     * 异步同步：立即返回，同步在 Spring 异步线程池中执行（避免长任务阻塞 HTTP、触发 nginx 超时）。
     * 进度通过 doc_source 状态字段（last_sync_status=2 同步中 / 1 成功 / 0 失败）承载，前端轮询查询。
     *
     * @param sourceId 文档来源id
     */
    void syncAsync(Long sourceId);

}
