package com.hmdp.service;

import com.hmdp.dto.Result;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务接口
 * <p>提供图片上传与本地磁盘存储能力，返回可供外部访问的相对 URL 路径。</p>
 */
public interface IFileStorageService {

    /**
     * 保存头像图片到本地磁盘
     *
     * @param file 上传的图片文件
     * @return 包含可访问 URL 的结果
     */
    Result storeIcon(MultipartFile file);
}