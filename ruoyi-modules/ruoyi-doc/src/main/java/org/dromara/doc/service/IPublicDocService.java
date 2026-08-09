package org.dromara.doc.service;

import org.dromara.doc.domain.vo.DocFileViewVo;
import org.dromara.doc.domain.vo.DocFileVo;
import org.dromara.doc.domain.vo.DocSearchHitVo;
import org.dromara.doc.domain.vo.DocSourceVo;

import java.util.List;

/**
 * 公开阅读面 Service（匿名可访问，仅暴露 public_visible=1 的来源）
 *
 * @author DocLoom
 */
public interface IPublicDocService {

    /**
     * 列出公开来源
     */
    List<DocSourceVo> listPublicSources();

    /**
     * 某公开来源下的文档（元数据列表，前端构建目录树）
     */
    List<DocFileVo> listFiles(Long sourceId);

    /**
     * 取单个文档渲染体
     */
    DocFileViewVo viewFile(Long id);

    /**
     * 公开检索（ES 不可用时降级 MySQL LIKE；仅检索 public_visible=1 的来源）
     */
    org.dromara.common.mybatis.core.page.TableDataInfo<DocSearchHitVo> search(String kw, Long sourceId, int pageNum, int pageSize);

}
