<script setup lang="ts">
import { useRouter } from 'vue-router';
const router = useRouter();

const goHome = () => router.replace('/');
</script>

<template>
  <div class="not-found-page min-h-screen bg-white">
    <van-nav-bar title="页面不存在" left-arrow fixed placeholder @click-left="router.back()" />

    <div class="flex flex-col items-center pt-16 px-8">
      <van-icon name="warning-o" size="64" class="text-gray-300" />
      <h2 class="mt-5 text-lg font-bold text-gray-700">你访问的页面不存在</h2>
      <p class="mt-2 text-sm text-gray-400 text-center leading-relaxed">
        链接可能已失效，或地址输入有误
      </p>
      <div class="mt-8 w-full">
        <van-button block round type="primary" @click="goHome">返回首页</van-button>
      </div>
    </div>
  </div>
</template>
