package org.dromara.doc.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.doc.domain.bo.DocSourceBo;
import org.dromara.doc.domain.vo.DocSourceTestVo;
import org.dromara.doc.domain.vo.DocSourceVo;

import java.util.Collection;
import java.util.List;

/**
 * 文档来源 Service 接口
 *
 * @author DocLoom
 */
public interface IDocSourceService {

    /**
     * 查询单个
     */
    DocSourceVo queryById(Long id);

    /**
     * 分页查询
     */
    TableDataInfo<DocSourceVo> queryPageList(DocSourceBo bo, PageQuery pageQuery);

    /**
     * 查询列表
     */
    List<DocSourceVo> queryList(DocSourceBo bo);

    /**
     * 新增
     */
    Boolean insertByBo(DocSourceBo bo);

    /**
     * 修改
     */
    Boolean updateByBo(DocSourceBo bo);

    /**
     * 校验并删除
     *
     * @param ids     主键集合
     * @param isValid 是否删除前校验
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 连通性校验：校验仓库/分支/路径可达并统计候选文件数
     */
    DocSourceTestVo testConnectivity(DocSourceBo bo);

}
