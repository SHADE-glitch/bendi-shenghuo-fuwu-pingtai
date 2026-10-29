import client from './client';
import { Result } from './types';

/**
 * 对应后端 UploadController
 */
export const uploadService = {
  /**
   * 上传头像图片
   * POST /upload/icon
   */
  uploadIcon(file: File) {
    const fd = new FormData();
    fd.append('file', file);
    return client.post<Result<string>>('/upload/icon', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
};