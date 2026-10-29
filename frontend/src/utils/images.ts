const DEFAULT_IMAGE = '/imgs/shops/shop_1.jpg';

const normalize = (value: unknown): string[] => {
  if (!Array.isArray(value)) return [];
  return value
    .filter((item): item is string => typeof item === 'string')
    .map((item) => item.trim())
    .filter(Boolean);
};

export const parseImageList = (input: string | string[] | null | undefined): string[] => {
  if (Array.isArray(input)) {
    const parsed = normalize(input);
    return parsed.length ? parsed : [DEFAULT_IMAGE];
  }

  if (!input || typeof input !== 'string' || !input.trim()) {
    return [DEFAULT_IMAGE];
  }

  const value = input.trim();
  if (value.startsWith('[')) {
    try {
      const parsed = normalize(JSON.parse(value));
      if (parsed.length) return parsed;
    } catch {
      // Fall back to comma parsing for malformed backend data.
    }
  }

  const list = value
    .split(',')
    .map((item) => item.trim().replace(/^["']|["']$/g, ''))
    .filter(Boolean);

  return list.length ? list : [DEFAULT_IMAGE];
};

export const firstImage = (input: string | string[] | null | undefined): string => {
  return parseImageList(input)[0] || DEFAULT_IMAGE;
};

export const fallbackImage = DEFAULT_IMAGE;
