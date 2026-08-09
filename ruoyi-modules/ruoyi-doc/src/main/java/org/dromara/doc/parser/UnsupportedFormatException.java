package org.dromara.doc.parser;

/**
 * 暂不支持的文档格式（M2b 将接入 Tika 处理 docx/doc/pdf 等）
 *
 * @author DocLoom
 */
public class UnsupportedFormatException extends RuntimeException {

    private final String ext;

    public UnsupportedFormatException(String ext) {
        super("暂不支持格式：" + ext + "（需 M2b Tika）");
        this.ext = ext;
    }

    public String getExt() {
        return ext;
    }

}
