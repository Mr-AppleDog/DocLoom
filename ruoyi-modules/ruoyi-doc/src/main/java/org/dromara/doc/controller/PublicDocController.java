package org.dromara.doc.controller;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.doc.domain.vo.DocFileViewVo;
import org.dromara.doc.domain.vo.DocFileVo;
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
    @GetMapping("/sources")
    public R<List<DocSourceVo>> sources() {
        return R.ok(publicDocService.listPublicSources());
    }

    /**
     * 某来源文档目录（扁平列表，前端构建树）
     */
    @GetMapping("/tree/{sourceId}")
    public R<List<DocFileVo>> tree(@NotNull(message = "主键不能为空") @PathVariable Long sourceId) {
        return R.ok(publicDocService.listFiles(sourceId));
    }

    /**
     * 取文档渲染体
     */
    @GetMapping("/view/{id}")
    public R<DocFileViewVo> view(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return R.ok(publicDocService.viewFile(id));
    }

}
