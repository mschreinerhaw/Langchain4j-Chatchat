import manifest from '../../../../../chatchat-common/src/main/resources/runtime/visualization-capabilities.json';

// These adapters are registered by VisualizationRenderer; the shared manifest supplies their contract.
const registeredAdapters = new Set(['line', 'bar', 'pie', 'scatter', 'kpi', 'table']);
export const visualizationCapabilities = manifest.capabilities.filter(capability => registeredAdapters.has(capability.rendererType));
export const registeredGraphTypes = new Set(visualizationCapabilities
  .filter(capability => !['metric', 'table'].includes(capability.type)).map(capability => capability.rendererType));

export function renderableReportBlock(block) {
  const capability = visualizationCapabilities.find(item => item.type === block?.chartType);
  const spec = block?.visualizationSpec;
  return block?.schemaVersion === 'report_block.v1' && block.validationStatus === 'VERIFIED_SOURCE_DATA'
    && capability && spec?.validationStatus === 'VERIFIED_SOURCE_DATA'
    && spec.chartType === capability.rendererType && block.datasetRef === spec.dataset?.sourceRef
    && Array.isArray(spec.dataset?.rows) && spec.dataset.rows.length > 0 && spec.dataset.rows.length <= manifest.maxRows;
}
