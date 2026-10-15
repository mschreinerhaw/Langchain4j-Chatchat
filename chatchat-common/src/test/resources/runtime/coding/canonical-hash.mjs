// Independent Node implementation of coding-canonical-json-v1. No SDK or npm dependencies.
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const pick = (value, keys) => Object.fromEntries(keys.map(key => [key, value[key]]));
const task = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const payload = pick(task, ['schemaVersion','taskId','scope','objective','inputSnapshotRef','metadataRefs',
  'workspaceRef','authorization','budget','environmentRef','validationPlanRef','outputContract']);
payload.scope = pick(task.scope, ['tenantId','userId','runId']);
// Existing KernelDataScope trims code units <= U+0020, not JavaScript's larger whitespace set.
for (const key of Object.keys(payload.scope)) payload.scope[key] = payload.scope[key].replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '');
payload.objective = pick(task.objective, ['instruction','deliverables','acceptanceCriteriaRefs']);
payload.authorization = pick(task.authorization, ['decisionRef','allowedTools','allowedDataRefs','readablePathRefs',
  'writablePathRefs','networkPolicy','networkAllowlist','allowedSideEffects']);
payload.budget = pick(task.budget, ['budgetId','deadlineAt','maxModelCalls','maxModelTokens','maxToolCalls',
  'maxCodeExecutions','maxCostAmount','costCurrency','maxCpuMillis','maxMemoryBytes','maxOutputBytes']);
payload.budget.deadlineAt = new Date(task.budget.deadlineAt).toISOString();
payload.outputContract = pick(task.outputContract, ['resultFormat','resultSchemaRef','validationSchemaRef','requiredFiles',
  'acceptedMediaTypes','maxResultRows','maxResultBytes','requiredEvidence']);
for (const [object, keys] of [
  [payload, ['metadataRefs']], [payload.objective, ['acceptanceCriteriaRefs']],
  [payload.authorization, ['allowedTools','allowedDataRefs','readablePathRefs','writablePathRefs','networkAllowlist','allowedSideEffects']],
  [payload.outputContract, ['requiredFiles','acceptedMediaTypes','requiredEvidence']],
]) for (const key of keys) object[key] = [...object[key]].sort();

function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value !== null && typeof value === 'object')
    return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}';
  if (typeof value === 'number' && (!Number.isSafeInteger(value) || value < 0)) throw Error('Unsafe integer');
  if (typeof value === 'string' && !value.isWellFormed()) throw Error('Invalid Unicode');
  if (value === undefined) throw Error('Missing required field');
  return JSON.stringify(value);
}
const canonicalJson = canonical(payload);
process.stdout.write(JSON.stringify({ canonicalJson, sha256: createHash('sha256').update(canonicalJson, 'utf8').digest('hex') }));
