<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { showToast } from 'vant';
import jsQR from 'jsqr';
import { parseScanText, resolveScanTarget } from '@/utils/scan';

const router = useRouter();
const videoRef = ref<HTMLVideoElement>();
const canvasRef = ref<HTMLCanvasElement>();
const scanning = ref(true);
const torchOn = ref(false);
const torchSupported = ref(false);

let stream: MediaStream | null = null;
let rafId = 0;
let lastInvalidAt = 0;

const stopCamera = () => {
  cancelAnimationFrame(rafId);
  stream?.getTracks().forEach((t) => t.stop());
  stream = null;
};

const onDecode = (text: string) => {
  const payload = parseScanText(text);
  if (!payload) {
    const now = Date.now();
    if (now - lastInvalidAt > 1500) {
      lastInvalidAt = now;
      showToast('无法识别的二维码');
    }
    return;
  }
  stopCamera();
  scanning.value = false;
  router.push(resolveScanTarget(payload));
};

const tick = () => {
  rafId = requestAnimationFrame(tick);
  const video = videoRef.value;
  const canvas = canvasRef.value;
  if (!video || !canvas || video.readyState < 2) return;
  const w = video.videoWidth;
  const h = video.videoHeight;
  if (!w || !h) return;
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return;
  ctx.drawImage(video, 0, 0, w, h);
  const img = ctx.getImageData(0, 0, w, h);
  const code = jsQR(img.data, w, h, { inversionAttempts: 'dontInvert' });
  if (code?.data) onDecode(code.data);
};

const toggleTorch = async () => {
  const track = stream?.getVideoTracks()[0];
  if (!track) return;
  try {
    await track.applyConstraints({ advanced: [{ torch: !torchOn.value } as MediaTrackConstraintSet] });
    torchOn.value = !torchOn.value;
  } catch {
    showToast('当前设备不支持手电筒');
  }
};

onMounted(async () => {
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'environment' },
      audio: false,
    });
    const track = stream.getVideoTracks()[0];
    torchSupported.value = (track?.getCapabilities?.() as MediaTrackCapabilities & { torch?: boolean })?.torch ?? false;
    if (videoRef.value) {
      videoRef.value.srcObject = stream;
      await videoRef.value.play();
    }
    tick();
  } catch (err: any) {
    scanning.value = false;
    if (err?.name === 'NotAllowedError') {
      showToast('摄像头权限被拒绝，请在浏览器设置中允许');
    } else if (err?.name === 'NotFoundError') {
      showToast('未检测到摄像头设备');
    } else {
      showToast('无法启动摄像头');
    }
  }
});

onBeforeUnmount(stopCamera);
</script>

<template>
  <div class="scan-page">
    <video ref="videoRef" class="scan-video" muted playsinline />
    <canvas ref="canvasRef" class="scan-canvas" />

    <div class="scan-overlay">
      <div class="scan-frame">
        <span class="corner tl" />
        <span class="corner tr" />
        <span class="corner bl" />
        <span class="corner br" />
        <div v-if="scanning" class="scan-line" />
      </div>
      <p class="scan-tip">将二维码放入框内，即可自动识别</p>
      <div v-if="torchSupported" class="scan-actions">
        <van-button round size="small" plain type="warning" @click="toggleTorch">
          {{ torchOn ? '关闭手电筒' : '打开手电筒' }}
        </van-button>
      </div>
    </div>

    <van-icon name="cross" class="scan-close" @click="router.back()" />
  </div>
</template>

<style scoped>
.scan-page {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: #000;
  overflow: hidden;
}

.scan-video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.scan-canvas {
  display: none;
}

.scan-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 24px;
  background: rgba(0, 0, 0, 0.35);
}

.scan-frame {
  position: relative;
  width: 250px;
  height: 250px;
  border-radius: 14px;
}

.corner {
  position: absolute;
  width: 34px;
  height: 34px;
  border: 4px solid var(--color-primary);
}

.corner.tl {
  top: 0;
  left: 0;
  border-right: none;
  border-bottom: none;
  border-top-left-radius: 12px;
}

.corner.tr {
  top: 0;
  right: 0;
  border-left: none;
  border-bottom: none;
  border-top-right-radius: 12px;
}

.corner.bl {
  bottom: 0;
  left: 0;
  border-right: none;
  border-top: none;
  border-bottom-left-radius: 12px;
}

.corner.br {
  bottom: 0;
  right: 0;
  border-left: none;
  border-top: none;
  border-bottom-right-radius: 12px;
}

.scan-line {
  position: absolute;
  left: 8px;
  right: 8px;
  top: 8px;
  height: 2px;
  background: linear-gradient(90deg, transparent, var(--color-primary), transparent);
  box-shadow: 0 0 12px rgba(83, 58, 253, 0.8);
  animation: scan-move 2.2s ease-in-out infinite;
}

@keyframes scan-move {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(226px);
  }
}

.scan-tip {
  margin: 0;
  color: rgba(255, 255, 255, 0.92);
  font-size: 14px;
  letter-spacing: 1px;
}

.scan-actions {
  min-height: 32px;
}

.scan-close {
  position: absolute;
  top: 18px;
  right: 18px;
  color: #fff;
  font-size: 26px;
  padding: 8px;
  background: rgba(0, 0, 0, 0.45);
  border-radius: 50%;
}
</style>