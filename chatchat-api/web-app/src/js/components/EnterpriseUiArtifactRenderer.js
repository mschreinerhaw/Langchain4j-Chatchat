import { defineComponent, computed, onErrorCaptured, provide, ref, watch } from "vue";
import { JSONUIProvider, Renderer } from "@json-render/vue";
import { fetchUiArtifact, fetchUiArtifactResource } from "../../services/api.js";
import {
  ARTIFACT_EVENT_DISPATCHER,
  ARTIFACT_VISUALIZATION_PREFERENCES,
  ARTIFACT_RESOURCE_LOADER,
  enterpriseUiRegistry,
} from "../ui-artifact/registry.js";

export default defineComponent({
  name: "EnterpriseUiArtifactRenderer",
  components: { JSONUIProvider, Renderer },
  props: {
    preferences: { type: Object, default: () => ({}) },
    artifact: {
      type: Object,
      required: true,
    },
  },
  emits: ["drill-down", "table-chart", "preference-change"],
  setup(componentProps, { expose: exposeComponent, emit: emitEvent }) {
    exposeComponent();
    const emit = emitEvent;

    const props = componentProps;

    const manifest = ref(null);
    const loading = ref(false);
    const error = ref("");
    const resourceCache = new Map();

    const artifactId = computed(() => String(props.artifact?.artifactId || ""));
    const spec = computed(() => manifest.value?.spec || null);

    function handleArtifactClick(event) {
      const chartTarget = event.target?.closest?.("[data-result-chart-payload]");
      if (!chartTarget) return;
      event.preventDefault();
      event.stopPropagation();
      emit("table-chart", chartTarget.dataset.resultChartPayload || "");
    }

    async function loadManifest() {
      manifest.value = null;
      resourceCache.clear();
      error.value = "";
      if (!artifactId.value) {
        error.value = "动态报告缺少 artifactId";
        return;
      }
      loading.value = true;
      try {
        const nextManifest = await fetchUiArtifact(artifactId.value);
        if (nextManifest?.schemaVersion !== "enterprise_ui_artifact_v1") {
          throw new Error("不支持的动态报告协议");
        }
        manifest.value = nextManifest;
      } catch (loadError) {
        error.value = loadError?.message || "动态报告加载失败";
      } finally {
        loading.value = false;
      }
    }

    async function loadResource(resourceId) {
      const key = String(resourceId || "");
      if (!key || !manifest.value?.resources?.[key]) {
        throw new Error(`动态报告资源不存在：${key}`);
      }
      if (!resourceCache.has(key)) {
        resourceCache.set(key, fetchUiArtifactResource(artifactId.value, key));
      }
      return resourceCache.get(key);
    }

    provide(ARTIFACT_RESOURCE_LOADER, loadResource);
    provide(
      ARTIFACT_VISUALIZATION_PREFERENCES,
      computed(() => props.preferences)
    );
    provide(ARTIFACT_EVENT_DISPATCHER, (eventName, payload = {}, context = {}) => {
      if (eventName === "preference-change") {
        emit("preference-change", payload);
        return;
      }
      if (eventName !== "drill-down") {
        return;
      }
      emit("drill-down", {
        ...payload,
        artifactId: artifactId.value,
        artifactResourceId: context.resourceId || "",
        artifactSchemaVersion: manifest.value?.schemaVersion || "enterprise_ui_artifact_v1",
      });
    });
    onErrorCaptured((renderError) => {
      error.value = renderError?.message || "动态报告渲染失败";
      return false;
    });
    watch(artifactId, loadManifest, { immediate: true });
    return { enterpriseUiRegistry, loading, error, artifactId, spec, handleArtifactClick };
  },
});
