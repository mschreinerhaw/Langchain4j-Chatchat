import MarkdownIt from "markdown-it";
import { defineComponent, h, inject, onMounted, ref, watch } from "vue";
import { defineRegistry } from "@json-render/vue";
import VisualizationRenderer from "../../components/VisualizationRenderer.vue";
import { enterpriseUiCatalog } from "./catalog.js";
import { enhanceResultTables } from "../utils/resultTableEnhancer.js";
import { normalizeArtifactHtml } from "../utils/artifactHtmlNormalizer.js";
import { isInternalDocumentRef, stripInternalDocumentRefs } from "../utils/internalDocumentRefs.js";

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
    innerHTML: enhanceResultTables(markdown.render(stripInternalDocumentRefs(String(value || ""))))
  })
);

const HtmlResource = resourceComponent("ArtifactHtml", (value) =>
  h("section", {
    class: "artifact-html-document message-markdown",
    innerHTML: normalizeArtifactHtml(String(value || ""), (source) => markdown.render(source))
  })
);

const NoticeResource = resourceComponent("ArtifactNotice", (value, props) =>
  h("details", { class: ["artifact-notice", `tone-${props.tone}`] }, [
    h("summary", [
      h("strong", props.title),
      h("span", "展开查看")
    ]),
    h("div", {
      class: "artifact-notice-content",
      innerHTML: markdown.render(stripInternalDocumentRefs(String(value || "")))
    })
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

function evidenceTitle(citation, index) {
  const candidate = String(citation?.title || citation?.name || "").trim();
  const typeLabels = {
    TABLE_FACT: "表格证据",
    DOC_CHUNK: "文档证据",
    WEB_CHUNK: "网页证据"
  };
  const visibleCandidate = stripInternalDocumentRefs(candidate);
  const visibleSource = isInternalDocumentRef(citation?.sourceRef) ? "" : stripInternalDocumentRefs(citation?.sourceRef);
  return typeLabels[candidate.toUpperCase()] || visibleCandidate || visibleSource || `证据 ${index + 1}`;
}

const EvidenceResource = resourceComponent("ArtifactEvidence", (value, props) => {
  const citations = Array.isArray(value) ? value : [];
  return h("details", { class: "artifact-evidence" }, [
    h("summary", [
      h("h4", props.title),
      h("span", `${citations.length} 条`)
    ]),
    h("ol", citations.map((citation, index) => {
      const title = evidenceTitle(citation, index);
      const text = stripInternalDocumentRefs(citation?.text || citation?.snippet || citation?.summary || "");
      const sourceRef = citation?.sourceRef || citation?.source || "";
      const visibleSourceRef = isInternalDocumentRef(sourceRef) ? "" : stripInternalDocumentRefs(sourceRef);
      const rawUrl = citation?.url || citation?.href || citation?.link || "";
      const url = /^https?:\/\//i.test(String(rawUrl)) ? String(rawUrl) : "";
      const confidence = Number(citation?.confidence);
      const meta = [
        visibleSourceRef && visibleSourceRef !== title ? visibleSourceRef : "",
        Number.isFinite(confidence) && confidence > 0 ? `可信度 ${Math.round(confidence * 100)}%` : ""
      ].filter(Boolean).join(" · ");
      return h("li", { key: `${title}-${index}` }, [
        h("details", { class: "artifact-evidence-item" }, [
          h("summary", [
            h("span", { class: "artifact-evidence-rank" }, String(index + 1)),
            h("span", { class: "artifact-evidence-heading" }, [
              url
                ? h("a", { href: url, target: "_blank", rel: "noopener noreferrer" }, String(title))
                : h("strong", String(title)),
              meta ? h("small", meta) : null
            ])
          ]),
          text ? h("div", {
            class: "artifact-evidence-content message-markdown",
            innerHTML: markdown.render(String(text))
          }) : null
        ])
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
