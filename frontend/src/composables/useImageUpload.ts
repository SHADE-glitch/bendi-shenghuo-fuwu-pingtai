import { ref } from 'vue';
import { showToast } from 'vant';
import { uploadService } from '@/api/upload';

const ACCEPTED_TYPES = /^image\/(jpeg|png|webp)$/i;
const MAX_SIZE = 2 * 1024 * 1024;

/**
 * 图片上传：统一格式/大小校验与头像上传逻辑。
 */
export function useImageUpload() {
  const uploading = ref(false);
  const url = ref<string | null>(null);

  /** 校验图片，返回错误文案（通过则返回 null） */
  const validate = (file: File): string | null => {
    if (!ACCEPTED_TYPES.test(file.type)) return '仅支持 jpg/png/webp 格式的图片';
    if (file.size > MAX_SIZE) return '图片大小不能超过 2MB';
    return null;
  };

  /** 上传图片，成功返回远端 URL，失败返回 null */
  const upload = async (file: File): Promise<string | null> => {
    const error = validate(file);
    if (error) {
      showToast(error);
      return null;
    }
    uploading.value = true;
    try {
      const res = await uploadService.uploadIcon(file);
      const result = res.data.success && res.data.data ? res.data.data : null;
      url.value = result;
      return result;
    } catch (err) {
      showToast((err as { errorMsg?: string })?.errorMsg || '头像上传失败，请重试');
      return null;
    } finally {
      uploading.value = false;
    }
  };

  const reset = () => {
    url.value = null;
  };

  return { uploading, url, upload, validate, reset };
}
