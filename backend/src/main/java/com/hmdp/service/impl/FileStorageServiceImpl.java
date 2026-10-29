package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.service.IFileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件存储服务实现类
 * <p>
 * 演示环境将图片保存到本地磁盘目录（hmdp.upload-dir），
 * 并通过 MvcConfig 中的静态资源映射 /upload/** 对外提供访问。
 * 生产环境应替换为 OSS / 对象存储。
 * </p>
 */
@Slf4j
@Service
public class FileStorageServiceImpl implements IFileStorageService {

    private static final long MAX_ICON_SIZE = 2 * 1024 * 1024; // 2MB
    private static final Set<String> ALLOWED_ICON_EXT = Set.of("jpg", "jpeg", "png", "webp");

    @Value("${hmdp.upload-dir:uploads/icon}")
    private String uploadDirConfig;

    @Override
    public Result storeIcon(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.fail("请选择要上传的图片");
        }
        if (file.getSize() > MAX_ICON_SIZE) {
            return Result.fail("图片大小不能超过 2MB");
        }
        String ext = resolveExt(file.getOriginalFilename());
        if (ext == null || !ALLOWED_ICON_EXT.contains(ext)) {
            return Result.fail("仅支持 jpg/jpeg/png/webp 格式的图片");
        }
        try {
            Path dir = Paths.get(uploadDirConfig).toAbsolutePath();
            Files.createDirectories(dir);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            file.transferTo(dir.resolve(filename).toFile());
            return Result.ok("/upload/icon/" + filename);
        } catch (IOException e) {
            log.error("头像上传保存失败", e);
            return Result.fail("图片保存失败，请稍后重试");
        }
    }

    private String resolveExt(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        // 必须指定 Locale.ROOT：无参 toLowerCase() 走 JVM 默认 locale，
        // 在 tr-TR 下 "I" 会变成 "ı"，导致合法的 .PNG/.JPG 被白名单误拒。
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}