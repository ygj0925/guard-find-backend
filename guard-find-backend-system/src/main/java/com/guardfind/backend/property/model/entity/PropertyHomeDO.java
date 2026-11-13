package com.guardfind.backend.property.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("gf_property_home")
public class PropertyHomeDO {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String contentJson;
}
