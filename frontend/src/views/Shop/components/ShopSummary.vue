<script setup lang="ts">
import { ref } from 'vue';
import { showToast } from 'vant';
import QRCode from 'qrcode';
import type { Shop } from '@/api/types';
import { encodeShopLink } from '@/utils/scan';

const props = defineProps<{
  shop: Shop;
  images: string[];
  scoreValue: number;
}>();

const showQr = ref(false);
const qrDataUrl = ref('');

const openQr = async () => {
  showQr.value = true;
  qrDataUrl.value = '';
  try {
    qrDataUrl.value = await QRCode.toDataURL(encodeShopLink(props.shop.id), {
      width: 280,
      margin: 1,
      color: { dark: '#111827', light: '#ffffff' },
    });
  } catch {
    showToast('二维码生成失败，请重试');
  }
};
</script>

<template>
  <div>
    <van-swipe :autoplay="3000" indicator-color="white" class="hero-swipe">
      <van-swipe-item v-for="(img, i) in images" :key="i">
        <van-image :src="img" width="100%" height="238" fit="cover" />
      </van-swipe-item>
    </van-swipe>

    <section class="shop-summary">
      <div class="title-row">
        <h1>{{ shop.name }}</h1>
        <div class="title-actions">
          <span>人均 ¥{{ shop.avgPrice || 0 }}</span>
          <button class="qr-btn" @click="openQr">
            <van-icon name="qr" size="16" />
            分享二维码
          </button>
        </div>
      </div>
      <div class="rating-line">
        <van-rate :model-value="scoreValue" readonly allow-half size="14" color="#ffb020" />
        <strong>{{ scoreValue.toFixed(1) }}分</strong>
        <span>月售 {{ shop.sold || 0 }}</span>
        <span>{{ shop.comments || 0 }}条评价</span>
      </div>
      <div class="address-line">
        <van-icon name="location-o" />
        <span>{{ shop.address }}</span>
      </div>
      <div v-if="shop.openHours" class="address-line">
        <van-icon name="clock-o" />
        <span>{{ shop.openHours }}</span>
      </div>
    </section>

    <van-popup v-model:show="showQr" round closeable class="qr-popup">
      <div class="qr-box">
        <p class="qr-title">{{ shop.name }}</p>
        <div class="qr-img-wrap">
          <van-image v-if="qrDataUrl" :src="qrDataUrl" width="260" height="260" radius="8" />
          <van-loading v-else size="28" />
        </div>
        <p class="qr-tip">扫码即可直达本店，快分享给朋友吧</p>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.hero-swipe {
  background: #e5e7eb;
}

.shop-summary {
  margin: 10px 12px 0;
  padding: 16px;
  border: 1px solid var(--c-border);
  border-radius: 10px;
  background: var(--c-card-bg);
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.05);
}

.title-row,
.rating-line,
.address-line {
  display: flex;
  align-items: center;
}

.title-row {
  justify-content: space-between;
  gap: 12px;
}

.title-row h1 {
  margin: 0;
  color: var(--c-text-1);
  font-size: 22px;
  line-height: 1.2;
  font-weight: 900;
}

.title-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.title-actions span {
  color: #e84855;
  font-size: 13px;
  font-weight: 900;
}

.qr-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 4px 10px;
  border: 1px solid rgba(232, 72, 85, 0.35);
  border-radius: 999px;
  background: rgba(232, 72, 85, 0.12);
  color: #e84855;
  font-size: 12px;
  cursor: pointer;
}

.rating-line {
  gap: 8px;
  margin-top: 10px;
  color: var(--c-text-2);
  font-size: 12px;
}

.rating-line strong {
  color: #f59e0b;
}

.address-line {
  gap: 7px;
  margin-top: 12px;
  color: var(--c-text-2);
  font-size: 13px;
  line-height: 1.5;
}

.qr-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 26px 22px 30px;
}

.qr-title {
  margin: 0;
  color: var(--c-text-1);
  font-size: 17px;
  font-weight: 800;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qr-img-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 264px;
  height: 264px;
  border: 1px solid var(--c-border);
  border-radius: 10px;
  background: var(--c-card-bg);
}

.qr-tip {
  margin: 0;
  color: var(--c-text-2);
  font-size: 12px;
}
</style>
