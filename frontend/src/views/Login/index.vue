<script setup lang="ts">
import { computed, ref, reactive, onBeforeUnmount } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showToast } from 'vant';
import { useUserStore } from '@/store/user';
import { LoginResultDTO } from '@/api/types';
import { userService } from '@/api/user';
import { getErrorMessage } from '@/api/client';
import { validateLoginForm, validatePhone } from '@/utils/validators';
import GradientHeader from '@/components/GradientHeader.vue';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const form = reactive({
  phone: '',
  code: '',
});

const countdown = ref(0);
const loading = ref(false);

/**
 * 校验提示只在用户开始输入后出现：
 * 空表单直接展示「请输入正确的手机号」属于未交互即报错，体验不好。
 */
const phoneError = computed(() => (form.phone.length > 0 ? validatePhone(form.phone) : ''));
const codeError = computed(() =>
  form.phone.length > 0 && form.code.length > 0 ? validateLoginForm(form.phone, form.code) : ''
);
/** 验证码倒计时定时器，组件卸载时必须清理，避免离开页面后仍在写 ref */
let countdownTimer: ReturnType<typeof setInterval> | null = null;

const stopCountdown = () => {
  if (countdownTimer) {
    clearInterval(countdownTimer);
    countdownTimer = null;
  }
};

const sendCode = async () => {
  const phoneError = validatePhone(form.phone);
  if (phoneError) {
    showToast(phoneError);
    return;
  }
  
  try {
    await userService.sendCode(form.phone);
    showToast('验证码已发送');
    stopCountdown();
    countdown.value = 60;
    countdownTimer = setInterval(() => {
      countdown.value--;
      if (countdown.value <= 0) stopCountdown();
    }, 1000);
  } catch (err) {
    // 业务失败（如 10003 验证码错误 / 10006 请求过于频繁）拦截器不弹提示，需在此 surface
    showToast(getErrorMessage(err));
  }
};

onBeforeUnmount(stopCountdown);

const handleLogin = async () => {
  const formError = validateLoginForm(form.phone, form.code);
  if (formError) {
    showToast(formError);
    return;
  }
  
  loading.value = true;
  try {
    const res = await userService.login(form);
    const { token, user } = res.data.data as LoginResultDTO;
    userStore.setToken(token);
    userStore.setUser(user);
    showToast('登录成功');
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/';
    router.replace(redirect);
  } catch (err) {
    // 业务失败（如 10003 验证码错误 / 10006 请求过于频繁）拦截器不弹提示，需在此 surface
    showToast(getErrorMessage(err));
  } finally {
    loading.value = false;
  }
};
</script>

<template>
  <div class="login-page min-h-screen">
    <GradientHeader 
      title="黑马点评" 
      subtitle="欢迎回来，开启你的探店之旅"
      height="240px"
    />

    <div class="form-container card-stripe-light">
      <van-form @submit="handleLogin">
        <div class="form-content">
          <Input
            v-model="form.phone"
            label="手机号"
            type="tel"
            maxlength="11"
            placeholder="请输入手机号"
            :error="!!phoneError"
            :help-text="phoneError"
          />
          
          <div class="code-row">
            <Input
              v-model="form.code"
              label="验证码"
              type="digit"
              maxlength="6"
              placeholder="请输入验证码"
              :error="!!codeError"
            />
            
            <button
              type="button"
              class="btn-stripe-secondary btn-stripe-sm code-button"
              :disabled="countdown > 0 || loading"
              @click="sendCode"
            >
              {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
            </button>
          </div>
          
          <div class="submit-row">
            <Button
              type="submit"
              variant="primary"
              size="lg"
              block
              :loading="loading"
            >
              登 录
            </Button>
          </div>
        </div>
      </van-form>
      
      <div class="footer">
        <p class="footer-text stripe-caption">
          登录即表示你同意 
          <a href="#" class="link-primary">用户协议</a> 
          和 
          <a href="#" class="link-primary">隐私政策</a>
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  background: var(--color-canvas-soft);
}

.form-container {
  margin-top: -40px;
  position: relative;
  z-index: 10;
}

.form-content {
  padding: 24px;
}

.code-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 24px;
}

.code-button {
  flex-shrink: 0;
  margin-top: 2px;
}

.submit-row {
  margin-top: 32px;
}

.footer {
  margin-top: 32px;
  padding-top: 24px;
  border-top: 1px solid var(--color-hairline);
}

.footer-text {
  color: var(--color-ink-mute);
  line-height: 1.6;
}

.link-primary {
  color: var(--color-primary);
  text-decoration: none;
  font-weight: 400;
}

.link-primary:hover {
  text-decoration: underline;
}

/* Mobile optimization */
@media (max-width: 768px) {
  .form-content {
    padding: 20px 16px;
  }
  
  .code-row {
    flex-direction: column;
    gap: 16px;
  }
  
  .code-button {
    width: 100%;
  }
}
</style>
