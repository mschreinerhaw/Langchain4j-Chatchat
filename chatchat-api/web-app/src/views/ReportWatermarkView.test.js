// @vitest-environment jsdom
import { createApp, nextTick } from "vue";
import { expect, it, vi } from "vitest";
import { apiRequest } from "../services/api.js";
import ReportWatermarkView from "./ReportWatermarkView.vue";

vi.mock("../services/api.js", () => ({ apiRequest: vi.fn() }));

it("preserves role grants across pages and searches when saving the watermark policy", async () => {
  const roles = Array.from({ length: 21 }, (_, index) => ({
    id: `role-${index + 1}`,
    name: index === 20 ? "Finance 审核" : `业务角色 ${index + 1}`,
    enabled: true,
  }));
  const policy = { version: 1, watermarkEnabled: true, watermark: "企业内部资料", roleIds: ["role-2"] };
  apiRequest.mockResolvedValueOnce({ policy, roles }).mockResolvedValueOnce(policy);
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(ReportWatermarkView);
  app.mount(root);
  try {
    await Promise.resolve();
    await nextTick();
    const table = root.querySelector("tbody");
    const checkboxes = () => table.querySelectorAll('input[type="checkbox"]');
    expect(checkboxes()).toHaveLength(20);
    checkboxes()[0].click();
    root.querySelector(".permission-pagination button:last-child").click();
    await nextTick();
    expect(checkboxes()).toHaveLength(1);
    expect(table.textContent).toContain("Finance 审核");
    checkboxes()[0].click();

    const search = root.querySelector('input[type="search"]');
    search.value = " finance ";
    search.dispatchEvent(new Event("input"));
    await nextTick();
    expect(root.querySelector(".permission-pagination").textContent).toContain("1 / 1");
    expect(checkboxes()[0].checked).toBe(true);
    search.value = "不存在的角色";
    search.dispatchEvent(new Event("input"));
    await nextTick();
    expect(table.textContent).toContain("未找到匹配的角色");

    root.querySelector("form").dispatchEvent(new Event("submit", { cancelable: true }));
    const [, request] = apiRequest.mock.calls[1];
    expect(request.method).toBe("PUT");
    expect(JSON.parse(request.body).roleIds).toEqual(["role-2", "role-1", "role-21"]);
  } finally {
    app.unmount();
    root.remove();
  }
});
