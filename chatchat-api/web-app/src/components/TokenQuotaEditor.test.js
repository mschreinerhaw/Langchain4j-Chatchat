// @vitest-environment jsdom
import { createApp, h, nextTick, ref } from "vue";
import { expect, it, vi } from "vitest";
import { apiRequest } from "../services/api.js";
import TokenQuotaEditor from "./TokenQuotaEditor.vue";

vi.mock("../services/api.js", () => ({ apiRequest: vi.fn() }));

it("edits a personal quota in a separate dialog without submitting or clearing the account form", async () => {
  const pool = { limit: 100, period: 2, spent: 20, reserved: 10 };
  const history = { entries: [], hasNext: false, lastResetAt: null };
  apiRequest.mockResolvedValueOnce(pool).mockResolvedValueOnce(history)
    .mockResolvedValueOnce({ ...pool, limit: 250 }).mockResolvedValueOnce(history);
  const submitAccount = vi.fn((event) => event.preventDefault());
  const draft = ref("");
  const root = document.body.appendChild(document.createElement("div"));
  const app = createApp(() => h("form", { onSubmit: submitAccount }, [
    h("input", { value: draft.value, onInput: (event) => { draft.value = event.target.value; } }),
    h(TokenQuotaEditor, { type: "USER", principal: "person-1" }),
  ]));
  app.mount(root);
  try {
    const accountName = root.querySelector("input");
    accountName.value = "尚未保存的姓名";
    accountName.dispatchEvent(new Event("input"));
    const trigger = root.querySelector('button[aria-haspopup="dialog"]');
    const dialog = document.querySelector("dialog.token-quota-dialog");
    dialog.showModal = () => dialog.setAttribute("open", "");
    dialog.close = () => dialog.removeAttribute("open");
    expect(root.querySelector("dialog")).toBeNull();
    expect(apiRequest).not.toHaveBeenCalled();

    trigger.click();
    await vi.waitFor(() => expect(dialog.textContent).toContain("可用 70"));
    expect(dialog.open).toBe(true);
    const limit = dialog.querySelector('input[inputmode="numeric"]');
    limit.value = "250";
    limit.dispatchEvent(new Event("input"));
    await nextTick();
    dialog.querySelector("footer .primary-button").click();
    await vi.waitFor(() => expect(dialog.textContent).toContain("Token 额度已保存"));
    expect(apiRequest).toHaveBeenCalledWith("/permission-center/token-pools/USER/person-1", {
      method: "PUT", body: JSON.stringify({ unlimited: false, limit: 250, reset: false }),
    });

    dialog.dispatchEvent(new Event("cancel", { cancelable: true }));
    expect(dialog.open).toBe(false);
    expect(document.activeElement).toBe(trigger);
    expect(accountName.value).toBe("尚未保存的姓名");
    expect(submitAccount).not.toHaveBeenCalled();
  } finally {
    app.unmount();
    root.remove();
  }
});
