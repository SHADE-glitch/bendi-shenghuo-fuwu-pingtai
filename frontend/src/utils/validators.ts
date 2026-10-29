export const phonePattern = /^1[3-9]\d{9}$/;

export const validatePhone = (phone: string): string | null => {
  if (!phonePattern.test(phone)) {
    return '请输入正确的手机号';
  }
  return null;
};

export const validateLoginForm = (phone: string, code: string): string | null => {
  const phoneError = validatePhone(phone);
  if (phoneError) return phoneError;
  if (!/^\d{6}$/.test(code)) {
    return '请输入6位验证码';
  }
  return null;
};

export const validateCode = (code: string): string | null => {
  if (!/^\d{6}$/.test(code)) {
    return '请输入6位验证码';
  }
  return null;
};

export const validateNickname = (name: string): string | null => {
  const trimmed = name.trim();
  if (!trimmed) return '昵称不能为空';
  if (trimmed.length > 30) return '昵称长度不能超过30字';
  return null;
};

export const validateBirthday = (value: string | null | undefined): string | null => {
  if (!value) return null;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return '生日格式应为 YYYY-MM-DD';
  }
  return null;
};

export const validateRequired = (value: string | null | undefined, label: string): string | null => {
  if (!value || !value.trim()) {
    return `${label}不能为空`;
  }
  return null;
};
