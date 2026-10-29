import { afterEach, beforeEach, vi } from 'vitest';
import { config } from '@vue/test-utils';
import Vant from 'vant';

config.global.plugins = [Vant];

// van-nav-bar 的 safe-area-inset-top 会让 vant 在挂载后调度 100/200/300ms 的
// setTimeout(setHeight)，而 vant 卸载时并不清理它们（见 vant 的 useHeight）。
// 测试文件跑完、jsdom 已被拆除后这些定时器才触发，回调里读 window 就抛
// ReferenceError: window is not defined —— 测试全过但 vitest 退出码为 1，CI 偶发变红
// （本地机器快，定时器通常在拆除前就触发了，所以本地复现不出来）。
//
// 把 setTimeout 换成假定时器并在每个用例后清空，这些定时器就再没有机会在拆除后触发。
// 只 fake setTimeout / clearTimeout，不碰 setImmediate：@vue/test-utils 的 flushPromises
// 优先用 setImmediate，一起 fake 掉会让所有 await flushPromises() 挂死。
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
});

afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
});
