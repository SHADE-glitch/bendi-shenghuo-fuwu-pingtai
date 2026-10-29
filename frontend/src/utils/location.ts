import { ref } from 'vue';

export interface GeoPoint {
  x: number;
  y: number;
}

export const DEFAULT_LOCATION: GeoPoint = { x: 120.155, y: 30.275 };

export type LocateMode = 'auto' | 'demo';

const MODE_KEY = 'loc_mode';
const CITY_KEY = 'loc_city';
const AUTO_TIMEOUT = 5000;

export const readLocateMode = (): LocateMode => {
  const saved = localStorage.getItem(MODE_KEY);
  return saved === 'demo' ? 'demo' : 'auto';
};

export const writeLocateMode = (mode: LocateMode) => {
  localStorage.setItem(MODE_KEY, mode);
};

export const cityByName = (name: string): City =>
  CITY_LIST.find((c) => c.name === name) || CITY_LIST.find((c) => c.name === '杭州')!;

export const readCityName = (): string => {
  const saved = localStorage.getItem(CITY_KEY);
  return saved && CITY_LIST.some((c) => c.name === saved) ? saved : '杭州';
};

interface City {
  name: string;
  x: number;
  y: number;
}

export const CITY_LIST: City[] = [
  { name: '北京', x: 116.4074, y: 39.9042 },
  { name: '上海', x: 121.4737, y: 31.2304 },
  { name: '广州', x: 113.2644, y: 23.1291 },
  { name: '深圳', x: 114.0579, y: 22.5431 },
  { name: '杭州', x: 120.155, y: 30.275 },
  { name: '南京', x: 118.7969, y: 32.0603 },
  { name: '苏州', x: 120.5853, y: 31.2989 },
  { name: '成都', x: 104.0665, y: 30.5723 },
  { name: '重庆', x: 106.5516, y: 29.563 },
  { name: '武汉', x: 114.3054, y: 30.5931 },
  { name: '西安', x: 108.9398, y: 34.3416 },
  { name: '天津', x: 117.2008, y: 39.0842 },
  { name: '郑州', x: 113.6254, y: 34.7466 },
  { name: '长沙', x: 112.9388, y: 28.2282 },
  { name: '合肥', x: 117.2272, y: 31.8206 },
  { name: '济南', x: 117.1205, y: 36.6512 },
  { name: '青岛', x: 120.3826, y: 36.0671 },
  { name: '沈阳', x: 123.4315, y: 41.8057 },
  { name: '大连', x: 121.6147, y: 38.914 },
  { name: '哈尔滨', x: 126.5349, y: 45.8038 },
  { name: '长春', x: 125.3235, y: 43.8171 },
  { name: '石家庄', x: 114.5149, y: 38.0428 },
  { name: '太原', x: 112.5489, y: 37.8706 },
  { name: '福州', x: 119.2965, y: 26.0745 },
  { name: '厦门', x: 118.0894, y: 24.4798 },
  { name: '南昌', x: 115.8582, y: 28.6829 },
  { name: '昆明', x: 102.8329, y: 24.8801 },
  { name: '贵阳', x: 106.6302, y: 26.6477 },
  { name: '南宁', x: 108.3669, y: 22.817 },
  { name: '海口', x: 110.1983, y: 20.044 },
  { name: '兰州', x: 103.8343, y: 36.0611 },
  { name: '西宁', x: 101.7782, y: 36.6171 },
  { name: '银川', x: 106.2309, y: 38.4872 },
  { name: '乌鲁木齐', x: 87.6168, y: 43.8256 },
  { name: '呼和浩特', x: 111.7492, y: 40.8426 },
  { name: '拉萨', x: 91.1409, y: 29.6456 },
  { name: '宁波', x: 121.5503, y: 29.8746 },
  { name: '无锡', x: 120.3119, y: 31.4912 },
  { name: '温州', x: 120.6994, y: 27.9943 },
  { name: '佛山', x: 113.1214, y: 23.0215 },
  { name: '东莞', x: 113.7518, y: 23.0207 },
  { name: '泉州', x: 118.6757, y: 24.8741 },
  { name: '烟台', x: 121.4479, y: 37.4638 },
  { name: '徐州', x: 117.2841, y: 34.2058 },
  { name: '洛阳', x: 112.454, y: 34.6197 },
];

const EARTH_RADIUS = 6371000;

const distanceMeters = (lat1: number, lon1: number, lat2: number, lon2: number) => {
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2);
  return 2 * EARTH_RADIUS * Math.asin(Math.sqrt(a));
};

export const reverseGeocode = (point: GeoPoint): string => {
  let nearest = CITY_LIST[0];
  let nearestDist = Infinity;
  for (const city of CITY_LIST) {
    const dist = distanceMeters(point.y, point.x, city.y, city.x);
    if (dist < nearestDist) {
      nearestDist = dist;
      nearest = city;
    }
  }
  return nearest.name;
};

const geolocationSupported = () => typeof navigator !== 'undefined' && 'geolocation' in navigator;

/**
 * 高德底图只覆盖中国大陆及港澳台，境外坐标会渲染成一片空白网格。
 * 这里取一个能完整包住中国陆域（含台湾、含南海诸岛）的外接矩形作为合法性范围：
 * 东经 73–136（含最西的帕米尔与最东的黑瞎子岛），北纬 3–54（含南沙群岛最南端）。
 * 落在矩形外的 GPS 结果说明浏览器定位到了境外，此时不能用它当地图中心。
 */
const isInsideMainland = (point: GeoPoint) =>
  point.x >= 73 && point.x <= 136 && point.y >= 3 && point.y <= 54;

const locateByGps = () =>
  new Promise<GeoPoint | null>((resolve) => {
    if (!geolocationSupported()) {
      resolve(null);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const point = { x: pos.coords.longitude, y: pos.coords.latitude };
        resolve(isInsideMainland(point) ? point : null);
      },
      () => resolve(null),
      { timeout: AUTO_TIMEOUT, maximumAge: 5 * 60 * 1000 }
    );
  });

export function useLocation() {
  const mode = ref<LocateMode>(readLocateMode());
  const locating = ref(false);
  const usedFallback = ref(false);
  const location = ref<GeoPoint>({ ...DEFAULT_LOCATION });
  const cityName = ref('杭州');

  const apply = async () => {
    if (mode.value === 'demo') {
      const city = cityByName(readCityName());
      location.value = { x: city.x, y: city.y };
      usedFallback.value = false;
      cityName.value = city.name;
      return;
    }
    locating.value = true;
    const gps = await locateByGps();
    locating.value = false;
    if (gps) {
      location.value = gps;
      usedFallback.value = false;
      cityName.value = reverseGeocode(gps);
    } else {
      location.value = { ...DEFAULT_LOCATION };
      usedFallback.value = true;
      cityName.value = '杭州';
    }
  };

  const setMode = (next: LocateMode) => {
    mode.value = next;
    writeLocateMode(next);
    apply();
  };

  const selectCity = (name: string) => {
    const city = cityByName(name);
    writeLocateMode('demo');
    localStorage.setItem(CITY_KEY, city.name);
    mode.value = 'demo';
    location.value = { x: city.x, y: city.y };
    usedFallback.value = false;
    cityName.value = city.name;
  };

  apply();

  return { mode, location, locating, usedFallback, cityName, setMode, selectCity };
}