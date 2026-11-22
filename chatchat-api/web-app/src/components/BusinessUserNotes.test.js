// @vitest-environment jsdom
import { createApp, h, nextTick } from "vue";
import { afterEach, expect, it, vi } from "vitest";
import BusinessUserNotes from "./BusinessUserNotes.vue";

let app;
let host;
afterEach(() => {
  app?.unmount();
  host?.remove();
  vi.restoreAllMocks();
});

it("lets a role member format, preview, save, publish and delete a note", async () => {
  const list = {
    notes: [{ id: "shared", title: "Shared note", body: "**Role update**", owned: false }],
    roles: [{ id: "analysts", name: "Analysts" }],
    page: 0,
    hasMore: false,
  };
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (url, request) => {
    switch (request.method) {
      case "POST":
        list.notes.unshift({ ...JSON.parse(request.body), id: "mine", revision: 0, owned: true });
        break;
      case "PUT":
        list.notes[0] = { ...list.notes[0], published: true, roleId: "analysts", revision: 1 };
        break;
      case "DELETE":
        list.notes.shift();
        break;
    }
    return new Response(JSON.stringify({ code: 200, data: request.method ? "mine" : list }));
  });
  host = document.body.appendChild(document.createElement("div"));
  app = createApp({ render: () => h(BusinessUserNotes, { panel: "guides" }) });
  app.mount(host);
  const click = (label, parent = host) => [...parent.querySelectorAll("button")]
    .find(button => button.textContent.trim().includes(label)).click();
  const enter = (selector, value) => {
    const input = host.querySelector(selector);
    input.value = value;
    input.dispatchEvent(new Event("input", { bubbles: true }));
    return input;
  };
  await vi.waitFor(() => expect(host.textContent).toContain("Shared note"));
  click("Shared note");
  await nextTick();
  expect(host.querySelector(".business-note-preview strong").textContent).toBe("Role update");
  expect(host.querySelector("textarea")).toBeNull();

  click("新建笔记");
  await nextTick();
  enter("input", "Weekly review");
  const editor = enter("textarea", "Checked");
  editor.setSelectionRange(0, 7);
  host.querySelector('[aria-label="粗体"]').click();
  await nextTick();
  click("预览");
  await nextTick();
  expect(host.querySelector(".business-note-preview strong").textContent).toBe("Checked");

  click("保存草稿");
  await vi.waitFor(() => expect(host.textContent).toContain("草稿已保存"));
  expect(fetch).toHaveBeenCalledWith("/api/v1/workspaces/business/notes/guides",
    expect.objectContaining({ method: "POST", body: JSON.stringify({ title: "Weekly review", body: "**Checked**", publishTo: null }) }));
  click("发布笔记");
  await vi.waitFor(() => expect(host.textContent).toContain("笔记已发布"));
  expect(fetch).toHaveBeenCalledWith("/api/v1/workspaces/business/notes/guides/mine",
    expect.objectContaining({ method: "PUT", body: JSON.stringify({
      title: "Weekly review", body: "**Checked**", revision: 0, publishTo: "analysts",
    }) }));

  click("删除笔记");
  await nextTick();
  click("删除笔记", document.querySelector("dialog"));
  await vi.waitFor(() => expect(host.textContent).not.toContain("Weekly review"));
  expect(fetch).toHaveBeenCalledWith("/api/v1/workspaces/business/notes/guides/mine?revision=1",
    expect.objectContaining({ method: "DELETE" }));
});
