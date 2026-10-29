package com.hmdp.utils;

/**
 * 系统常量定义
 * <p>
 * 集中管理项目中使用的业务常量，包括图片上传目录、用户昵称前缀、
 * 分页默认大小等配置值。
 * </p>
 */
public class SystemConstants {
    /**
     * 图片上传目录（服务器本地绝对路径）
     */
    public static final String IMAGE_UPLOAD_DIR = "/var/www/html/hmdp/imgs";

    /**
     * 用户默认昵称前缀，用于未填写昵称时自动生成
     */
    public static final String USER_NICK_NAME_PREFIX = "user_";

    /**
     * 默认每页条数
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * 最大每页条数（分页上限）
     */
    public static final int MAX_PAGE_SIZE = 10;
}
