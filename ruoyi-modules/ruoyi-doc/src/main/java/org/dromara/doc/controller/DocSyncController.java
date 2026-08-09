package org.dromara.doc.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.dromara.doc.domain.vo.DocSourceVo;
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

    /**
     * 触发同步（同步执行，返回最终状态）
     */
    @SaCheckPermission("doc:source:sync")
    @Log(title = "文档同步", businessType = BusinessType.OTHER)
    @PostMapping("/{sourceId}")
    public R<DocSourceVo> sync(@NotNull(message = "主键不能为空") @PathVariable Long sourceId) {
        return R.ok(docSyncService.sync(sourceId));
    }

}
