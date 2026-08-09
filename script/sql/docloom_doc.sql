-- ----------------------------
-- DocLoom 文档托管：doc_source 表 + 菜单/权限
-- 适用 MySQL 8.x（与 ry_vue_5.X.sql 同库）
-- ----------------------------

DROP TABLE IF EXISTS `doc_source`;
CREATE TABLE `doc_source`
(
    `id`               bigint(0)     NOT NULL COMMENT '主键',
    `tenant_id`        varchar(20)    NULL DEFAULT '000000' COMMENT '租户编号',
    `name`             varchar(128)   NOT NULL COMMENT '来源名称',
    `owner`            varchar(64)   NOT NULL COMMENT 'GitHub owner',
    `repo`             varchar(64)   NOT NULL COMMENT '仓库名',
    `branch`           varchar(64)   NULL DEFAULT 'main' COMMENT '分支',
    `path`             varchar(512)   NULL DEFAULT '' COMMENT '仓库内子路径，空表示根目录',
    `github_token`     varchar(512)   NULL DEFAULT NULL COMMENT 'GitHub PAT（加密存储）',
    `file_exts`        varchar(256)   NULL DEFAULT 'md,docx,doc,pdf,txt' COMMENT '允许的文件扩展名，逗号分隔',
    `max_file_size`    int(0)         NULL DEFAULT 10240 COMMENT '单文件大小上限（KB）',
    `public_visible`   char(1)        NULL DEFAULT '0' COMMENT '是否对外公开 0否 1是',
    `sync_mode`        char(1)        NULL DEFAULT '0' COMMENT '同步方式 0手动 1定时',
    `sync_cron`        varchar(64)    NULL DEFAULT NULL COMMENT '定时同步表达式',
    `last_sync_status` char(1)        NULL DEFAULT '0' COMMENT '最近一次同步状态 0失败 1成功 2进行中',
    `last_sync_time`   datetime(0)    NULL DEFAULT NULL COMMENT '最近一次同步时间',
    `file_count`       int(0)         NULL DEFAULT 0 COMMENT '已同步文件数',
    `remark`           varchar(500)   NULL DEFAULT NULL COMMENT '备注',
    `version`          int(0)         NULL DEFAULT 0 COMMENT '版本（乐观锁）',
    `create_dept`      bigint(0)      NULL DEFAULT NULL COMMENT '创建部门',
    `create_time`      datetime(0)    NULL DEFAULT NULL COMMENT '创建时间',
    `create_by`        bigint(0)      NULL DEFAULT NULL COMMENT '创建人',
    `update_time`      datetime(0)    NULL DEFAULT NULL COMMENT '更新时间',
    `update_by`        bigint(0)      NULL DEFAULT NULL COMMENT '更新人',
    `del_flag`         int(0)         NULL DEFAULT 0 COMMENT '删除标志 0存在 2删除',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB COMMENT = '文档来源';

-- ----------------------------
-- 菜单：文档托管 目录 + 来源管理 + 按钮权限
-- 列序：menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark
-- ----------------------------

-- 目录
INSERT INTO sys_menu VALUES ('2000', '文档托管', '0', '6', 'doc',    NULL,               '', 1, 0, 'M', '0', '0', '',                'documentation', 103, 1, sysdate(), NULL, NULL, '文档托管目录');

-- 菜单
INSERT INTO sys_menu VALUES ('2001', '来源管理', '2000', '1', 'source', 'doc/source/index', '', 1, 0, 'C', '0', '0', 'doc:source:list', 'tree-table',    103, 1, sysdate(), NULL, NULL, 'GitHub 仓库来源管理');

-- 按钮
INSERT INTO sys_menu VALUES ('2002', '来源查询', '2001', '1', '', '', '', 1, 0, 'F', '0', '0', 'doc:source:query', '#', 103, 1, sysdate(), NULL, NULL, '');
INSERT INTO sys_menu VALUES ('2003', '来源新增', '2001', '2', '', '', '', 1, 0, 'F', '0', '0', 'doc:source:add',   '#', 103, 1, sysdate(), NULL, NULL, '');
INSERT INTO sys_menu VALUES ('2004', '来源修改', '2001', '3', '', '', '', 1, 0, 'F', '0', '0', 'doc:source:edit',  '#', 103, 1, sysdate(), NULL, NULL, '');
INSERT INTO sys_menu VALUES ('2005', '来源删除', '2001', '4', '', '', '', 1, 0, 'F', '0', '0', 'doc:source:remove','#', 103, 1, sysdate(), NULL, NULL, '');
INSERT INTO sys_menu VALUES ('2006', '连通性测试','2001', '5', '', '', '', 1, 0, 'F', '0', '0', 'doc:source:test',  '#', 103, 1, sysdate(), NULL, NULL, '');

-- ----------------------------
-- 角色授权：超级管理员(role_id=1)
-- ----------------------------
INSERT INTO sys_role_menu VALUES ('1', '2000');
INSERT INTO sys_role_menu VALUES ('1', '2001');
INSERT INTO sys_role_menu VALUES ('1', '2002');
INSERT INTO sys_role_menu VALUES ('1', '2003');
INSERT INTO sys_role_menu VALUES ('1', '2004');
INSERT INTO sys_role_menu VALUES ('1', '2005');
INSERT INTO sys_role_menu VALUES ('1', '2006');
