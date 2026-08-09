package org.dromara.doc.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.dromara.doc.service.IDocSearchService;
import org.dromara.doc.service.IDocSyncService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 文档同步 Controller
 *
 * @author DocLoom
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/doc/sync")
public class DocSyncController extends BaseController {

    private final IDocSyncService docSyncService;
    private final IDocSearchService docSearchService;

    /**
     * 触发同步（异步执行：立即返回，后台同步；进度经来源状态字段查询）
     */
    @SaCheckPermission("doc:source:sync")
    @RepeatSubmit(interval = 3000)
    @Log(title = "文档同步", businessType = BusinessType.OTHER)
    @PostMapping("/{sourceId}")
    public R<String> sync(@NotNull(message = "主键不能为空") @PathVariable Long sourceId) {
        docSyncService.syncAsync(sourceId);
        return R.ok("同步任务已提交");
    }

    /**
     * 全量重建 ES 索引（从 MySQL raw_text；ES 不可用抛 ServiceException）
     */
    @SaCheckPermission("doc:source:sync")
    @Log(title = "文档索引重建", businessType = BusinessType.OTHER)
    @PostMapping("/reindex")
    public R<Integer> reindex() {
        return R.ok(docSearchService.reindexAll());
    }

}
