package com.hmdp.dto;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 浏览历史列表项 DTO
 */
@Data
public class BrowseHistoryDTO {

    /** 记录id */
    private Long id;

    /** 目标类型：1-店铺 2-笔记 */
    private Integer targetType;

    /** 目标id */
    private Long targetId;

    /** 目标标题（店铺名 / 笔记标题） */
    private String title;

    /** 目标首图 */
    private String image;

    /** 最近浏览时间 */
    private Timestamp createTime;
}
