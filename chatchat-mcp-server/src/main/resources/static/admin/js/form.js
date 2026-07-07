const DEFAULT_SCHEMA = {
    type: 'object',
    properties: {},
    required: [],
    additionalProperties: false
};

const DEFAULT_MASK_FIELDS = ['phone', 'id_card', 'account_no'];
const DEFAULT_TIMEOUT_SECONDS = 20;

function secondsToMillis(value, fallbackSeconds = DEFAULT_TIMEOUT_SECONDS) {
    const seconds = Number(value || fallbackSeconds);
    return Math.max(1, Math.round(seconds)) * 1000;
}

function millisToSeconds(value, fallbackSeconds = DEFAULT_TIMEOUT_SECONDS) {
    const millis = Number(value || fallbackSeconds * 1000);
    return Math.max(1, Math.round(millis / 1000));
}

export function readServiceForm() {
    const microserviceConfig = readMicroserviceConfig();
    const inputSchema = readApiParamSchema();
    return {
        id: value('serviceId'),
        toolName: value('toolName'),
        title: value('title'),
        description: value('description'),
        businessGroup: value('businessGroup'),
        businessGroupName: value('businessGroupName'),
        businessGroupDescription: value('businessGroupDescription'),
        gatewayId: value('apiGatewaySelect') || null,
        method: value('method') || 'GET',
        urlTemplate: value('urlTemplate'),
        headers: microserviceConfig?.headers || parseJsonField('headersJson', {}),
        bodyTemplate: microserviceConfig?.bodyTemplate || value('bodyTemplate') || null,
        inputSchema,
        governance: parseJsonField('governanceJson', defaultApiGovernance()),
        enabled: value('enabled') === 'true',
        timeoutMs: secondsToMillis(value('timeoutMs')),
        cacheEnabled: value('cacheEnabled') === 'true',
        cacheTtlSeconds: Number(value('cacheTtlSeconds') || 300)
    };
}

export function fillServiceForm(service) {
    setValue('serviceId', service?.id || '');
    setValue('toolName', service?.toolName || '');
    setValue('title', service?.title || '');
    setValue('description', service?.description || '');
    setValue('businessGroup', service?.businessGroup || 'default');
    setValue('businessGroupName', service?.businessGroupName || '');
    setValue('businessGroupDescription', service?.businessGroupDescription || '');
    setValue('apiGatewaySelect', service?.gatewayId || '');
    setValue('method', service?.method || 'GET');
    setValue('urlTemplate', service?.urlTemplate || '');
    setValue('headersJson', stringify(service?.headers || {}));
    setValue('bodyTemplate', service?.bodyTemplate || '');
    renderApiParamEditor(service?.inputSchema || DEFAULT_SCHEMA);
    setValue('governanceJson', stringify(service?.governance || defaultApiGovernance(service)));
    setValue('enabled', String(service?.enabled ?? true));
    setValue('timeoutMs', String(millisToSeconds(service?.timeoutMs)));
    setValue('cacheEnabled', String(service?.cacheEnabled ?? false));
    setValue('cacheTtlSeconds', String(service?.cacheTtlSeconds || 300));
    fillMicroserviceFields(service);
}

export function toggleMicroserviceFields() {
    const enabled = document.getElementById('microserviceMode').checked;
    document.getElementById('microserviceFields').classList.toggle('d-none', !enabled);
    if (enabled) {
        setValue('method', 'POST');
    }
}

export function readTestArgs() {
    return readTestArgsFromSchema(readApiParamSchema());
}

export function bindApiParamEditor() {
    const addButton = document.getElementById('addApiParamBtn');
    const rows = document.getElementById('apiParamRows');
    if (!addButton || !rows) {
        return;
    }
    addButton.addEventListener('click', () => {
        addApiParamRow();
        syncApiParamSchema();
    });
    rows.addEventListener('click', event => {
        const button = event.target?.closest?.('[data-api-param-remove]');
        if (!button) {
            return;
        }
        button.closest('[data-api-param-row]')?.remove();
        updateApiParamEmptyState();
        syncApiParamSchema();
    });
    rows.addEventListener('input', () => syncApiParamSchema(false));
    rows.addEventListener('change', () => syncApiParamSchema(false));
}

export function readTestArgsFromSchema(schema = DEFAULT_SCHEMA) {
    const args = {};
    const properties = schema.properties || {};
    for (const [name, definition] of Object.entries(properties)) {
        const promptValue = window.prompt(`请输入参数 ${name}`, definition.default ?? '');
        if (promptValue !== null && promptValue !== '') {
            args[name] = coerceValue(promptValue, definition.type);
        }
    }
    return args;
}

function readMicroserviceConfig() {
    if (!document.getElementById('microserviceMode').checked) {
        return null;
    }
    const sessionId = value('microSessionId');
    const namespace = value('microNamespace');
    const amsToken = value('microAmsToken');
    const data = parseJsonField('microDataJson', {});

    const payload = {
        sessionId,
        namespace,
        head: {
            'x-ams-token': amsToken
        },
        data
    };

    return {
        headers: {
            'Content-Type': 'application/json;charset=UTF-8'
        },
        bodyTemplate: stringify(payload)
    };
}

function fillMicroserviceFields(service) {
    const parsed = tryParseJson(service?.bodyTemplate);
    const looksLikeMicroservice = parsed
        && Object.prototype.hasOwnProperty.call(parsed, 'sessionId')
        && Object.prototype.hasOwnProperty.call(parsed, 'namespace')
        && parsed.head
        && Object.prototype.hasOwnProperty.call(parsed.head, 'x-ams-token')
        && Object.prototype.hasOwnProperty.call(parsed, 'data');

    document.getElementById('microserviceMode').checked = Boolean(looksLikeMicroservice);
    if (looksLikeMicroservice) {
        setValue('microSessionId', parsed.sessionId || '');
        setValue('microNamespace', parsed.namespace || '');
        setValue('microAmsToken', parsed.head['x-ams-token'] || '');
        setValue('microDataJson', stringify(parsed.data || {}));
    } else {
        setValue('microSessionId', '');
        setValue('microNamespace', '');
        setValue('microAmsToken', '');
        setValue('microDataJson', stringify({
            etl_date: '',
            fund_code: ''
        }));
    }
    toggleMicroserviceFields();
}

function parseJsonField(id, fallback) {
    const text = value(id);
    if (!text) {
        return fallback;
    }
    try {
        return JSON.parse(text);
    } catch (error) {
        throw new Error(`${labelFor(id)} 不是合法 JSON`);
    }
}

function defaultApiGovernance(service = {}) {
    const operationType = operationTypeForMethod(service?.method || value('method') || 'GET');
    return {
        category: 'external_api',
        operation_type: operationType,
        risk_level: operationType === 'read' ? 'medium' : 'high',
        data_scope: 'external_service',
        user_visible: true,
        confirmation: {
            default: 'ask_before_execute',
            allow_user_override: true
        },
        permission: {
            roles: []
        },
        input_policy: {
            must_show_parameters: true,
            sensitive_params: [],
            parameter_rules: {}
        },
        output_policy: {
            mask_fields: DEFAULT_MASK_FIELDS
        },
        audit: {
            enabled: true,
            log_params: true,
            log_result_summary: true
        }
    };
}

function operationTypeForMethod(method) {
    const value = String(method || 'GET').toUpperCase();
    if (value === 'GET') {
        return 'read';
    }
    if (value === 'DELETE') {
        return 'delete';
    }
    return 'write';
}

function tryParseJson(text) {
    if (!text || !String(text).trim()) {
        return null;
    }
    try {
        return JSON.parse(text);
    } catch (error) {
        return null;
    }
}

function coerceValue(text, type) {
    if (type === 'number' || type === 'integer') {
        const number = Number(text);
        if (Number.isNaN(number)) {
            return text;
        }
        return type === 'integer' ? Math.trunc(number) : number;
    }
    if (type === 'boolean') {
        return text === 'true' || text === '1' || text === 'yes';
    }
    if (type === 'object' || type === 'array') {
        try {
            return JSON.parse(text);
        } catch (error) {
            return text;
        }
    }
    return text;
}

function stringify(value) {
    return JSON.stringify(value, null, 2);
}

function value(id) {
    const element = document.getElementById(id);
    return element ? element.value.trim() : '';
}

function renderApiParamEditor(schema = DEFAULT_SCHEMA) {
    const rows = document.getElementById('apiParamRows');
    if (!rows) {
        setValue('inputSchemaJson', stringify(schema || DEFAULT_SCHEMA));
        return;
    }
    rows.querySelectorAll('[data-api-param-row]').forEach(row => row.remove());
    const normalized = normalizeSchema(schema);
    const required = new Set(normalized.required || []);
    for (const [name, definition] of Object.entries(normalized.properties || {})) {
        addApiParamRow({
            name,
            type: definition.type || 'string',
            required: required.has(name),
            defaultValue: definition.default,
            exampleValue: Array.isArray(definition.examples) ? definition.examples[0] : definition.example,
            description: definition.description || ''
        });
    }
    updateApiParamEmptyState();
    syncApiParamSchema(false);
}

function addApiParamRow(param = {}) {
    const rows = document.getElementById('apiParamRows');
    const empty = document.getElementById('apiParamEmptyRow');
    if (!rows) {
        return;
    }
    const tr = document.createElement('tr');
    tr.dataset.apiParamRow = 'true';
    tr.innerHTML = `
        <td><input class="form-control form-control-sm api-param-name" data-api-param-name value="${escapeAttribute(param.name || '')}" placeholder="customer_id"></td>
        <td>
            <select class="form-select form-select-sm" data-api-param-type>
                ${apiParamTypeOptions(param.type || 'string')}
            </select>
        </td>
        <td class="api-param-required-cell"><input class="form-check-input" type="checkbox" data-api-param-required ${param.required ? 'checked' : ''}></td>
        <td><input class="form-control form-control-sm" data-api-param-default value="${escapeAttribute(formatApiParamValue(param.defaultValue))}"></td>
        <td><input class="form-control form-control-sm" data-api-param-example value="${escapeAttribute(formatApiParamValue(param.exampleValue))}"></td>
        <td><input class="form-control form-control-sm api-param-description" data-api-param-description value="${escapeAttribute(param.description || '')}" placeholder="参数业务含义"></td>
        <td class="text-end"><button class="btn btn-outline-danger btn-sm api-param-remove" type="button" data-api-param-remove aria-label="删除参数">×</button></td>
    `;
    rows.insertBefore(tr, empty || null);
    updateApiParamEmptyState();
}

function readApiParamSchema() {
    const rows = [...document.querySelectorAll('[data-api-param-row]')];
    if (!rows.length && !document.getElementById('apiParamRows')) {
        return parseJsonField('inputSchemaJson', DEFAULT_SCHEMA);
    }
    const schema = normalizeSchema({});
    const names = new Set();
    for (const row of rows) {
        const name = fieldValue(row, 'api-param-name');
        if (!name) {
            continue;
        }
        if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
            throw new Error(`API 参数名 ${name} 不合法，只能使用字母、数字和下划线，且不能以数字开头`);
        }
        if (names.has(name)) {
            throw new Error(`API 参数名 ${name} 重复`);
        }
        names.add(name);
        const type = fieldValue(row, 'api-param-type') || 'string';
        const definition = {
            type
        };
        const description = fieldValue(row, 'api-param-description');
        const defaultValue = fieldValue(row, 'api-param-default');
        const exampleValue = fieldValue(row, 'api-param-example');
        if (description) {
            definition.description = description;
        }
        if (defaultValue !== '') {
            definition.default = coerceValue(defaultValue, type);
        }
        if (exampleValue !== '') {
            definition.examples = [coerceValue(exampleValue, type)];
        }
        schema.properties[name] = definition;
        if (row.querySelector('[data-api-param-required]')?.checked) {
            schema.required.push(name);
        }
    }
    setValue('inputSchemaJson', stringify(schema));
    return schema;
}

function syncApiParamSchema(strict = true) {
    try {
        readApiParamSchema();
    } catch (error) {
        if (strict) {
            throw error;
        }
    }
}

function updateApiParamEmptyState() {
    const empty = document.getElementById('apiParamEmptyRow');
    const hasRows = Boolean(document.querySelector('[data-api-param-row]'));
    empty?.classList.toggle('d-none', hasRows);
}

function apiParamTypeOptions(selectedType) {
    return ['string', 'number', 'integer', 'boolean', 'object', 'array']
        .map(type => `<option value="${type}" ${type === selectedType ? 'selected' : ''}>${apiParamTypeLabel(type)}</option>`)
        .join('');
}

function apiParamTypeLabel(type) {
    return {
        string: '文本',
        number: '数字',
        integer: '整数',
        boolean: '布尔',
        object: '对象',
        array: '数组'
    }[type] || type;
}

function normalizeSchema(schema) {
    const next = schema && typeof schema === 'object' ? { ...schema } : {};
    next.type = 'object';
    next.properties = next.properties && typeof next.properties === 'object' ? next.properties : {};
    next.required = Array.isArray(next.required) ? next.required : [];
    next.additionalProperties = false;
    return next;
}

function fieldValue(row, name) {
    return row.querySelector(`[data-${name}]`)?.value?.trim() || '';
}

function formatApiParamValue(value) {
    if (value == null) {
        return '';
    }
    if (typeof value === 'object') {
        return JSON.stringify(value);
    }
    return String(value);
}

function escapeAttribute(value) {
    return String(value)
        .replaceAll('&', '&amp;')
        .replaceAll('"', '&quot;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;');
}

function setValue(id, nextValue) {
    const element = document.getElementById(id);
    if (element) {
        element.value = nextValue;
    }
}

function labelFor(id) {
    return {
        headersJson: '请求头 JSON',
        inputSchemaJson: '入参 Schema JSON',
        microDataJson: 'data JSON 参数'
    }[id] || id;
}
