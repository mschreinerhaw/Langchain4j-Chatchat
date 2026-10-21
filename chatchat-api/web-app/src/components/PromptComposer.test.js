// @vitest-environment jsdom
import { createApp, h, nextTick, reactive } from "vue";
import { it, expect, afterEach } from "vitest";
import PromptComposer from "./PromptComposer.vue";
let app, host;
afterEach(() => {
  app?.unmount();
  host?.remove();
});
it("keeps reports selected across sends and conversation changes until manually disabled", async () => {
  host = document.createElement("div");
  document.body.append(host);
  const sends = [],
    props = reactive({ modelValue: "Analyze this dataset", conversationKey: "one" });
  app = createApp({
    render: () => h(PromptComposer, { ...props, onSend: (value) => sends.push(value) }),
  });
  app.mount(host);
  await nextTick();
  const toggle = host.querySelector("[role=switch]");
  expect(toggle.textContent).toContain("图文报告");
  expect(toggle.getAttribute("aria-checked")).toBe("false");
  toggle.click();
  await nextTick();
  host
    .querySelector("form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTick();
  expect(sends[0].reportMode).toBe("visual");
  expect(toggle.getAttribute("aria-checked")).toBe("true");
  expect(sends[0].documentWorkflow).toBe(false);
  expect(sends[0]).not.toHaveProperty("webSearch");
  host
    .querySelector("form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTick();
  expect(sends[1].reportMode).toBe("visual");
  props.conversationKey = "two";
  await nextTick();
  expect(toggle.getAttribute("aria-checked")).toBe("true");
  expect(toggle.classList.contains("active")).toBe(true);
  host
    .querySelector("form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTick();
  expect(sends[2].reportMode).toBe("visual");
  toggle.click();
  await nextTick();
  expect(toggle.getAttribute("aria-checked")).toBe("false");
  expect(toggle.classList.contains("active")).toBe(false);
  host
    .querySelector("form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTick();
  expect(sends[3].reportMode).toBe("off");
});

it("preserves the selection during execution alongside the document workflow badge", async () => {
  host = document.createElement("div");
  document.body.append(host);
  const sends = [];
  const props = reactive({
    modelValue: "Analyze documents",
    loading: false,
    selectedAgentId: "document-agent",
    agents: [
      {
        id: "document-agent",
        name: "Document agent",
        marketStatus: "published",
        boundDocumentCount: 1,
      },
    ],
  });
  app = createApp({
    render: () => h(PromptComposer, { ...props, onSend: (value) => sends.push(value) }),
  });
  app.mount(host);
  await nextTick();
  const toggle = host.querySelector('[role="switch"]');
  expect(host.querySelector(".workflow-badge").textContent.trim()).toBe("文档工作流");
  toggle.click();
  await nextTick();
  props.loading = true;
  await nextTick();
  expect(toggle.disabled).toBe(true);
  toggle.click();
  host
    .querySelector("form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTick();
  expect(sends).toHaveLength(0);
  expect(toggle.getAttribute("aria-checked")).toBe("true");
  props.loading = false;
  await nextTick();
  host
    .querySelector("form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTick();
  expect(sends[0]).toMatchObject({ reportMode: "visual", documentWorkflow: true });
  expect(toggle.getAttribute("aria-checked")).toBe("true");
});
