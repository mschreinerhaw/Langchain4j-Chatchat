// @vitest-environment jsdom
import { createApp, nextTick } from 'vue';
import { afterEach, describe, expect, it } from 'vitest';
import AgentWorkshopView from './AgentWorkshopView.vue';

let app, host;
afterEach(() => { app?.unmount(); host?.remove(); });

async function mountEditor(agent) {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({ ...AgentWorkshopView, mounted() {} });
  const vm = app.mount(host);
  vm.resourceOptions = [{ id: 'knowledge_documents', label: '企业知识文档' }];
  if (agent) vm.form = vm.agentToForm(agent);
  vm.dialogOpen = true;
  await nextTick();
  return vm;
}

describe('Agent resource configuration flow', () => {
  it('reveals document scope before strategy only after opting in, retaining choices when collapsed', async () => {
    const vm = await mountEditor();
    const resources = host.querySelector('[aria-label="可查阅的资料"]');
    expect(resources.querySelector('.agent-resource-settings')).toBeNull();
    resources.querySelector('input[type=checkbox]').click();
    await nextTick();
    const documents = resources.querySelector('.agent-resource-documents');
    const strategy = resources.querySelector('select');
    expect(documents).not.toBeNull();
    expect(documents.compareDocumentPosition(strategy) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    vm.form.boundDocumentIds = ['doc-1'];
    strategy.value = 'explicit_request';
    strategy.dispatchEvent(new Event('change', { bubbles: true }));
    await nextTick();
    resources.querySelector('input[type=checkbox]').click();
    await nextTick();
    expect(resources.querySelector('.agent-resource-settings')).toBeNull();
    expect(vm.form.boundDocumentIds).toEqual(['doc-1']);
    resources.querySelector('input[type=checkbox]').click();
    await nextTick();
    expect(resources.querySelector('select').value).toBe('explicit_request');
    expect(resources.querySelector('.agent-resource-selector-action').textContent).toContain('已选 1 份');
  });

  it('restores enabled persisted configuration and shows only relevant resource settings', async () => {
    const vm = await mountEditor({
      id: 'agent', name: 'Agent', boundDocumentIds: ['saved-document'],
      workflowConfig: { resourceExploration: {
        resourcePermissions: [{ resourceId: 'knowledge_documents', enabled: true, access: 'read' }],
        selectionStrategy: 'explicit_request'
      } }
    });
    const resources = host.querySelector('[aria-label="可查阅的资料"]');
    expect(resources.querySelector('input[type=checkbox]').checked).toBe(true);
    expect(resources.querySelector('.agent-resource-documents')).not.toBeNull();
    expect(resources.querySelector('select').value).toBe('explicit_request');
    expect(resources.querySelector('.agent-resource-selector-action').textContent).toContain('已选 1 份');
    vm.resourceOptions.push({ id: 'financial_news', label: '财经新闻' });
    vm.form.resourceIds = ['financial_news'];
    await nextTick();
    expect(resources.querySelector('.agent-resource-documents')).toBeNull();
    expect(resources.querySelector('select')).not.toBeNull();
    vm.form.resourceIds = [];
    await nextTick();
    expect(resources.querySelector('.agent-resource-settings')).toBeNull();
  });
});
