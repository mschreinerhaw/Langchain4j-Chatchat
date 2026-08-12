import MarkdownIt from "markdown-it";
import { defineComponent, h, inject, onMounted, ref, watch } from "vue";
import { defineRegistry } from "@json-render/vue";
import VisualizationRenderer from "../../components/VisualizationRenderer.vue";
import { enterpriseUiCatalog } from "./catalog.js";

export const ARTIFACT_RESOURCE_LOADER = Symbol("artifact-resource-loader");
export const ARTIFACT_EVENT_DISPATCHER = Symbol("artifact-event-dispatcher");

const markdown = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
  breaks: true
});

const defaultLinkOpen = markdown.renderer.rules.link_open
  || ((tokens, index, options, env, self) => self.renderToken(tokens, index, options));
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  tokens[index].attrSet("target", "_blank");
  tokens[index].attrSet("rel", "noopener noreferrer");
  return defaultLinkOpen(tokens, index, options, env, self);
};

function resourceComponent(name, renderResource) {
  return defineComponent({
    name,
    props: {
      resourceId: { type: String, required: true },
      title: { type: String, default: "" },
      tone: { type: String, default: "info" }
    },
    setup(props) {
      const loader = inject(ARTIFACT_RESOURCE_LOADER, null);
      const dispatchArtifactEvent = inject(ARTIFACT_EVENT_DISPATCHER, null);
      const value = ref(null);
      const loading = ref(true);
      const error = ref("");

      async function load() {
        loading.value = true;
        error.value = "";
        try {
          value.value = loader ? await loader(props.resourceId) : null;
        } catch (loadError) {
          error.value = loadError?.message || "资源加载失败";
        } finally {
          loading.value = false;
        }
      }

      onMounted(load);
      watch(() => props.resourceId, load);
      return () => {
        if (loading.value) {
          return h("div", { class: "artifact-resource-state" }, "正在加载报告内容…");
        }
        if (error.value) {
          return h("div", { class: "artifact-resource-state error", role: "alert" }, error.value);
        }
        return renderResource(value.value, props, dispatchArtifactEvent);
      };
    }
  });
}

const MarkdownResource = resourceComponent("ArtifactMarkdown", (value) =>
  h("section", {
    class: "artifact-markdown message-markdown",
    innerHTML: markdown.render(String(value || ""))
  })
);

function sanitizeArtifactHtml(value = "") {
  if (typeof DOMParser === "undefined") {
    return "";
  }
  const document = new DOMParser().parseFromString(String(value || ""), "text/html");
  document.querySelectorAll("script, iframe, object, embed, base, meta, form").forEach((node) => node.remove());
  document.querySelectorAll("*").forEach((node) => {
    [...node.attributes].forEach((attribute) => {
      const name = attribute.name.toLowerCase();
      const content = String(attribute.value || "");
      if (name.startsWith("on") || /javascript\s*:/i.test(content)) {
        node.removeAttribute(attribute.name);
      }
    });
  });
  return document.body.innerHTML;
}

const HtmlResource = resourceComponent("ArtifactHtml", (value) =>
  h("section", {
    class: "artifact-html-document",
    innerHTML: sanitizeArtifactHtml(String(value || ""))
  })
);

const NoticeResource = resourceComponent("ArtifactNotice", (value, props) =>
  h("aside", { class: ["artifact-notice", `tone-${props.tone}`] }, [
    h("strong", props.title),
    h("div", { innerHTML: markdown.render(String(value || "")) })
  ])
);

const VisualizationResource = resourceComponent("ArtifactVisualization", (value, props, dispatchArtifactEvent) =>
  value && typeof value === "object"
    ? h(VisualizationRenderer, {
        spec: value,
        onDrillDown: (event) => dispatchArtifactEvent?.("drill-down", event, {
          resourceId: props.resourceId
        })
      })
    : h("div", { class: "artifact-resource-state" }, "暂无可视化数据")
);

const EvidenceResource = resourceComponent("ArtifactEvidence", (value, props) => {
  const citations = Array.isArray(value) ? value : [];
  return h("section", { class: "artifact-evidence" }, [
    h("h4", props.title),
    h("ol", citations.map((citation, index) => {
      const title = citation?.title || citation?.name || citation?.sourceRef || `证据 ${index + 1}`;
      const text = citation?.text || citation?.snippet || citation?.summary || "";
      const rawUrl = citation?.url || citation?.href || citation?.link || "";
      const url = /^https?:\/\//i.test(String(rawUrl)) ? String(rawUrl) : "";
      return h("li", { key: `${title}-${index}` }, [
        url
          ? h("a", { href: url, target: "_blank", rel: "noopener noreferrer" }, String(title))
          : h("strong", String(title)),
        text ? h("p", String(text)) : null
      ]);
    }))
  ]);
});

export const { registry: enterpriseUiRegistry } = defineRegistry(enterpriseUiCatalog, {
  components: {
    Report: ({ props, children }) => h("article", {
      class: "enterprise-ui-report",
      "data-status": props.status || undefined,
      "data-task-id": props.taskId || undefined
    }, children),
    Html: ({ props }) => h(HtmlResource, props),
    Markdown: ({ props }) => h(MarkdownResource, props),
    Notice: ({ props }) => h(NoticeResource, props),
    Visualization: ({ props }) => h(VisualizationResource, props),
    EvidenceList: ({ props }) => h(EvidenceResource, props)
  }
});
