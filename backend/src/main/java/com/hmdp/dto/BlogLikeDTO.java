package com.hmdp.dto;

import lombok.Data;

/**
 * 笔记点赞结果 DTO
 */
@Data
public class BlogLikeDTO {

    /** 当前用户是否已点赞（true=已赞，false=已取消） */
    private Boolean liked;

    /** 笔记当前点赞总数 */
    private Integer likedCount;
}
