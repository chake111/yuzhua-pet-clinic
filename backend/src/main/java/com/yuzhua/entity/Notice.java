package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yuzhua.domain.enums.NoticeStatus;
import com.yuzhua.domain.enums.NoticeType;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

@Data
@TableName("notice")
public class Notice {

    @TableId(type = IdType.AUTO)
    private @Nullable Long id;

    private @Nullable String title;
    private @Nullable String cover;
    private @Nullable String summary;
    private @Nullable String content;

    private @Nullable NoticeType type;
    private @Nullable Integer topFlag;
    private @Nullable NoticeStatus status;
    private @Nullable Integer viewCount;
    private @Nullable LocalDateTime publishTime;
    private @Nullable Long publishBy;

    @TableField(fill = FieldFill.INSERT)
    private @Nullable LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private @Nullable LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private @Nullable Integer deleted;
}
