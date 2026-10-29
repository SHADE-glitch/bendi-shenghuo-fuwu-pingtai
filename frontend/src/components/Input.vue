<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  modelValue?: string | number;
  label?: string;
  placeholder?: string;
  type?: 'text' | 'password' | 'email' | 'tel' | 'number' | 'digit';
  disabled?: boolean;
  readonly?: boolean;
  error?: boolean;
  helpText?: string;
  maxlength?: number | string;
  autocomplete?: string;
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: '',
  type: 'text',
  disabled: false,
  readonly: false,
  error: false,
});

const emit = defineEmits<{
  'update:modelValue': [value: string];
  blur: [event: FocusEvent];
  focus: [event: FocusEvent];
}>();

const inputClasses = computed(() => [
  'input-stripe-base',
  {
    'input-stripe-disabled': props.disabled,
    'input-stripe-readonly': props.readonly,
    'input-stripe-error': props.error,
  },
]);

const handleInput = (event: Event) => {
  const target = event.target as HTMLInputElement;
  emit('update:modelValue', target.value);
};

const handleFocus = (event: FocusEvent) => {
  emit('focus', event);
};

const handleBlur = (event: FocusEvent) => {
  emit('blur', event);
};
</script>

<template>
  <div class="input-stripe-wrapper">
    <label v-if="label" class="input-stripe-label">{{ label }}</label>
    
    <div class="input-stripe-container">
      <input
        :model-value="modelValue"
        :type="type"
        :placeholder="placeholder"
        :disabled="disabled"
        :readonly="readonly"
        :maxlength="maxlength"
        :autocomplete="autocomplete"
        :class="inputClasses"
        @input="handleInput"
        @focus="handleFocus"
        @blur="handleBlur"
      />
      
      <!-- Error Icon -->
      <span v-if="error" class="input-stripe-error-icon">
        <van-icon name="cross" size="16" color="var(--color-ruby)" />
      </span>
    </div>
    
    <!-- Help Text / Error Message -->
    <div v-if="helpText || error" class="input-stripe-hint">
      <span :class="{ 'input-stripe-error-text': error }">
        {{ error ? helpText : helpText }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.input-stripe-wrapper {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.input-stripe-label {
  font-size: 15px;
  font-weight: 300;
  color: var(--color-ink);
  line-height: 1.4;
}

.input-stripe-container {
  position: relative;
  display: flex;
  align-items: center;
}

.input-stripe-base {
  width: 100%;
  padding: 8px 12px;
  background: var(--color-canvas);
  color: var(--color-ink);
  border: 1px solid var(--color-hairline-input);
  border-radius: 6px;
  font-family: inherit;
  font-size: 15px;
  font-weight: 300;
  line-height: 1.4;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.input-stripe-base::placeholder {
  color: var(--color-ink-mute);
}

.input-stripe-base:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(83, 58, 253, 0.15);
}

.input-stripe-base:hover:not(.input-stripe-disabled):not(.input-stripe-readonly) {
  border-color: var(--color-primary-soft);
}

.input-stripe-disabled {
  background: var(--color-canvas-soft);
  color: var(--color-ink-mute);
  cursor: not-allowed;
}

.input-stripe-readonly {
  background: var(--color-canvas-soft);
  cursor: default;
}

.input-stripe-error {
  border-color: var(--color-ruby);
}

.input-stripe-error:focus {
  border-color: var(--color-ruby);
  box-shadow: 0 0 0 3px rgba(234, 34, 97, 0.15);
}

.input-stripe-error-icon {
  position: absolute;
  right: 12px;
}

.input-stripe-hint {
  font-size: 13px;
  line-height: 1.4;
  color: var(--color-ink-mute);
}

.input-stripe-error-text {
  color: var(--color-ruby);
}

/* Mobile Optimization */
@media (max-width: 768px) {
  .input-stripe-base {
    min-height: 44px;
    font-size: 16px; /* Prevent zoom on iOS */
  }
  
  .input-stripe-wrapper {
    gap: 10px;
  }
}
</style>
