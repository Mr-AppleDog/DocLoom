package org.dromara.doc.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.doc.service.IDocSearchService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等确保 ES 索引存在（best-effort，ES 不可用不阻断启动，首次检索时再重试）
 *
 * @author DocLoom
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class EsIndexInitializer implements ApplicationRunner {

    private final IDocSearchService docSearchService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            docSearchService.ensureIndex();
        } catch (Exception e) {
            log.warn("[DocLoom] 启动期 ES 索引就绪失败（将在首次检索时重试）: {}", e.getMessage());
        }
    }

}
