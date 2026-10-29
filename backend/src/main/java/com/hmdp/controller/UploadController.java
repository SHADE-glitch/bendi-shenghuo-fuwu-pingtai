package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IFileStorageService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;

/**
 * 文件上传接口
 * <p>
 * 图片校验、存储与访问路径生成由 {@link IFileStorageService} 承载，
 * 演示环境保存到本地磁盘并通过 MvcConfig 静态资源映射 /upload/** 对外提供访问。
 * </p>
 */
@RestController
@RequestMapping("/upload")
public class UploadController {

    @Resource
    private IFileStorageService fileStorageService;

    /**
     * 上传头像图标
     *
     * @param file 上传的图片文件
     * @return 包含可访问 URL 的结果
     */
    @PostMapping("/icon")
    public Result uploadIcon(@RequestParam("file") MultipartFile file) {
        return fileStorageService.storeIcon(file);
    }
}