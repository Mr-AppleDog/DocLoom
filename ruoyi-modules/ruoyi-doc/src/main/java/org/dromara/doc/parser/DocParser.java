package org.dromara.doc.parser;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文档解析器
 * <p>
 * M2：md/txt/html（纯 JDK，零依赖）
 * M2b：docx/doc/pdf 等将接入 Apache Tika
 *
 * @author DocLoom
 */
@Component
public class DocParser {

    private static final Set<String> MD_EXTS = Set.of("md", "markdown", "mdx");
    private static final Set<String> TXT_EXTS = Set.of("txt", "text", "log");
    private static final Set<String> HTML_EXTS = Set.of("htm", "html");

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * 解析文件字节
     *
     * @param ext    扩展名（小写无点）
     * @param bytes  原始字节
     * @return 解析结果
     * @throws UnsupportedFormatException 暂不支持的格式
     */
    public ParseResult parse(String ext, byte[] bytes) {
        String e = ext == null ? "" : ext.toLowerCase().replaceFirst("^\\.", "");
        ParseResult r = new ParseResult();

        if (MD_EXTS.contains(e)) {
            String text = decode(bytes);
            r.setRawText(text);
            r.setRawMd(text);
        } else if (TXT_EXTS.contains(e)) {
            r.setRawText(decode(bytes));
        } else if (HTML_EXTS.contains(e)) {
            String text = decode(bytes);
            r.setRenderHtml(text);
            r.setRawText(stripTags(text));
        } else {
            throw new UnsupportedFormatException(e);
        }
        return r;
    }

    private String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        // 去 UTF-8 BOM
        int start = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    private String stripTags(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String text = TAG_PATTERN.matcher(html).replaceAll(" ");
        return MULTI_SPACE_PATTERN.matcher(text).replaceAll(" ").trim();
    }

}
