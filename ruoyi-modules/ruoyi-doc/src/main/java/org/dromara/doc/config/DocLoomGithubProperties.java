package org.dromara.doc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * DocLoom GitHub 相关配置
 *
 * @author DocLoom
 */
@Data
@ConfigurationProperties(prefix = "docloom.github")
public class DocLoomGithubProperties {

    /**
     * 全局兜底 PAT（来源级 token 为空时使用）
     */
    private String defaultToken;

    /**
     * 连接超时
     */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * 读取超时
     */
    private Duration readTimeout = Duration.ofSeconds(30);

    /**
     * 失败最大重试次数（M2 同步使用）
     */
    private int maxRetries = 3;

}
