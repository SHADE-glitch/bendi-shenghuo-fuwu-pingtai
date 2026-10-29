<script setup lang="ts">
import { ref, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { showToast } from 'vant';
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query';
import { UpdateUserInfoDTO } from '@/api/types';
import { useUserStore } from '@/store/user';
import { userService } from '@/api/user';
import { uploadService } from '@/api/upload';

const router = useRouter();
const userStore = useUserStore();
const queryClient = useQueryClient();

const form = reactive<UpdateUserInfoDTO>({
  nickName: '',
  icon: '',
  gender: false,
  city: '',
  birthday: '',
  introduce: '',
});

const showGenderSheet = ref(false);
const genderOptions = [
  { name: '男', value: false },
  { name: '女', value: true },
];

// 1. 获取基本信息 (User) 和 详细信息 (UserInfo)
const { isLoading } = useQuery({
  queryKey: ['userFullInfo'],
  queryFn: async () => {
    const meRes = await userService.me();
    const user = meRes.data.data;
    
    const infoRes = await userService.info(user.id);
    const info = infoRes.data.data;
    
    // 同步到表单
    form.nickName = user.nickName;
    form.icon = user.icon;
    if (info) {
      form.gender = info.gender;
      form.city = info.city ?? '';
      form.birthday = info.birthday ?? '';
      form.introduce = info.introduce ?? '';
    }
    return { user, info };
  },
});

const isUploading = ref(false);
const fileInputRef = ref<HTMLInputElement>();

const onAvatarClick = () => {
  if (isUploading.value) return;
  fileInputRef.value?.click();
};

const onFileChange = async (e: Event) => {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;

  if (!/^image\/(jpeg|png|webp)$/i.test(file.type)) {
    showToast('仅支持 jpg/png/webp 格式的图片');
    return;
  }
  if (file.size > 2 * 1024 * 1024) {
    showToast('图片大小不能超过 2MB');
    return;
  }

  isUploading.value = true;
  try {
    const res = await uploadService.uploadIcon(file);
    if (res.data.success && res.data.data) {
      form.icon = res.data.data;
      showToast('头像上传成功，点击保存生效');
    } else {
      showToast(res.data.errorMsg || '头像上传失败');
    }
  } catch (err: any) {
    showToast(err?.errorMsg || '头像上传失败，请重试');
  } finally {
    isUploading.value = false;
  }
};

// 3. 保存资料
const saveMutation = useMutation({
  mutationFn: (data: UpdateUserInfoDTO) => userService.updateInfo(data),
  onSuccess: (res) => {
    if (res.data.success) {
      showToast('保存成功');
      // 更新 Pinia 中的基本信息
      if (userStore.user) {
        userStore.setUser({
          ...userStore.user,
          nickName: form.nickName || userStore.user.nickName,
          icon: form.icon || userStore.user.icon,
        });
      }
      queryClient.invalidateQueries({ queryKey: ['userFullInfo'] });
      router.back();
    }
  },
  onError: (err: any) => {
    showToast(err?.errorMsg || '保存失败，请稍后重试');
  },
});

const onGenderSelect = (item: any) => {
  form.gender = item.value;
  showGenderSheet.value = false;
};

const BIRTHDAY_REGEX = /^\d{4}-\d{2}-\d{2}$/;

const onBirthdayChange = (value: string | number | undefined) => {
  form.birthday = value ? String(value) : null;
};

const onSubmit = () => {
  if (!form.nickName?.trim()) {
    showToast('昵称不能为空');
    return;
  }
  if (form.birthday && !BIRTHDAY_REGEX.test(form.birthday)) {
    showToast('生日格式应为 YYYY-MM-DD');
    return;
  }
  const payload: UpdateUserInfoDTO = {
    ...form,
    birthday: form.birthday || null,
  };
  saveMutation.mutate(payload);
};

const goBack = () => router.back();
</script>

<template>
  <div class="user-info-page min-h-screen bg-gray-50">
    <van-nav-bar title="个人资料" left-arrow fixed placeholder @click-left="goBack">
      <!-- 用真 <button> 而非 right-text：Vant 的 right-text 渲染成裸 span，无 role/tabindex，键盘不可达 -->
      <template #right>
        <button type="button" class="van-nav-bar__text van-nav-bar__text-btn" @click="onSubmit">保存</button>
      </template>
    </van-nav-bar>

    <div v-if="isLoading" class="p-10 text-center">
      <van-loading type="spinner" />
    </div>

    <template v-else>
      <!-- Avatar Section -->
      <div class="bg-white p-6 flex flex-col items-center border-b">
        <div class="relative" @click="onAvatarClick">
          <van-image
            :src="form.icon || '/imgs/icons/default-icon.svg'"
            round
            width="80"
            height="80"
            class="border-2 border-gray-100 shadow-sm"
          />
          <div class="absolute bottom-0 right-0 rounded-full p-1 shadow-md avatar-badge">
            <van-icon v-if="!isUploading" name="photograph" color="#fff" size="14" />
            <van-loading v-else color="#fff" size="14" />
          </div>
        </div>
        <span class="text-xs text-gray-400 mt-2">{{ isUploading ? '上传中...' : '点击更换头像' }}</span>
        <input ref="fileInputRef" type="file" accept="image/jpeg,image/png,image/webp" class="hidden" @change="onFileChange" />
      </div>

      <!-- Form Section -->
      <van-cell-group inset class="mt-4">
        <van-field
          v-model="form.nickName"
          label="昵称"
          placeholder="请输入昵称"
          maxlength="30"
          input-align="right"
        />
        <van-cell 
          title="性别" 
          :value="form.gender ? '女' : '男'" 
          is-link 
          @click="showGenderSheet = true" 
        />
        <van-field
          v-model="form.city"
          label="城市"
          placeholder="请输入城市"
          maxlength="50"
          input-align="right"
        />
        <van-field
          :model-value="form.birthday ?? ''"
          label="生日"
          placeholder="YYYY-MM-DD"
          input-align="right"
          @update:model-value="onBirthdayChange"
        />
      </van-cell-group>

      <van-cell-group inset class="mt-4">
        <van-field
          v-model="form.introduce"
          label="简介"
          type="textarea"
          placeholder="介绍一下你自己吧..."
          rows="3"
          autosize
          maxlength="200"
          show-word-limit
        />
      </van-cell-group>

      <div class="p-8 text-center text-xs text-gray-400">
        资料越完善，越容易结交志同道合的探店达人哦
      </div>
    </template>

    <!-- Action Sheets -->
    <van-action-sheet
      v-model:show="showGenderSheet"
      :actions="genderOptions"
      cancel-text="取消"
      close-on-click-action
      @select="onGenderSelect"
    />
  </div>
</template>

<style scoped>
.user-info-page {
  --van-nav-bar-icon-color: #333;
}
:deep(.van-cell__title) { color: #666; }
:deep(.van-field__label) { color: #666; }

.avatar-badge {
  background: rgba(232, 72, 85, 0.9);
  border: 2px solid #fff;
}
</style>
