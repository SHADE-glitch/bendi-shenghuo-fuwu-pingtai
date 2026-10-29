import { describe, expect, it } from 'vitest';
import { validateLoginForm, validatePhone } from '../src/utils/validators';

describe('validators', () => {
  it('validates phone format', () => {
    expect(validatePhone('13800138000')).toBeNull();
    expect(validatePhone('123')).toBe('请输入正确的手机号');
  });

  it('validates login form', () => {
    expect(validateLoginForm('13800138000', '123456')).toBeNull();
    expect(validateLoginForm('13800138000', '123')).toBe('请输入6位验证码');
  });
});
