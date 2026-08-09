package org.dromara.doc.parser;

import lombok.Data;

/**
 * 文档解析结果
 *
 * @author DocLoom
 */
@Data
public class ParseResult {

    /**
     * 抽取正文（检索源）
     */
    private String rawText;

    /**
     * md 原文（前端 markdown-it 渲染）
     */
    private String rawMd;

    /**
     * 渲染 HTML（Office/PDF/html；md 不存）
     */
    private String renderHtml;

}
