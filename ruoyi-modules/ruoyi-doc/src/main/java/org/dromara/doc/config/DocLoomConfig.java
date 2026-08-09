package org.dromara.doc.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * DocLoom 文档托管自动配置
 *
 * @author DocLoom
 */
@Configuration
@EnableConfigurationProperties({DocLoomGithubProperties.class, DocLoomEsProperties.class})
public class DocLoomConfig {

}
