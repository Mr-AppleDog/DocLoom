package org.dromara.doc.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.doc.client.GhTreeEntry;
import org.dromara.doc.client.GithubClient;
import org.dromara.doc.domain.DocFile;
import org.dromara.doc.domain.DocSource;
import org.dromara.doc.domain.vo.DocSourceVo;
import org.dromara.doc.mapper.DocFileMapper;
import org.dromara.doc.mapper.DocSourceMapper;
import org.dromara.doc.parser.DocParser;
import org.dromara.doc.parser.ParseResult;
import org.dromara.doc.parser.UnsupportedFormatException;
import org.dromara.doc.service.IDocSearchService;
import org.dromara.doc.service.IDocSyncService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档同步 Service 业务层处理（同步执行）
 * <p>
 * M4：落库后双写 ES 索引、删除时同步删 ES（best-effort，不影响同步主流程）。
 *
 * @author DocLoom
 */
@RequiredArgsConstructor
@Service
public class DocSyncServiceImpl implements IDocSyncService {

    private final DocSourceMapper docSourceMapper;
    private final DocFileMapper docFileMapper;
    private final GithubClient githubClient;
    private final DocParser docParser;
    private final IDocSearchService docSearchService;

    /**
     * 进行中的来源，防止重复触发
     */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    @Override
    public DocSourceVo sync(Long sourceId) {
        if (running.contains(sourceId)) {
            throw new ServiceException("该来源正在同步中，请稍后再试");
        }
        DocSource source = docSourceMapper.selectById(sourceId);
        if (source == null) {
            throw new ServiceException("文档来源不存在");
        }
        running.add(sourceId);
        try {
            return doSync(source);
        } finally {
            running.remove(sourceId);
        }
    }

    private DocSourceVo doSync(DocSource source) {
        Date now = new Date();
        Long sourceId = source.getId();
        try {
            updateStatus(sourceId, "2", now, "同步中");

            String token = source.getGithubToken();
            String branch = StringUtils.isBlank(source.getBranch())
                ? githubClient.fetchDefaultBranch(source.getOwner(), source.getRepo(), token)
                : source.getBranch();
            List<GhTreeEntry> entries = githubClient.listFiles(
                source.getOwner(), source.getRepo(), branch, source.getPath(),
                source.getFileExts(), token, null);

            long maxBytes = (source.getMaxFileSize() == null ? 10240 : source.getMaxFileSize()) * 1024L;
            Set<String> remotePaths = new HashSet<>();
            int synced = 0, skipped = 0;
            Set<String> unsupportedExts = new TreeSet<>();

            for (GhTreeEntry entry : entries) {
                remotePaths.add(entry.getPath());
                try {
                    DocFile existing = docFileMapper.selectOne(Wrappers.<DocFile>lambdaQuery()
                        .select(DocFile::getId, DocFile::getPath, DocFile::getSha)
                        .eq(DocFile::getSourceId, sourceId)
                        .eq(DocFile::getPath, entry.getPath()));
                    // sha 未变则跳过下载
                    if (existing != null && entry.getSha() != null && entry.getSha().equals(existing.getSha())) {
                        continue;
                    }
                    byte[] bytes = githubClient.downloadRaw(source.getOwner(), source.getRepo(),
                        branch, entry.getPath(), token);
                    if (bytes.length > maxBytes) {
                        skipped++;
                        continue;
                    }
                    ParseResult parse = docParser.parse(entry.getExt(), bytes);
                    long size = bytes.length;
                    Long docId;
                    if (existing != null) {
                        docId = existing.getId();
                        docFileMapper.update(null, Wrappers.<DocFile>lambdaUpdate()
                            .set(DocFile::getSha, entry.getSha())
                            .set(DocFile::getSize, size)
                            .set(DocFile::getRawText, parse.getRawText())
                            .set(DocFile::getRawMd, parse.getRawMd())
                            .set(DocFile::getRenderHtml, parse.getRenderHtml())
                            .set(DocFile::getSyncTime, now)
                            .eq(DocFile::getId, existing.getId()));
                    } else {
                        DocFile f = new DocFile();
                        f.setSourceId(sourceId);
                        f.setPath(entry.getPath());
                        f.setName(nameFromPath(entry.getPath()));
                        f.setExt(entry.getExt());
                        f.setSha(entry.getSha());
                        f.setSize(size);
                        f.setRawText(parse.getRawText());
                        f.setRawMd(parse.getRawMd());
                        f.setRenderHtml(parse.getRenderHtml());
                        f.setSyncTime(now);
                        docFileMapper.insert(f);
                        docId = f.getId();
                    }
                    // 双写 ES（best-effort，失败不影响同步）
                    DocFile toIndex = new DocFile();
                    toIndex.setId(docId);
                    toIndex.setSourceId(sourceId);
                    toIndex.setPath(entry.getPath());
                    toIndex.setName(nameFromPath(entry.getPath()));
                    toIndex.setExt(entry.getExt());
                    toIndex.setSha(entry.getSha());
                    toIndex.setSize(size);
                    toIndex.setRawText(parse.getRawText());
                    toIndex.setRawMd(parse.getRawMd());
                    toIndex.setRenderHtml(parse.getRenderHtml());
                    toIndex.setSyncTime(now);
                    docSearchService.indexFile(toIndex, source.getName());
                    synced++;
                } catch (UnsupportedFormatException ufe) {
                    unsupportedExts.add(ufe.getExt());
                    skipped++;
                } catch (Exception e) {
                    skipped++;
                }
            }

            // 删除远端已不存在的本地文件（物理删，DocFile 无逻辑删除）
            List<DocFile> local = docFileMapper.selectList(Wrappers.<DocFile>lambdaQuery()
                .select(DocFile::getId, DocFile::getPath)
                .eq(DocFile::getSourceId, sourceId));
            List<Long> toDelete = local.stream()
                .filter(f -> !remotePaths.contains(f.getPath()))
                .map(DocFile::getId)
                .toList();
            if (!toDelete.isEmpty()) {
                docFileMapper.deleteByIds(toDelete);
                // 同步删除 ES 索引（best-effort）
                docSearchService.deleteFiles(toDelete);
            }

            long count = docFileMapper.selectCount(Wrappers.<DocFile>lambdaQuery().eq(DocFile::getSourceId, sourceId));

            StringBuilder msg = new StringBuilder("成功同步 ").append(synced).append(" 个");
            if (skipped > 0) {
                msg.append("，跳过 ").append(skipped).append(" 个");
            }
            if (!unsupportedExts.isEmpty()) {
                msg.append("，不支持格式：").append(String.join(",", unsupportedExts));
            }
            updateStatus(sourceId, "1", now, msg.toString());
            updateCount(sourceId, (int) count);
            return docSourceMapper.selectVoById(sourceId);
        } catch (Exception e) {
            updateStatus(sourceId, "0", now, "同步失败：" + e.getMessage());
            return docSourceMapper.selectVoById(sourceId);
        }
    }

    private void updateStatus(Long sourceId, String status, Date time, String msg) {
        docSourceMapper.update(null, Wrappers.<DocSource>lambdaUpdate()
            .set(DocSource::getLastSyncStatus, status)
            .set(DocSource::getLastSyncTime, time)
            .set(DocSource::getLastSyncMsg, msg)
            .eq(DocSource::getId, sourceId));
    }

    private void updateCount(Long sourceId, int count) {
        docSourceMapper.update(null, Wrappers.<DocSource>lambdaUpdate()
            .set(DocSource::getFileCount, count)
            .eq(DocSource::getId, sourceId));
    }

    private String nameFromPath(String path) {
        if (path == null) {
            return null;
        }
        int idx = path.lastIndexOf('/');
        return idx < 0 ? path : path.substring(idx + 1);
    }

}
