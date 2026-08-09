package org.dromara.doc.client;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.doc.config.DocLoomGithubProperties;
import org.dromara.doc.domain.vo.DocSourceTestVo;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GitHub REST API 客户端（基于 Hutool HttpUtil）
 * <p>
 * 连通性校验、文件枚举(trees recursive)、原始文件下载(raw)
 *
 * @author DocLoom
 */
@Component
@RequiredArgsConstructor
public class GithubClient {

    private static final String API_BASE = "https://api.github.com";
    private static final String RAW_BASE = "https://raw.githubusercontent.com";
    private static final String ACCEPT = "application/vnd.github+json";
    private static final String USER_AGENT = "DocLoom";

    private final DocLoomGithubProperties props;

    /**
     * 连通性校验：枚举仓库树(递归)，按 path 前缀与扩展名白名单统计候选文件数
     */
    public DocSourceTestVo testSource(String owner, String repo, String branch,
                                      String path, String fileExts, String token) {
        DocSourceTestVo vo = new DocSourceTestVo();
        vo.setReachable(false);
        vo.setFileCount(0);
        vo.setTruncated(false);
        try {
            if (StringUtils.isBlank(branch)) {
                branch = fetchDefaultBranch(owner, repo, token);
            }
            vo.setBranch(branch);
            List<GhTreeEntry> entries = listFiles(owner, repo, branch, path, fileExts, token, vo::setTruncated);
            vo.setReachable(true);
            vo.setFileCount(entries.size());
            vo.setMessage(Boolean.TRUE.equals(vo.getTruncated()) ? "仓库较大，树已被 GitHub 截断，仅统计部分文件" : "ok");
        } catch (ServiceException e) {
            vo.setMessage(e.getMessage());
        } catch (Exception e) {
            vo.setMessage("GitHub 请求异常：" + e.getMessage());
        }
        return vo;
    }

    /**
     * 枚举仓库递归树，按 path 前缀 + 扩展名白名单过滤，返回候选文件列表（含 sha/size/ext）
     */
    public List<GhTreeEntry> listFiles(String owner, String repo, String branch, String path,
                                       String fileExts, String token, java.util.function.Consumer<Boolean> truncatedConsumer) {
        String url = String.format("%s/repos/%s/%s/git/trees/%s?recursive=1", API_BASE, owner, repo, branch);
        JSONObject resp = getJson(url, token);
        boolean truncated = resp.getBool("truncated", false);
        if (truncatedConsumer != null) {
            truncatedConsumer.accept(truncated);
        }
        JSONArray tree = resp.getJSONArray("tree");
        Set<String> exts = parseExts(fileExts);
        String prefix = normalizePath(path);
        List<GhTreeEntry> result = new ArrayList<>();
        if (tree != null) {
            for (Object o : tree) {
                JSONObject item = (JSONObject) o;
                if (!"blob".equals(item.getStr("type"))) {
                    continue;
                }
                String p = item.getStr("path");
                if (!matchPath(p, prefix)) {
                    continue;
                }
                String ext = FileUtil.extName(p);
                if (ext == null) {
                    continue;
                }
                if (!exts.isEmpty() && !exts.contains(ext.toLowerCase())) {
                    continue;
                }
                GhTreeEntry entry = new GhTreeEntry();
                entry.setPath(p);
                entry.setSha(item.getStr("sha"));
                entry.setSize(item.getLong("size"));
                entry.setExt(ext.toLowerCase());
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 下载原始文件字节
     */
    public byte[] downloadRaw(String owner, String repo, String branch, String path, String token) {
        String encodedPath = encodePath(path);
        String url = String.format("%s/%s/%s/%s/%s", RAW_BASE, owner, repo, branch, encodedPath);
        HttpRequest req = HttpRequest.get(url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", USER_AGENT)
            .timeout(timeoutMs());
        String auth = resolveToken(token);
        if (StringUtils.isNotBlank(auth)) {
            req.header("Authorization", "Bearer " + auth);
        }
        HttpResponse res = req.execute();
        int status = res.getStatus();
        if (status == 404) {
            throw new ServiceException("文件不存在：" + path);
        }
        if (status < 200 || status >= 300) {
            throw new ServiceException("下载失败(" + status + ")：" + path);
        }
        return res.bodyBytes();
    }

    /**
     * 获取仓库默认分支
     */
    public String fetchDefaultBranch(String owner, String repo, String token) {
        String url = String.format("%s/repos/%s/%s", API_BASE, owner, repo);
        return getJson(url, token).getStr("default_branch");
    }

    private JSONObject getJson(String url, String token) {
        HttpRequest req = HttpRequest.get(url)
            .header("Accept", ACCEPT)
            .header("User-Agent", USER_AGENT)
            .timeout(timeoutMs());
        String auth = resolveToken(token);
        if (StringUtils.isNotBlank(auth)) {
            req.header("Authorization", "Bearer " + auth);
        }
        HttpResponse res = req.execute();
        int status = res.getStatus();
        String body = res.body();
        if (status == 401 || status == 403) {
            throw new ServiceException("GitHub 鉴权失败：token 无效或权限不足(" + status + ")");
        }
        if (status == 404) {
            throw new ServiceException("仓库或分支不存在：" + url);
        }
        if (status < 200 || status >= 300) {
            String snippet = body == null ? "" : (body.length() > 200 ? body.substring(0, 200) : body);
            throw new ServiceException("GitHub 请求失败(" + status + ")：" + snippet);
        }
        return JSONUtil.parseObj(body);
    }

    private String resolveToken(String perSource) {
        return StringUtils.isNotBlank(perSource) ? perSource : props.getDefaultToken();
    }

    private int timeoutMs() {
        long c = props.getConnectTimeout() == null ? 10000 : props.getConnectTimeout().toMillis();
        long r = props.getReadTimeout() == null ? 30000 : props.getReadTimeout().toMillis();
        return (int) Math.max(c, r);
    }

    private Set<String> parseExts(String fileExts) {
        if (StringUtils.isBlank(fileExts)) {
            return new HashSet<>();
        }
        return Arrays.stream(fileExts.split(","))
            .map(String::trim)
            .filter(StringUtils::isNotBlank)
            .map(s -> s.toLowerCase().replaceFirst("^\\.", ""))
            .collect(Collectors.toSet());
    }

    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        return path.trim().replaceAll("^/+|/+$", "");
    }

    private boolean matchPath(String entryPath, String prefix) {
        if (StringUtils.isBlank(prefix)) {
            return true;
        }
        return entryPath != null && entryPath.startsWith(prefix + "/");
    }

    /**
     * URL 编码路径段（保留 /，空格→%20）
     */
    private String encodePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        return URLEncoder.encode(path, StandardCharsets.UTF_8).replace("%2F", "/").replace("+", "%20");
    }

}
