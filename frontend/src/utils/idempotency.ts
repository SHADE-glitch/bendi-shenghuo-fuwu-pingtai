export const createIdempotencyKey = (scope: string): string => {
  const random = globalThis.crypto?.randomUUID?.() || Math.random().toString(36).slice(2);
  return `${scope}:${Date.now()}:${random}`;
};

export const idempotencyHeaders = (scope: string) => ({
  'Idempotency-Key': createIdempotencyKey(scope),
});
