-- liquibase formatted sql

-- changeset guardfind:backend-property-unification-1
CREATE TABLE gf_miniapp_user (
 id bigint NOT NULL PRIMARY KEY, openid varchar(128) NOT NULL, nickname varchar(64) NOT NULL,
 avatar varchar(1024), phone varchar(20), account_status tinyint NOT NULL DEFAULT 1, is_property_manager tinyint NOT NULL DEFAULT 0,
 create_user bigint, create_time datetime NOT NULL, update_user bigint, update_time datetime, deleted bigint NOT NULL DEFAULT 0,
 UNIQUE KEY uk_openid (openid, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE gf_miniapp_notice (
 id bigint NOT NULL PRIMARY KEY, user_id bigint NOT NULL, business_type varchar(32) NOT NULL, business_id bigint NOT NULL,
 title varchar(100) NOT NULL, content varchar(2000) NOT NULL, is_read tinyint NOT NULL DEFAULT 0, create_time datetime NOT NULL,
 KEY idx_user_time(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS `gf_property` (
    `id`            bigint(20)    AUTO_INCREMENT             COMMENT 'ID',
    `user_id`       bigint(20)    NOT NULL                   COMMENT '房东用户 ID',
    `title`         varchar(100)  NOT NULL                   COMMENT '房源标题',
    `description`   varchar(2000) DEFAULT NULL               COMMENT '房源描述',
    `property_type` varchar(20)   NOT NULL DEFAULT 'house'   COMMENT '类型（house/apartment/villa/shop）',
    `layout`        varchar(30)   DEFAULT NULL               COMMENT '户型（如 2室1厅1卫）',
    `area_sqm`      decimal(8,2)  DEFAULT NULL               COMMENT '面积(㎡)',
    `floor_info`    varchar(20)   DEFAULT NULL               COMMENT '楼层信息（如 5/18）',
    `orientation`   varchar(20)   DEFAULT NULL               COMMENT '朝向（南/北/东南…）',
    `decoration`    varchar(20)   DEFAULT NULL               COMMENT '装修（毛坯/简装/精装/豪装）',
    `price`         decimal(12,2) NOT NULL                   COMMENT '价格（元/月或元）',
    `price_type`    varchar(10)   NOT NULL DEFAULT 'rent'    COMMENT '价格类型（rent=租金/sale=售价）',
    `community` varchar(100) DEFAULT NULL,
    `available_from` date DEFAULT NULL,
    `rental_mode` varchar(10) DEFAULT NULL,
    `address`       varchar(200)  NOT NULL                   COMMENT '详细地址',
    `district`      varchar(50)   DEFAULT NULL               COMMENT '区域/商圈',
    `city`          varchar(50)   DEFAULT NULL               COMMENT '城市',
    `facilities`    varchar(500)  DEFAULT NULL               COMMENT '设施 JSON 数组',
    `contact_name`  varchar(50)   DEFAULT NULL               COMMENT '联系人',
    `contact_phone` varchar(20)   DEFAULT NULL               COMMENT '联系电话',
    `status`        tinyint(4)    NOT NULL DEFAULT 1         COMMENT '状态（1：草稿；2：已上架；3：已下架）',
    `view_count`    int(11)       NOT NULL DEFAULT 0         COMMENT '浏览次数',
    `images`        text DEFAULT NULL               COMMENT '图片 URL JSON 数组（最多 9 张）',
    `create_user`   bigint(20)    DEFAULT NULL               COMMENT '创建人',
    `create_time`   datetime      NOT NULL                   COMMENT '创建时间',
    `update_user`   bigint(20)    DEFAULT NULL               COMMENT '修改人',
    `update_time`   datetime      DEFAULT NULL               COMMENT '修改时间',
    `deleted`       bigint(20)    NOT NULL DEFAULT 0         COMMENT '是否已删除（0：否；id：是）',
    PRIMARY KEY (`id`),
    INDEX `idx_user_status`(`user_id`, `status`),
    INDEX `idx_status_time`(`status`, `create_time`),
    INDEX `idx_city`(`city`),
    INDEX `idx_deleted`(`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='房产房源表';

CREATE TABLE IF NOT EXISTS `gf_property_favorite` (
    `id`           bigint(20) AUTO_INCREMENT   COMMENT 'ID',
    `user_id`      bigint(20)  NOT NULL        COMMENT '收藏用户 ID',
    `property_id`  bigint(20)  NOT NULL        COMMENT '房源 ID',
    `create_time`  datetime    NOT NULL        COMMENT '收藏时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_user_property`(`user_id`, `property_id`),
    INDEX `idx_user_time`(`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='房源收藏表';

CREATE TABLE IF NOT EXISTS `gf_property_view_history` (
    `id`           bigint(20) AUTO_INCREMENT   COMMENT 'ID',
    `user_id`      bigint(20)  NOT NULL        COMMENT '浏览用户 ID',
    `property_id`  bigint(20)  NOT NULL        COMMENT '房源 ID',
    `view_time`    datetime    NOT NULL        COMMENT '最近浏览时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_user_property`(`user_id`, `property_id`),
    INDEX `idx_user_time`(`user_id`, `view_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='房源浏览历史表';

CREATE TABLE IF NOT EXISTS `gf_property_booking` (
    `id`            bigint(20)   AUTO_INCREMENT      COMMENT 'ID',
    `property_id`   bigint(20)   NOT NULL            COMMENT '房源 ID',
    `user_id`       bigint(20)   NOT NULL            COMMENT '预约人用户 ID',
    `owner_id`      bigint(20)   NOT NULL            COMMENT '房东用户 ID',
    `booking_time`  datetime     NOT NULL            COMMENT '预约看房时间',
    `contact_name`  varchar(50)  DEFAULT NULL        COMMENT '联系人',
    `contact_phone` varchar(20)  DEFAULT NULL        COMMENT '联系电话',
    `remark`        varchar(500) DEFAULT NULL        COMMENT '备注',
    `status`        tinyint(4)   NOT NULL DEFAULT 1  COMMENT '状态（1：待确认；2：已确认；3：已拒绝；4：已取消）',
    `create_time`   datetime     NOT NULL            COMMENT '创建时间',
    `update_time`   datetime     DEFAULT NULL        COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_status`(`user_id`, `status`),
    INDEX `idx_owner_status`(`owner_id`, `status`),
    INDEX `idx_property`(`property_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='房源预约看房表';

CREATE TABLE IF NOT EXISTS `gf_im_conversation` (
    `id`            bigint(20)  AUTO_INCREMENT            COMMENT 'ID',
    `user_a_id`     bigint(20)  NOT NULL                  COMMENT '用户 A（user_a < user_b，保证唯一对）',
    `user_b_id`     bigint(20)  NOT NULL                  COMMENT '用户 B',
    `hidden_a` tinyint NOT NULL DEFAULT 0,
    `hidden_b` tinyint NOT NULL DEFAULT 0,
    `last_message`  varchar(200) DEFAULT NULL             COMMENT '最后一条消息摘要',
    `last_time`     datetime    NOT NULL                  COMMENT '最后消息时间',
    `unread_a`      int(11)     NOT NULL DEFAULT 0        COMMENT 'A 未读数（B 发给 A 的未读）',
    `unread_b`      int(11)     NOT NULL DEFAULT 0        COMMENT 'B 未读数（A 发给 B 的未读）',
    `create_user`   bigint(20)  DEFAULT NULL              COMMENT '创建人',
    `create_time`   datetime    NOT NULL                  COMMENT '创建时间',
    `update_user`   bigint(20)  DEFAULT NULL              COMMENT '修改人',
    `update_time`   datetime    DEFAULT NULL              COMMENT '修改时间',
    `deleted`       bigint(20)  NOT NULL DEFAULT 0        COMMENT '是否已删除（0：否；id：是）',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_pair`(`user_a_id`, `user_b_id`, `deleted`),
    INDEX `idx_a_time`(`user_a_id`, `last_time`),
    INDEX `idx_b_time`(`user_b_id`, `last_time`),
    INDEX `idx_deleted`(`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IM 会话表';

CREATE TABLE IF NOT EXISTS `gf_im_message` (
    `id`             bigint(20)   AUTO_INCREMENT           COMMENT 'ID',
    `conversation_id` bigint(20)  NOT NULL                COMMENT '所属会话 ID',
    `sender_id`      bigint(20)   NOT NULL                COMMENT '发送者用户 ID',
    `content`        varchar(2000) NOT NULL               COMMENT '消息内容',
    `is_read`        tinyint(4)   NOT NULL DEFAULT 0      COMMENT '是否已读（0：否；1：是）',
    `create_time`    datetime     NOT NULL                COMMENT '发送时间',
    PRIMARY KEY (`id`),
    INDEX `idx_conv_time`(`conversation_id`, `id`),
    INDEX `idx_sender`(`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IM 消息表';

CREATE TABLE IF NOT EXISTS `gf_admin_message` (
    `id`          bigint(20)    AUTO_INCREMENT        COMMENT 'ID',
    `user_id`     bigint(20)    NOT NULL              COMMENT 'App 用户 ID',
    `sender_type` varchar(10)   NOT NULL              COMMENT '发送方（ADMIN/USER）',
    `admin_id`    bigint(20)    DEFAULT NULL          COMMENT '管理员 ID（sender_type=ADMIN 时）',
    `admin_name`  varchar(64)   DEFAULT NULL          COMMENT '管理员昵称',
    `content`     varchar(2000) NOT NULL              COMMENT '消息内容',
    `is_read`     tinyint(4)    NOT NULL DEFAULT 0    COMMENT '接收方是否已读（0：否；1：是）',
    `create_time` datetime      NOT NULL              COMMENT '发送时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_time`(`user_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台与用户私信表';

CREATE INDEX idx_property_booking_active ON gf_property_booking(property_id,user_id,status);

-- changeset guardfind:backend-property-unification-menus-1
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden) VALUES
(9400,'房源运营',0,1,'/guardfind','Guardfind','Layout','home',NULL,20,1,1,NOW(),b'0',b'0',b'0'),
(9410,'房源管理',9400,2,'/guardfind/property','GuardfindProperty','guardfind/property/index','home',NULL,1,1,1,NOW(),b'0',b'0',b'0'),
(9420,'用户沟通',9400,2,'/guardfind/im','GuardfindIm','guardfind/im/index','message',NULL,2,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time) VALUES (9440,'list',9410,3,'system:property:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time) VALUES (9441,'status',9410,3,'system:property:status',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time) VALUES (9442,'delete',9410,3,'system:property:delete',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time) VALUES (9443,'manager:list',9410,3,'system:property:manager:list',3,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time) VALUES (9444,'manager:set',9410,3,'system:property:manager:set',4,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time) VALUES (9445,'create',9410,3,'system:property:create',5,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time) VALUES (9446,'update',9410,3,'system:property:update',6,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time) VALUES (9447,'booking:list',9410,3,'system:property:booking:list',7,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time) VALUES (9448,'booking:update',9410,3,'system:property:booking:update',8,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time) VALUES (9421,'list',9420,3,'system:im:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time) VALUES (9422,'send',9420,3,'system:im:send',1,1,1,NOW());
