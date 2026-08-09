package org.dromara.doc.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文档渲染视图对象（公开阅读面 /view 返回）
 *
 * @author DocLoom
 */
@Data
public class DocFileViewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long sourceId;
    private String path;
    private String name;
    private String ext;

    /**
     * 渲染类型：md | html | text | unsupported
     */
    private String type;

    /**
     * md / text 原文（前端渲染或 pre 展示）
     */
    private String raw;

    /**
     * html 渲染体（office/pdf 已转 html）
     */
    private String html;

}
