import { defineCatalog } from "@json-render/core";
import { schema } from "@json-render/vue/schema";
import { z } from "zod";

export const enterpriseUiCatalog = defineCatalog(schema, {
  components: {
    Report: {
      props: z.object({
        status: z.string().optional(),
        taskId: z.string().optional()
      }),
      description: "Enterprise report root container"
    },
    Markdown: {
      props: z.object({ resourceId: z.string() }),
      description: "Safe Markdown loaded from an artifact resource"
    },
    AnalyticalReport: {
      props: z.object({ resourceId: z.string() }),
      description: "Runtime composed insight blocks with bound data, charts, judgments and evidence"
    },
    Html: {
      props: z.object({ resourceId: z.string() }),
      description: "Sanitized HTML document loaded from an artifact resource"
    },
    Notice: {
      props: z.object({
        title: z.string(),
        tone: z.enum(["info", "warning", "error"]).optional(),
        resourceId: z.string()
      }),
      description: "A report notice backed by an artifact resource"
    },
    Visualization: {
      props: z.object({ resourceId: z.string() }),
      description: "A chart or table specification loaded on demand"
    },
    EvidenceList: {
      props: z.object({
        title: z.string(),
        resourceId: z.string()
      }),
      description: "Evidence and citations loaded on demand"
    }
  },
  actions: {}
});
