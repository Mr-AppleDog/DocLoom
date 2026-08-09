package org.dromara.doc.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.doc.domain.DocSource;
import org.dromara.doc.mapper.DocSourceMapper;
import org.dromara.doc.service.IDocSyncService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档定时全量同步（Spring @Scheduled 回退方案）。
 * <p>
 * 因 SnailJob Server 暂未部署（镜像受 daocloud 白名单限制、本机无 docker），定时同步改用 Spring 原生调度。
 * {@code @EnableScheduling} 由 ruoyi-common-job 的 SnailJobConfig 在 {@code snail-job.enabled=true} 时激活（prod/dev 均为 true）。
 * SnailJob 任务类 {@link DocSyncJobExecutor} 保留，待 Server 上线后可切换回框架原生调度。
 *
 * @author DocLoom
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocSyncScheduler {

    private final IDocSyncService docSyncService;
    private final DocSourceMapper docSourceMapper;

    /**
     * 跨租户枚举所有来源，逐来源在其自身租户上下文内同步，失败隔离不影响其他来源。
     * cron 可经 {@code docloom.sync.cron} 覆盖，默认每小时 :30。
     */
    @Scheduled(cron = "${docloom.sync.cron:0 30 * * * ?}")
    public void syncAll() {
        List<DocSource> sources = TenantHelper.ignore(() -> docSourceMapper.selectList(
            Wrappers.<DocSource>lambdaQuery().select(DocSource::getId, DocSource::getTenantId)));
        log.info("[DocLoom] 定时同步开始，共 {} 个来源", sources.size());
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
                log.warn("[DocLoom] 来源 {} 同步失败: {}", s.getId(), e.getMessage());
            }
        }
        log.info("[DocLoom] 定时同步完成 成功{} 失败{}", ok, fail);
    }
}
