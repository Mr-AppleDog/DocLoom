package org.dromara.doc.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.doc.domain.DocSource;
import org.dromara.doc.mapper.DocSourceMapper;
import org.dromara.doc.service.IDocSyncService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档定时全量同步任务（SnailJob）。
 * <p>
 * 跨租户枚举所有文档来源，逐来源在其自身租户上下文内执行同步，失败隔离不影响其他来源。
 * 后台创建任务时 executor 名称填 {@link #EXECUTOR_NAME}，触发方式 CRON，表达式按需配置。
 *
 * @author DocLoom
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = DocSyncJobExecutor.EXECUTOR_NAME)
public class DocSyncJobExecutor {

    public static final String EXECUTOR_NAME = "docSyncAllTask";

    private final IDocSyncService docSyncService;
    private final DocSourceMapper docSourceMapper;

    public ExecuteResult jobExecute(JobArgs jobArgs) {
        // 跨租户枚举所有来源（仅取 id 与 tenantId，避免拉取加密 token 等无关注段）
        List<DocSource> sources = TenantHelper.ignore(() -> docSourceMapper.selectList(
            Wrappers.<DocSource>lambdaQuery().select(DocSource::getId, DocSource::getTenantId)));
        SnailJobLog.LOCAL.info("[DocLoom] 定时同步开始，共 {} 个来源", sources.size());
        int ok = 0, fail = 0;
        for (DocSource s : sources) {
            try {
                // 在来源自身租户上下文内同步，保证 DB 写入 tenant_id 正确
                TenantHelper.dynamic(s.getTenantId(), () -> {
                    docSyncService.sync(s.getId());
                });
                ok++;
            } catch (Exception e) {
                fail++;
                SnailJobLog.LOCAL.warn("[DocLoom] 来源 {} 同步失败: {}", s.getId(), e.getMessage());
            }
        }
        String msg = "同步完成 成功" + ok + " 失败" + fail;
        SnailJobLog.LOCAL.info("[DocLoom] {}", msg);
        return ExecuteResult.success(msg);
    }
}
