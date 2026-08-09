-- ----------------------------
-- DocLoom 文档托管 M2：doc_file 表 + doc_source 增 last_sync_msg
-- 适用 MySQL 8.x（与 ry_vue_5.X.sql 同库 doc-loom）
-- ----------------------------

DROP TABLE IF EXISTS `doc_file`;
CREATE TABLE `doc_file`
(
    `id`                bigint(0)     NOT NULL COMMENT '主键',
    `tenant_id`         varchar(20)   NULL DEFAULT '000000' COMMENT '租户编号',
    `source_id`         bigint(0)     NOT NULL COMMENT '文档来源id',
    `path`              varchar(1024) NOT NULL COMMENT '仓库内相对路径',
    `name`              varchar(255)  NULL DEFAULT NULL COMMENT '文件名',
    `ext`               varchar(16)   NULL DEFAULT NULL COMMENT '扩展名',
    `sha`               varchar(64)   NULL DEFAULT NULL COMMENT 'GitHub blob sha',
    `size`              bigint(0)     NULL DEFAULT 0 COMMENT '字节',
    `raw_text`          MEDIUMTEXT    NULL DEFAULT NULL COMMENT '抽取正文（检索源）',
    `render_html`       LONGTEXT      NULL DEFAULT NULL COMMENT 'Office/PDF 渲染HTML（md不存）',
    `raw_md`            MEDIUMTEXT    NULL DEFAULT NULL COMMENT 'md原文（前端渲染）',
    `content_updated_at` datetime(0) NULL DEFAULT NULL COMMENT '仓库内文件修改时间',
    `sync_time`         datetime(0)   NULL DEFAULT NULL COMMENT '本地同步时间',
    `create_dept`       bigint(0)     NULL DEFAULT NULL COMMENT '创建部门',
    `create_time`       datetime(0)   NULL DEFAULT NULL COMMENT '创建时间',
    `create_by`         bigint(0)     NULL DEFAULT NULL COMMENT '创建人',
    `update_time`       datetime(0)   NULL DEFAULT NULL COMMENT '更新时间',
    `update_by`         bigint(0)     NULL DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_source_path` (`tenant_id`, `source_id`, `path`(255)) USING BTREE,
    KEY `idx_source_id` (`source_id`) USING BTREE
) ENGINE = InnoDB COMMENT = '同步文档';

ALTER TABLE `doc_source` ADD COLUMN `last_sync_msg` varchar(500) NULL DEFAULT NULL COMMENT '最近同步信息' AFTER `last_sync_time`;

-- ----------------------------
-- M2 菜单：触发同步 按钮
-- ----------------------------
INSERT INTO sys_menu VALUES ('2007', '触发同步', '2001', '6', '', '', '', 1, 0, 'F', '0', '0', 'doc:source:sync', '#', 103, 1, sysdate(), NULL, NULL, '');
INSERT INTO sys_role_menu VALUES ('1', '2007');

