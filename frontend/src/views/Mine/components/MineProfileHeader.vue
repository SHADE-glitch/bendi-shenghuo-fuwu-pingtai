<script setup lang="ts">
import type { UserDTO } from '@/api/types';

defineProps<{
  user?: UserDTO | null;
  displayName: string;
  userId: string | number;
  isSigned: boolean;
}>();

const emit = defineEmits<{
  clickInfo: [];
  sign: [];
  clickVouchers: [];
  clickSettings: [];
}>();
</script>

<template>
  <section class="profile-header">
    <div class="profile-top">
      <van-image
        :src="user?.icon || '/imgs/icons/default-icon.svg'"
        round
        width="72"
        height="72"
        class="avatar"
        @click="emit('clickInfo')"
      />
      <div class="profile-copy" @click="emit('clickInfo')">
        <h1>{{ displayName }}</h1>
        <p>ID: {{ userId }}</p>
        <span>本地生活服务平台 · 面试演示版</span>
      </div>
      <van-icon name="setting-o" size="23" @click="emit('clickSettings')" />
    </div>

    <div class="profile-actions">
      <button type="button" :class="{ signed: isSigned }" @click="emit('sign')">
        <van-icon :name="isSigned ? 'checked' : 'calendar-o'" />
        {{ isSigned ? '今日已签' : '每日签到' }}
      </button>
      <button type="button" @click="emit('clickVouchers')">
        <van-icon name="coupon-o" />
        我的优惠券
      </button>
    </div>
  </section>
</template>

<style scoped>
.profile-header {
  padding: 38px 18px 18px;
  color: white;
}

.profile-top {
  display: flex;
  align-items: center;
  gap: 14px;
}

.avatar {
  border: 3px solid rgba(255, 255, 255, 0.22);
}

.profile-copy {
  min-width: 0;
  flex: 1;
}

.profile-copy h1 {
  margin: 0;
  font-size: 22px;
  line-height: 1.2;
  font-weight: 900;
}

.profile-copy p,
.profile-copy span {
  display: block;
  margin-top: 4px;
  color: rgba(255, 255, 255, 0.68);
  font-size: 12px;
}

.profile-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: 20px;
}

.profile-actions button {
  height: 40px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.1);
  color: white;
  font-size: 13px;
  font-weight: 800;
}

.profile-actions button.signed {
  background: rgba(16, 185, 129, 0.28);
}
</style>
