const DEFAULT_SCHEMA = {
    type: 'object',
    properties: {},
    required: [],
    additionalProperties: false
};

export function readServiceForm() {
    const microserviceConfig = readMicroserviceConfig();
    return {
        id: value('serviceId'),
        toolName: value('toolName'),
        title: value('title'),
        description: value('description'),
        method: value('method') || 'GET',
        urlTemplate: value('urlTemplate'),
        headers: microserviceConfig?.headers || parseJsonField('headersJson', {}),
        bodyTemplate: microserviceConfig?.bodyTemplate || value('bodyTemplate') || null,
        inputSchema: parseJsonField('inputSchemaJson', DEFAULT_SCHEMA),
        enabled: value('enabled') === 'true',
        timeoutMs: Number(value('timeoutMs') || 20000),
        cacheEnabled: value('cacheEnabled') === 'true',
        cacheTtlSeconds: Number(value('cacheTtlSeconds') || 300)
    };
}

export function fillServiceForm(service) {
    setValue('serviceId', service?.id || '');
    setValue('toolName', service?.toolName || '');
    setValue('title', service?.title || '');
    setValue('description', service?.description || '');
    setValue('method', service?.method || 'GET');
    setValue('urlTemplate', service?.urlTemplate || '');
    setValue('headersJson', stringify(service?.headers || {}));
    setValue('bodyTemplate', service?.bodyTemplate || '');
    setValue('inputSchemaJson', stringify(service?.inputSchema || DEFAULT_SCHEMA));
    setValue('enabled', String(service?.enabled ?? true));
    setValue('timeoutMs', String(service?.timeoutMs || 20000));
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
    const schema = parseJsonField('inputSchemaJson', DEFAULT_SCHEMA);
    return readTestArgsFromSchema(schema);
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
    return text;
}

function stringify(value) {
    return JSON.stringify(value, null, 2);
}

function value(id) {
    return document.getElementById(id).value.trim();
}

function setValue(id, nextValue) {
    document.getElementById(id).value = nextValue;
}

function labelFor(id) {
    return {
        headersJson: '请求头 JSON',
        inputSchemaJson: '入参 Schema JSON',
        microDataJson: 'data JSON 参数'
    }[id] || id;
}
