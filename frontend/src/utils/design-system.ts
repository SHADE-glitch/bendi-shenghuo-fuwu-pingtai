/**
 * Stripe Design System Tokens
 * 
 * Based on Stripe's design language - see DESIGN.md for full specifications
 * This file provides TypeScript constants and types for the design system
 */

// ============================================
// Colors
// ============================================

export const Colors = {
  // Primary & Accent
  primary: '#533afd',
  primaryDeep: '#4434d4',
  primaryPress: '#2e2b8c',
  primarySoft: '#665efd',
  primaryBgSubduedHover: '#b9b9f9',
  
  // Brand Dark
  brandDark900: '#1c1e54',
  
  // Ink (Text Colors)
  ink: '#0d253d',
  inkSecondary: '#273951',
  inkMute: '#64748d',
  inkMute2: '#61718a',
  
  // Surfaces
  onPrimary: '#ffffff',
  canvas: '#ffffff',
  canvasSoft: '#f6f9fc',
  canvasCream: '#f5e9d4',
  hairline: '#e3e8ee',
  hairlineInput: '#a8c3de',
  
  // Accent Colors
  ruby: '#ea2261',
  magenta: '#f96bee',
  lemon: '#9b6829',
  shadowBlue: '#003770',
} as const;

export type ColorKey = keyof typeof Colors;

// ============================================
// Typography Scale
// ============================================

export const Typography = {
  // Display sizes with negative letter-spacing
  displayXxl: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 56,
    fontWeight: 300 as 300,
    lineHeight: 1.03,
    letterSpacing: -1.4,
  },
  
  displayXl: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 48,
    fontWeight: 300 as 300,
    lineHeight: 1.15,
    letterSpacing: -0.96,
  },
  
  displayLg: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 32,
    fontWeight: 300 as 300,
    lineHeight: 1.1,
    letterSpacing: -0.64,
  },
  
  displayMd: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 26,
    fontWeight: 300 as 300,
    lineHeight: 1.12,
    letterSpacing: -0.26,
  },
  
  // Heading sizes
  headingLg: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 22,
    fontWeight: 300 as 300,
    lineHeight: 1.1,
    letterSpacing: -0.22,
  },
  
  headingMd: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 20,
    fontWeight: 300 as 300,
    lineHeight: 1.4,
    letterSpacing: -0.2,
  },
  
  headingSm: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 18,
    fontWeight: 300 as 300,
    lineHeight: 1.4,
    letterSpacing: 0,
  },
  
  // Body sizes
  bodyLg: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 16,
    fontWeight: 300 as 300,
    lineHeight: 1.4,
    letterSpacing: 0,
  },
  
  bodyMd: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 15,
    fontWeight: 300 as 300,
    lineHeight: 1.4,
    letterSpacing: 0,
  },
  
  bodyTabular: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 14,
    fontWeight: 300 as 300,
    lineHeight: 1.4,
    letterSpacing: -0.42,
  },
  
  // Button sizes
  buttonMd: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 16,
    fontWeight: 400 as 400,
    lineHeight: 1.0,
    letterSpacing: 0,
  },
  
  buttonSm: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 14,
    fontWeight: 400 as 400,
    lineHeight: 1.0,
    letterSpacing: 0,
  },
  
  // Caption sizes
  caption: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 13,
    fontWeight: 400 as 400,
    lineHeight: 1.4,
    letterSpacing: -0.39,
  },
  
  micro: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 11,
    fontWeight: 300 as 300,
    lineHeight: 1.4,
    letterSpacing: 0,
  },
  
  microCap: {
    fontFamily: 'Inter, "SF Pro Display", system-ui, sans-serif',
    fontSize: 10,
    fontWeight: 400 as 400,
    lineHeight: 1.15,
    letterSpacing: 0.1,
  },
} as const;

export type TypographyKey = keyof typeof Typography;

// ============================================
// Spacing System
// ============================================

export const Spacing = {
  xxs: 2,
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
  huge: 64,
} as const;

export type SpacingKey = keyof typeof Spacing;

// ============================================
// Border Radius
// ============================================

export const BorderRadius = {
  xs: 4,
  sm: 6,
  md: 8,
  lg: 12,
  xl: 16,
  pill: 9999,
} as const;

export type BorderRadiusKey = keyof typeof BorderRadius;

// ============================================
// Font Feature Settings
// ============================================

export const FontFeatures = {
  /** Apply to body element for stylistic set substitution */
  ss01: '"ss01"',
  
  /** Apply to numeric/money cells for tabular figures */
  tnum: '"tnum"',
} as const;

// ============================================
// Component Defaults
// ============================================

export const ComponentDefaults = {
  button: {
    paddingX: Spacing.lg, // 16px
    paddingY: Spacing.sm, // 8px
    borderRadius: BorderRadius.pill,
  },
  
  input: {
    paddingX: Spacing.md, // 12px
    paddingY: Spacing.sm, // 8px
    borderRadius: BorderRadius.sm,
    borderWidth: 1,
    borderColor: Colors.hairlineInput,
    focusBorderColor: Colors.primary,
  },
  
  card: {
    padding: Spacing.xxl, // 32px
    borderRadius: BorderRadius.lg,
    borderWidth: 1,
    borderColor: Colors.hairline,
    shadow: 'rgba(0, 55, 112, 0.08) 0 1px 3px',
  },
} as const;

// ============================================
// Breakpoints
// ============================================

export const Breakpoints = {
  sm: '640px',   // Small screens (mobile)
  md: '768px',   // Medium screens (tablet)
  lg: '1024px',  // Large screens (desktop)
  xl: '1280px',  // Extra large screens
} as const;

export type BreakpointKey = keyof typeof Breakpoints;

// ============================================
// Type Exports
// ============================================

export interface ColorPalette {
  [key: string]: string;
}

export interface TypographyStyle {
  fontFamily: string;
  fontSize: number;
  fontWeight: number;
  lineHeight: number;
  letterSpacing: number;
}

export interface SpacingMap {
  [key: string]: number;
}

// Helper functions
export function getColor(key: ColorKey): string {
  return Colors[key];
}

export function getSpacing(key: SpacingKey): number {
  return Spacing[key];
}

export function getBorderRadius(key: BorderRadiusKey): number {
  return BorderRadius[key];
}

export function getTypography(key: TypographyKey): TypographyStyle {
  return Typography[key];
}

// ============================================
// Tailwind Utility Class Generators
// ============================================

/** 生成 Tailwind 任意值类名，如 twValue('p', '16px') -> "p-[16px]" */
export const twValue = (utility: string, value: number | string): string =>
  `${utility}-[${value}]`;

/** 间距类名生成器，如 twSpacing('px', 'lg') -> "px-[16px]" */
export const twSpacing = (
  utility: 'p' | 'px' | 'py' | 'm' | 'mx' | 'my' | 'gap',
  key: SpacingKey
): string => twValue(utility, `${Spacing[key]}px`);

/** 圆角类名生成器，pill 映射到 Tailwind 内置 rounded-full */
export const twRadius = (key: BorderRadiusKey): string =>
  key === 'pill' ? 'rounded-full' : twValue('rounded', `${BorderRadius[key]}px`);

/** 字号类名生成器 */
export const twText = (key: TypographyKey): string =>
  twValue('text', `${Typography[key].fontSize}px`);

/** 颜色类名生成器（text- / bg- / border-） */
export const twColor = (utility: 'text' | 'bg' | 'border', key: ColorKey): string =>
  twValue(utility, Colors[key]);

// ============================================
// CSS Variable Injection
// ============================================

/** 设计系统 CSS 变量映射（与 src/assets/main.css 保持一致） */
export const DesignSystemCssVars: Record<string, string> = {
  '--color-primary': Colors.primary,
  '--color-primary-deep': Colors.primaryDeep,
  '--color-primary-press': Colors.primaryPress,
  '--color-primary-soft': Colors.primarySoft,
  '--color-primary-bg-subdued-hover': Colors.primaryBgSubduedHover,
  '--color-brand-dark-900': Colors.brandDark900,
  '--color-ink': Colors.ink,
  '--color-ink-secondary': Colors.inkSecondary,
  '--color-ink-mute': Colors.inkMute,
  '--color-ink-mute-2': Colors.inkMute2,
  '--color-on-primary': Colors.onPrimary,
  '--color-canvas': Colors.canvas,
  '--color-canvas-soft': Colors.canvasSoft,
  '--color-canvas-cream': Colors.canvasCream,
  '--color-hairline': Colors.hairline,
  '--color-hairline-input': Colors.hairlineInput,
  '--color-ruby': Colors.ruby,
  '--color-magenta': Colors.magenta,
  '--color-lemon': Colors.lemon,
  '--color-shadow-blue': Colors.shadowBlue,
};

/** 将设计系统 CSS 变量注入指定元素（默认 :root） */
export function injectDesignSystemVars(el: HTMLElement = document.documentElement): void {
  Object.entries(DesignSystemCssVars).forEach(([name, value]) => {
    el.style.setProperty(name, value);
  });
}

/** 运行时设置单个 CSS 变量 */
export function setCssVar(name: string, value: string, el: HTMLElement = document.documentElement): void {
  el.style.setProperty(name, value);
}

// ============================================
// Theme Switching
// ============================================

/** 深色主题 CSS 变量覆盖（用于运行时主题切换） */
export const DarkThemeCssVars: Record<string, string> = {
  '--color-canvas': '#0d253d',
  '--color-canvas-soft': '#1c1e54',
  '--color-ink': '#ffffff',
  '--color-ink-secondary': '#b9b9f9',
  '--color-ink-mute': '#64748d',
  '--color-hairline': 'rgba(255, 255, 255, 0.08)',
};

/**
 * 应用主题（light/dark），先注入基础设计变量，再按需叠加深色覆盖。
 * 与 theme.ts 的 class 切换方案互补，可直接用于运行时精确控制变量。
 */
export function applyDesignTheme(
  mode: 'light' | 'dark',
  el: HTMLElement = document.documentElement
): void {
  injectDesignSystemVars(el);
  Object.entries(DarkThemeCssVars).forEach(([name, value]) => {
    el.style.setProperty(name, mode === 'dark' ? value : DesignSystemCssVars[name] ?? '');
  });
}
