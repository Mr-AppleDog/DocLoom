package org.dromara.doc.controller;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.ratelimiter.annotation.RateLimiter;
import org.dromara.common.ratelimiter.enums.LimitType;
import org.dromara.common.web.core.BaseController;
import org.dromara.doc.domain.vo.DocFileViewVo;
import org.dromara.doc.domain.vo.DocFileVo;
import org.dromara.doc.domain.vo.DocSearchHitVo;
import org.dromara.doc.domain.vo.DocSourceVo;
import org.dromara.doc.service.IPublicDocService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 公开阅读面 Controller（匿名，security.excludes 放行 /doc/public/**）
 *
 * @author DocLoom
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/doc/public")
public class PublicDocController extends BaseController {

    private final IPublicDocService publicDocService;

    /**
     * 公开来源列表
     */
    @RateLimiter(count = 20, time = 60, limitType = LimitType.IP)
    @GetMapping("/sources")
    public org.dromara.common.core.domain.R<List<DocSourceVo>> sources() {
        return org.dromara.common.core.domain.R.ok(publicDocService.listPublicSources());
    }

    /**
     * 某来源文档目录（扁平列表，前端构建树）
     */
    @RateLimiter(count = 60, time = 60, limitType = LimitType.IP)
    @GetMapping("/tree/{sourceId}")
    public org.dromara.common.core.domain.R<List<DocFileVo>> tree(@NotNull(message = "主键不能为空") @PathVariable Long sourceId) {
        return org.dromara.common.core.domain.R.ok(publicDocService.listFiles(sourceId));
    }

    /**
     * 取文档渲染体
     */
    @RateLimiter(count = 120, time = 60, limitType = LimitType.IP)
    @GetMapping("/view/{id}")
    public org.dromara.common.core.domain.R<DocFileViewVo> view(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return org.dromara.common.core.domain.R.ok(publicDocService.viewFile(id));
    }

    /**
     * 公开检索（匿名，ES 不可用时降级 MySQL LIKE；仅检索公开来源）
     */
    @RateLimiter(count = 30, time = 60, limitType = LimitType.IP)
    @GetMapping("/search")
    public TableDataInfo<DocSearchHitVo> search(@RequestParam(required = false) String kw,
                                                 @RequestParam(required = false) Long sourceId,
                                                 @RequestParam(defaultValue = "1") int pageNum,
                                                 @RequestParam(defaultValue = "10") int pageSize) {
        return publicDocService.search(kw, sourceId, pageNum, pageSize);
    }

}
