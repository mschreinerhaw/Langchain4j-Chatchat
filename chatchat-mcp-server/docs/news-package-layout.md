# News package layout

`com.chatchat.mcpserver.news` owns news-source presets, extraction templates,
remote news-runtime access, financial enrichment, caching, MCP publication, and
administrative APIs.

| Package | Responsibility |
| --- | --- |
| `news.catalog` | Source presets, extraction patterns, collection templates, and preset seeding |
| `news.runtime` | Signed news-runtime HTTP client and news-search application service |
| `news.financial` | Financial enrichment and financial-query cache configuration, persistence, and storage |
| `news.tool` | Unified news/financial MCP tool providers and their internal bridge executor |
| `news.admin` | News collection and financial-cache administrative HTTP APIs |

## Placement rules

1. Keep the `news` root free of concrete types.
2. Keep source definitions and extraction/collection templates in `catalog`.
3. Keep remote transport concerns in `runtime` and financial enrichment/cache behavior in `financial`.
4. Keep MCP-facing adapters in `tool` and HTTP administration in `admin`.
5. Avoid generic `model`, `service`, `persistence`, and `util` packages when a functional owner exists.
