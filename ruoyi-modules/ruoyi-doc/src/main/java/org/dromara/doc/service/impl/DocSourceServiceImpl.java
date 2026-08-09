package org.dromara.doc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.doc.client.GithubClient;
import org.dromara.doc.domain.DocSource;
import org.dromara.doc.domain.bo.DocSourceBo;
import org.dromara.doc.domain.vo.DocSourceTestVo;
import org.dromara.doc.domain.vo.DocSourceVo;
import org.dromara.doc.mapper.DocSourceMapper;
import org.dromara.doc.service.IDocSourceService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 文档来源 Service 业务层处理
 *
 * @author DocLoom
 */
@RequiredArgsConstructor
@Service
public class DocSourceServiceImpl implements IDocSourceService {

    private final DocSourceMapper baseMapper;
    private final GithubClient githubClient;

    @Override
    public DocSourceVo queryById(Long id) {
        return baseMapper.selectVoById(id);
    }

    @Override
    public TableDataInfo<DocSourceVo> queryPageList(DocSourceBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<DocSource> lqw = buildQueryWrapper(bo);
        Page<DocSourceVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    @Override
    public List<DocSourceVo> queryList(DocSourceBo bo) {
        return baseMapper.selectVoList(buildQueryWrapper(bo));
    }

    private LambdaQueryWrapper<DocSource> buildQueryWrapper(DocSourceBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<DocSource> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getName()), DocSource::getName, bo.getName());
        lqw.eq(StringUtils.isNotBlank(bo.getOwner()), DocSource::getOwner, bo.getOwner());
        lqw.like(StringUtils.isNotBlank(bo.getRepo()), DocSource::getRepo, bo.getRepo());
        lqw.eq(StringUtils.isNotBlank(bo.getPublicVisible()), DocSource::getPublicVisible, bo.getPublicVisible());
        lqw.eq(StringUtils.isNotBlank(bo.getSyncMode()), DocSource::getSyncMode, bo.getSyncMode());
        lqw.between(params.get("beginLastSyncTime") != null && params.get("endLastSyncTime") != null,
            DocSource::getLastSyncTime, params.get("beginLastSyncTime"), params.get("endLastSyncTime"));
        lqw.orderByDesc(DocSource::getCreateTime);
        return lqw;
    }

    @Override
    public Boolean insertByBo(DocSourceBo bo) {
        DocSource add = MapstructUtils.convert(bo, DocSource.class);
        applyDefaults(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    public Boolean updateByBo(DocSourceBo bo) {
        DocSource update = MapstructUtils.convert(bo, DocSource.class);
        // 编辑时若未重新填写 token，则保留原密钥，避免被置空
        if (StringUtils.isBlank(update.getGithubToken())) {
            DocSource existing = baseMapper.selectById(update.getId());
            if (existing != null) {
                update.setGithubToken(existing.getGithubToken());
            }
        }
        return baseMapper.updateById(update) > 0;
    }

    /**
     * 填充默认值
     */
    private void applyDefaults(DocSource entity) {
        if (StringUtils.isBlank(entity.getBranch())) {
            entity.setBranch("main");
        }
        if (StringUtils.isBlank(entity.getFileExts())) {
            entity.setFileExts("md,docx,doc,pdf,txt");
        }
        if (entity.getMaxFileSize() == null) {
            entity.setMaxFileSize(10240);
        }
        if (StringUtils.isBlank(entity.getPublicVisible())) {
            entity.setPublicVisible("0");
        }
        if (StringUtils.isBlank(entity.getSyncMode())) {
            entity.setSyncMode("0");
        }
        if (StringUtils.isBlank(entity.getLastSyncStatus())) {
            entity.setLastSyncStatus("0");
        }
        if (entity.getFileCount() == null) {
            entity.setFileCount(0);
        }
    }

    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (isValid) {
            List<DocSource> list = baseMapper.selectByIds(ids);
            if (list.size() != ids.size()) {
                throw new ServiceException("您没有删除权限!");
            }
        }
        // TODO M2：删除来源时级联清理 doc_file 与 ES 索引
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    public DocSourceTestVo testConnectivity(DocSourceBo bo) {
        return githubClient.testSource(bo.getOwner(), bo.getRepo(), bo.getBranch(),
            bo.getPath(), bo.getFileExts(), bo.getGithubToken());
    }

}
