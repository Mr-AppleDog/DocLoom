package org.dromara.doc.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.doc.domain.DocFile;
import org.dromara.doc.domain.DocSource;
import org.dromara.doc.domain.vo.DocFileViewVo;
import org.dromara.doc.domain.vo.DocFileVo;
import org.dromara.doc.domain.vo.DocSearchHitVo;
import org.dromara.doc.domain.vo.DocSourceVo;
import org.dromara.doc.mapper.DocFileMapper;
import org.dromara.doc.mapper.DocSourceMapper;
import org.dromara.doc.service.IDocSearchService;
import org.dromara.doc.service.IPublicDocService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 公开阅读面 Service 业务层处理
 * <p>
 * 匿名访问：用 {@link TenantHelper#ignore} 跨租户查询，仅返回 public_visible=1 的来源及其文档。
 *
 * @author DocLoom
 */
@RequiredArgsConstructor
@Service
public class PublicDocServiceImpl implements IPublicDocService {

    private final DocSourceMapper docSourceMapper;
    private final DocFileMapper docFileMapper;
    private final IDocSearchService docSearchService;

    @Override
    public List<DocSourceVo> listPublicSources() {
        return TenantHelper.ignore(() -> docSourceMapper.selectVoList(
            Wrappers.<DocSource>lambdaQuery()
                .eq(DocSource::getPublicVisible, "1")
                .orderByDesc(DocSource::getCreateTime)));
    }

    @Override
    public List<DocFileVo> listFiles(Long sourceId) {
        return TenantHelper.ignore(() -> {
            if (!isPublic(sourceId)) {
                return Collections.emptyList();
            }
            return docFileMapper.selectVoList(
                Wrappers.<DocFile>lambdaQuery()
                    .eq(DocFile::getSourceId, sourceId)
                    .orderByAsc(DocFile::getPath));
        });
    }

    @Override
    public DocFileViewVo viewFile(Long id) {
        return TenantHelper.ignore(() -> {
            DocFile f = docFileMapper.selectById(id);
            if (f == null) {
                throw new ServiceException("文档不存在");
            }
            if (!isPublic(f.getSourceId())) {
                throw new ServiceException("文档不可访问");
            }
            DocFileViewVo v = new DocFileViewVo();
            v.setId(f.getId());
            v.setSourceId(f.getSourceId());
            v.setPath(f.getPath());
            v.setName(f.getName());
            v.setExt(f.getExt());
            String ext = f.getExt() == null ? "" : f.getExt().toLowerCase();

            if ("md".equals(ext) || "markdown".equals(ext)) {
                v.setType("md");
                v.setRaw(f.getRawMd() != null ? f.getRawMd() : f.getRawText());
            } else if ("html".equals(ext) || "htm".equals(ext)) {
                v.setType("html");
                v.setHtml(f.getRenderHtml() != null ? f.getRenderHtml() : f.getRawText());
            } else if ("txt".equals(ext) || "text".equals(ext) || "log".equals(ext)) {
                v.setType("text");
                v.setRaw(f.getRawText());
            } else {
                v.setType("unsupported");
            }
            return v;
        });
    }

    @Override
    public TableDataInfo<DocSearchHitVo> search(String kw, Long sourceId, int pageNum, int pageSize) {
        return TenantHelper.ignore(() -> {
            // 公开检索仅限 public_visible=1 的来源
            Set<Long> allowed;
            if (sourceId != null) {
                if (!isPublic(sourceId)) {
                    return new TableDataInfo<>(Collections.emptyList(), 0L);
                }
                allowed = Collections.singleton(sourceId);
            } else {
                List<DocSource> pubs = docSourceMapper.selectList(Wrappers.<DocSource>lambdaQuery()
                    .select(DocSource::getId)
                    .eq(DocSource::getPublicVisible, "1"));
                allowed = pubs.stream().map(DocSource::getId).collect(Collectors.toSet());
            }
            if (allowed.isEmpty()) {
                return new TableDataInfo<>(Collections.emptyList(), 0L);
            }
            return docSearchService.search(kw, allowed, pageNum, pageSize);
        });
    }

    /**
     * 来源是否对外公开（需存在且 public_visible=1）
     */
    private boolean isPublic(Long sourceId) {
        if (sourceId == null) {
            return false;
        }
        DocSource s = docSourceMapper.selectById(sourceId);
        return s != null && "1".equals(s.getPublicVisible());
    }

}
