<script setup lang="ts">
import { useRouter } from 'vue-router';
const router = useRouter();
</script>

<template>
  <div class="about-page min-h-screen bg-white">
    <van-nav-bar title="关于我们" left-arrow fixed placeholder @click-left="router.back()" />
    
    <div class="flex flex-col items-center pt-12">
      <van-image src="/favicon.svg" width="80" height="80" />
      <h2 class="mt-4 text-2xl font-bold text-[#f72d4e]">黑马点评</h2>
      <p class="text-gray-400 text-sm mt-1">Version 1.0.0</p>
    </div>

    <div class="mt-12 px-8 text-sm text-gray-600 leading-relaxed">
      <p>黑马点评是一款专注于城市生活服务发现与互动的应用。在这里，你可以：</p>
      <ul class="list-disc pl-5 mt-4 space-y-2">
        <li>发现周边高分优质商铺</li>
        <li>抢购超值秒杀代金券</li>
        <li>分享真实的探店心得</li>
        <li>结交志同道合的探店达人</li>
      </ul>
    </div>

    <div class="fixed bottom-10 left-0 right-0 text-center text-[10px] text-gray-300">
      <p>© 2026 黑马点评 版权所有</p>
      <p class="mt-1">由 Gemini CLI 强力驱动</p>
    </div>
  </div>
</template>
