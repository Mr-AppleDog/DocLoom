package org.dromara.doc.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.doc.domain.bo.DocSourceBo;
import org.dromara.doc.domain.vo.DocSourceTestVo;
import org.dromara.doc.domain.vo.DocSourceVo;
import org.dromara.doc.service.IDocSourceService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 文档来源 Controller
 *
 * @author DocLoom
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/doc/source")
public class DocSourceController extends BaseController {

    private final IDocSourceService docSourceService;

    /**
     * 查询文档来源列表
     */
    @SaCheckPermission("doc:source:list")
    @GetMapping("/list")
    public TableDataInfo<DocSourceVo> list(@Validated(QueryGroup.class) DocSourceBo bo, PageQuery pageQuery) {
        return docSourceService.queryPageList(bo, pageQuery);
    }

    /**
     * 获取文档来源详情
     */
    @SaCheckPermission("doc:source:query")
    @GetMapping("/{id}")
    public R<DocSourceVo> getInfo(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return R.ok(docSourceService.queryById(id));
    }

    /**
     * 新增文档来源
     */
    @SaCheckPermission("doc:source:add")
    @Log(title = "文档来源", businessType = BusinessType.INSERT)
    @RepeatSubmit(interval = 2, timeUnit = TimeUnit.SECONDS, message = "{repeat.submit.message}")
    @PostMapping
    public R<Void> add(@Validated(AddGroup.class) @RequestBody DocSourceBo bo) {
        return toAjax(docSourceService.insertByBo(bo));
    }

    /**
     * 修改文档来源
     */
    @SaCheckPermission("doc:source:edit")
    @Log(title = "文档来源", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody DocSourceBo bo) {
        return toAjax(docSourceService.updateByBo(bo));
    }

    /**
     * 删除文档来源
     */
    @SaCheckPermission("doc:source:remove")
    @Log(title = "文档来源", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空") @PathVariable Long[] ids) {
        return toAjax(docSourceService.deleteWithValidByIds(Arrays.asList(ids), true));
    }

    /**
     * 连通性校验：校验仓库/分支/路径可达并统计候选文件数
     */
    @SaCheckPermission("doc:source:test")
    @PostMapping("/test")
    public R<DocSourceTestVo> test(@RequestBody DocSourceBo bo) {
        return R.ok(docSourceService.testConnectivity(bo));
    }

}
