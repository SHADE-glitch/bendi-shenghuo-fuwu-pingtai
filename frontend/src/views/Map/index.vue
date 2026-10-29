<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';
import { showToast } from 'vant';
import { shopService } from '@/api/shop';
import { useLocation, CITY_LIST } from '@/utils/location';
import { wgs84ToGcj02 } from '@/utils/coord';
import { formatScore } from '@/utils/format';
import { loadAMap, resetAMapCache } from '@/utils/amap';
import type { Shop } from '@/api/types';

const router = useRouter();
const activeType = ref(0);
const showCityPicker = ref(false);
const mapContainer = ref<HTMLDivElement>();
const mapError = ref(false);
const loadingMap = ref(false);
const { mode, location, locating, usedFallback, cityName, setMode, selectCity } = useLocation();

watch(usedFallback, (v) => {
  if (v) showToast('定位失败，已使用默认演示位置（杭州）');
});

let map: any = null;
let userMarker: any = null;
let shopMarkers: any[] = [];
let infoWindow: any = null;
let destroyed = false;

const gcjCenter = computed(() => wgs84ToGcj02(location.value.x, location.value.y));

const { data: types } = useQuery({
  queryKey: ['shopTypes'],
  queryFn: () => shopService.queryTypeList().then((res) => res.data.data || []),
});

const currentTypeId = computed(() => types.value?.[activeType.value]?.id);

const { data: shops } = useQuery<Shop[], Error, Shop[]>({
  queryKey: ['nearbyShops', computed(() => ({ typeId: currentTypeId.value, x: location.value.x, y: location.value.y, city: cityName.value }))],
  queryFn: async () => {
    if (!currentTypeId.value) return [];
    const res = await shopService.queryByType({ typeId: currentTypeId.value, current: 1, x: location.value.x, y: location.value.y, city: cityName.value });
    return res.data.data || [];
  },
  enabled: computed(() => Boolean(currentTypeId.value)),
});

const positionText = computed(() => {
  if (mode.value === 'auto' && !usedFallback.value) {
    return `自动定位 · ${cityName.value}`;
  }
  return `${cityName.value}演示坐标`;
});

const initMap = () => {
  if (!mapContainer.value || destroyed) return;
  map = new window.AMap.Map(mapContainer.value, {
    zoom: 12,
    center: [gcjCenter.value.lng, gcjCenter.value.lat],
    viewMode: '2D',
  });
  infoWindow = new window.AMap.InfoWindow({ offset: new window.AMap.Pixel(0, -34), autoMove: true });
  renderUserMarker();
};

const mountMap = async () => {
  if (loadingMap.value) return;
  loadingMap.value = true;
  mapError.value = false;
  try {
    await loadAMap();
    if (destroyed) return;
    initMap();
    renderShopMarkers();
  } catch {
    mapError.value = true;
  } finally {
    loadingMap.value = false;
  }
};

const retryMap = async () => {
  resetAMapCache();
  await mountMap();
};

const refreshTiles = () => {
  if (!map) return;
  showToast('正在刷新地图...');
  map.destroy();
  map = null;
  userMarker = null;
  infoWindow = null;
  // destroy 后旧 marker 引用已失效，清空后再重建，否则刷新后店铺点位会消失
  shopMarkers = [];
  initMap();
  renderShopMarkers();
};

/** AMap InfoWindow.setContent 走 HTML 渲染，后端字段必须先转义，避免标签注入 */
const escapeHtml = (value: unknown) =>
  String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');

const renderUserMarker = () => {
  if (!map) return;
  if (userMarker) {
    userMarker.setPosition([gcjCenter.value.lng, gcjCenter.value.lat]);
    return;
  }
  userMarker = new window.AMap.Marker({
    position: [gcjCenter.value.lng, gcjCenter.value.lat],
    content: '<div class="user-dot"></div>',
    offset: new window.AMap.Pixel(-8, -8),
    zIndex: 200,
  });
  map.add(userMarker);
};

const clearShopMarkers = () => {
  shopMarkers.forEach((m) => map.remove(m));
  shopMarkers = [];
};

const isValidLngLat = (lng: any, lat: any) =>
  Number.isFinite(lng) && Number.isFinite(lat) && lng >= -180 && lng <= 180 && lat >= -90 && lat <= 90;

const renderShopMarkers = () => {
  if (!map) return;
  clearShopMarkers();
  (shops.value || []).forEach((shop: any) => {
    if (!isValidLngLat(shop.x, shop.y)) return;
    const marker = new window.AMap.Marker({
      position: [shop.x, shop.y],
      title: shop.name,
      content: `<div class="shop-pin">¥${escapeHtml(shop.avgPrice || '')}</div>`,
      offset: new window.AMap.Pixel(-17, -30),
      zIndex: 100,
    });
    marker.on('click', () => showShopInfo(shop));
    map.add(marker);
    shopMarkers.push(marker);
  });
};

const showShopInfo = (shop: any) => {
  if (!map) return;
  const distance = shop.distance ? `${(shop.distance / 1000).toFixed(1)}km` : '';
  const score = formatScore(shop.score).toFixed(1);
  const content = `
    <div class="map-info">
      <div class="map-info-name">${escapeHtml(shop.name)}</div>
      <div class="map-info-meta">${score}分${distance ? ` · ${distance}` : ''} · 月售${escapeHtml(shop.sold || 0)}</div>
      <div class="map-info-addr">${escapeHtml(shop.area || '')}${escapeHtml(shop.address || '')}</div>
      <button class="map-info-btn" data-id="${escapeHtml(shop.id)}">看优惠</button>
    </div>`;
  infoWindow.setContent(content);
  infoWindow.open(map, [shop.x, shop.y]);
  requestAnimationFrame(() => {
    const btn = document.querySelector('.amap-info-content .map-info-btn') as HTMLElement | null;
    if (!btn) return;
    // 使用 onclick 赋值而非 addEventListener：重复打开信息窗不会累积监听器
    btn.onclick = (e: Event) => {
      const id = (e.currentTarget as HTMLElement).dataset.id;
      if (id) router.push(`/shop/${id}`);
    };
  });
};

const relocate = () => {
  if (!map) return;
  map.setZoomAndCenter(13, [gcjCenter.value.lng, gcjCenter.value.lat]);
};

const toggleAuto = (v: boolean) => {
  setMode(v ? 'auto' : 'demo');
  if (!v) showToast(`已切换到${cityName.value}演示坐标`);
};

const onSelectCity = (name: string) => {
  selectCity(name);
  showCityPicker.value = false;
  showToast(`已切换到${name}演示坐标`);
};

watch(gcjCenter, () => {
  if (!map) return;
  map.setCenter([gcjCenter.value.lng, gcjCenter.value.lat]);
  renderUserMarker();
});

watch(shops, () => renderShopMarkers());

onMounted(() => {
  mountMap();
});

onBeforeUnmount(() => {
  destroyed = true;
  if (map) {
    map.destroy();
    map = null;
  }
});
</script>

<template>
  <div class="map-page">
    <van-nav-bar title="附近商户" fixed placeholder />

    <div ref="mapContainer" class="map-container"></div>

    <div v-if="mapError" class="map-error">
      <van-icon name="warn-o" size="40" color="var(--color-ruby)" />
      <p class="map-error-text">地图加载失败，请检查网络后重试</p>
      <van-button size="small" round type="primary" :loading="loadingMap" @click="retryMap">
        重新加载
      </van-button>
    </div>

    <div class="map-float">
      <div class="locate-chip" @click="showCityPicker = true">
        <van-icon name="location-o" />
        <span>{{ positionText }}</span>
        <van-icon name="arrow-down" />
      </div>
      <div class="coord-chip">坐标 {{ location.x.toFixed(4) }}, {{ location.y.toFixed(4) }}</div>
    </div>

    <div class="map-tabs">
      <van-tabs
        v-model:active="activeType"
        color="#533afd"
        title-active-color="#0d253d"
        background="var(--color-canvas)"
      >
        <van-tab v-for="t in types" :key="t.id" :title="t.name" />
      </van-tabs>
    </div>

    <button type="button" class="relocate-btn" @click="relocate">
      <van-icon name="aim" size="18" color="var(--color-primary)" />
    </button>

    <button type="button" class="refresh-btn" title="刷新地图瓦片" @click="refreshTiles">
      <van-icon name="replay" size="16" color="var(--color-primary)" />
    </button>

    <van-popup v-model:show="showCityPicker" position="bottom" round :style="{ height: '62%' }">
      <div class="city-picker">
        <div class="city-picker-title">选择演示城市</div>
        <div class="auto-row">
          <span>自动定位</span>
          <van-switch
            :model-value="mode === 'auto'"
            size="20px"
            :loading="locating"
            @update:model-value="toggleAuto"
          />
        </div>
        <div class="city-grid">
          <button
            v-for="c in CITY_LIST"
            :key="c.name"
            type="button"
            class="city-item"
            :class="{ active: c.name === cityName && mode !== 'auto' }"
            @click="onSelectCity(c.name)"
          >
            {{ c.name }}
          </button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.map-page {
  position: relative;
  height: calc(100vh - 60px);
  background: var(--color-canvas-soft);
}

.map-container {
  position: absolute;
  inset: 0;
}

.map-float {
  position: absolute;
  top: 10px;
  left: 12px;
  right: 12px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  pointer-events: none;
}

.locate-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-radius: 999px;
  background: var(--color-canvas);
  box-shadow: rgba(0, 55, 112, 0.08) 0 4px 16px;
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 400;
  pointer-events: auto;
  cursor: pointer;
}

.coord-chip {
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--color-canvas);
  color: var(--color-ink-mute);
  font-size: 11px;
}

.map-tabs {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 12px;
  border-radius: 12px;
  overflow: hidden;
}

.relocate-btn {
  position: absolute;
  right: 14px;
  bottom: 64px;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 50%;
  background: var(--color-canvas);
  box-shadow: rgba(0, 55, 112, 0.12) 0 4px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.refresh-btn {
  position: absolute;
  right: 14px;
  bottom: 112px;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 50%;
  background: var(--color-canvas);
  box-shadow: rgba(0, 55, 112, 0.12) 0 4px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.map-error {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background: var(--color-canvas-soft);
  z-index: 10;
}

.map-error-text {
  color: var(--color-ink-mute);
  font-size: 13px;
}

.city-picker {
  padding: 14px;
}

.city-picker-title {
  color: var(--color-ink);
  font-size: 15px;
  font-weight: 400;
  margin-bottom: 8px;
}

.auto-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--color-hairline);
  color: var(--color-ink-secondary);
  font-size: 13px;
}

.city-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  max-height: calc(62vh - 130px);
  overflow-y: auto;
}

.city-item {
  padding: 8px 0;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  background: var(--color-canvas);
  color: var(--color-ink-secondary);
  font-size: 13px;
}

.city-item.active {
  border-color: var(--color-primary);
  color: var(--color-primary);
  font-weight: 400;
}
</style>

<style>
.user-dot {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--color-primary);
  border: 3px solid white;
  box-shadow: 0 0 0 3px rgba(83, 58, 253, 0.25);
}

.shop-pin {
  min-width: 34px;
  padding: 3px 6px;
  border-radius: 8px;
  background: var(--color-primary);
  color: white;
  font-size: 11px;
  font-weight: 400;
  text-align: center;
  box-shadow: 0 3px 10px rgba(83, 58, 253, 0.35);
}

.map-info {
  min-width: 160px;
  padding: 2px 2px 6px;
}

.map-info-name {
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 400;
}

.map-info-meta {
  margin-top: 4px;
  color: var(--color-ink-secondary);
  font-size: 12px;
}

.map-info-addr {
  margin-top: 3px;
  color: var(--color-ink-mute);
  font-size: 11px;
  line-height: 1.4;
}

.map-info-btn {
  margin-top: 8px;
  padding: 5px 14px;
  border: 0;
  border-radius: 999px;
  background: var(--color-primary);
  color: white;
  font-size: 12px;
  font-weight: 400;
  cursor: pointer;
}
</style>