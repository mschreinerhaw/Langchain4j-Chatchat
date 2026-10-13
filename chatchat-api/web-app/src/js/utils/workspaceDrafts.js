import { workspaceStorageKey } from "./workspaceContext";
const MAX_BYTES = 2 * 1024 * 1024;
export function saveWorkspaceDraft(namespace, context, value) {
  const key = workspaceStorageKey(namespace, context);
  if (!key) return false;
  try {
    const encoded = JSON.stringify({ version: 1, value });
    if (encoded.length > MAX_BYTES) return false;
    globalThis.localStorage?.setItem(key, encoded); return true;
  } catch (_) { return false; }
}
export function readWorkspaceDraft(namespace, context) {
  const key = workspaceStorageKey(namespace, context);
  if (!key) return null;
  try {
    const encoded = globalThis.localStorage?.getItem(key);
    if (!encoded || encoded.length > MAX_BYTES) return null;
    const stored = JSON.parse(encoded);
    return stored?.version === 1 ? stored.value : null;
  } catch (_) { return null; }
}
export function ownedPythonDraftTabs(tabs, scripts, assets) {
  if (!Array.isArray(tabs)) return [];
  const permittedScripts = new Map(scripts.map((script) => [script.id, script]));
  const permittedAssets = new Set(assets.map((asset) => asset.id));
  const keys = new Set();
  return tabs.slice(0, 20).filter((tab) => {
    if (!tab?.key || keys.has(tab.key) || !tab.form || typeof tab.form.sourceCode !== "string"
        || tab.form.sourceCode.length > 200000 || !permittedAssets.has(tab.form.assetId)) return false;
    if (tab.form.id && (!permittedScripts.has(tab.form.id)
        || permittedScripts.get(tab.form.id).assetId !== tab.form.assetId)) return false;
    keys.add(tab.key); return true;
  }).map((tab) => ({
    key: tab.key, form: { id: tab.form.id || "", assetId: tab.form.assetId, folderId: tab.form.folderId || "",
      fileName: String(tab.form.fileName || "").slice(0, 255), title: String(tab.form.title || "").slice(0, 255),
      sourceCode: tab.form.sourceCode, status: permittedScripts.get(tab.form.id)?.status || "draft" },
    savedSource: permittedScripts.get(tab.form.id)?.sourceCode || "",
    savedFileName: permittedScripts.get(tab.form.id)?.fileName || "", savedFolderId: permittedScripts.get(tab.form.id)?.folderId || "",
    consoleText: "", parametersText: String(tab.parametersText || "{}").slice(0, 10000),
    inputSchemaText: String(tab.inputSchemaText || "{}").slice(0, 10000), runState: "idle", runFeedback: null
  }));
}
