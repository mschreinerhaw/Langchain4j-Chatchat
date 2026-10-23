import { effectScope, ref } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { useTransientNotice } from "./useTransientNotice.js";

let scope;
beforeEach(() => { vi.useFakeTimers(); scope = effectScope(); });
afterEach(() => { scope.stop(); vi.useRealTimers(); });
function noticeState() {
  const notice = ref(""), failed = ref(false);
  scope.run(() => useTransientNotice(notice, failed));
  return { notice, failed };
}
it("gives a replacement message its full display time", () => {
  const { notice } = noticeState();
  notice.value = "已刷新";
  vi.advanceTimersByTime(3000);
  notice.value = "已保存";
  vi.advanceTimersByTime(500);
  expect(notice.value).toBe("已保存");
  vi.advanceTimersByTime(3000);
  expect(notice.value).toBe("");
});
it("preserves errors and cancels pending dismissal when a success becomes an error", () => {
  const { notice, failed } = noticeState();
  notice.value = "已刷新";
  failed.value = true;
  notice.value = "保存失败";
  vi.advanceTimersByTime(10000);
  expect(notice.value).toBe("保存失败");
  expect(vi.getTimerCount()).toBe(0);
});
it("clears pending timers when the component scope is disposed", () => {
  const { notice } = noticeState();
  notice.value = "已保存";
  expect(vi.getTimerCount()).toBe(1);
  scope.stop();
  expect(vi.getTimerCount()).toBe(0);
});
