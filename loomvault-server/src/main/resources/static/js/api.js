/**
 * Thin typed-ish wrapper over the LoomVault REST API.
 *
 * Every call funnels through request() so that error handling, the ApiError body shape and the
 * trace id are dealt with in exactly one place. The UI can then `catch` a single Error type and
 * show the server's `code` and `traceId` without inspecting HTTP status codes.
 */

export class ApiError extends Error {
    constructor(status, body) {
        super(body?.message || `HTTP ${status}`);
        this.status = status;
        this.code = body?.code || 'UNKNOWN';
        this.traceId = body?.traceId || '-';
        this.context = body?.context || {};
    }
}

async function request(path, { method = 'GET', body, signal } = {}) {
    const response = await fetch(path, {
        method,
        signal,
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body ? JSON.stringify(body) : undefined,
    });

    const text = await response.text();
    let payload = null;
    if (text) {
        try {
            payload = JSON.parse(text);
        } catch {
            payload = { message: text };
        }
    }

    if (!response.ok) {
        throw new ApiError(response.status, payload);
    }
    return payload;
}

export const api = {
    info: () => request('/api/system/info'),
    stats: () => request('/api/system/stats'),
    tools: () => request('/api/system/tools'),
    models: () => request('/api/system/models'),
    effects: () => request('/api/system/effects'),

    workflows: () => request('/api/workflows'),
    workflow: (id) => request(`/api/workflows/${encodeURIComponent(id)}`),

    executions: (limit = 25) => request(`/api/executions?limit=${limit}`),
    execution: (id) => request(`/api/executions/${encodeURIComponent(id)}`),
    journal: (id) => request(`/api/executions/${encodeURIComponent(id)}/journal`),

    /**
     * Starts a run. `wait=false` returns as soon as the engine accepts it, which is what the UI
     * wants: the graph then fills in over SSE instead of freezing the page for seconds.
     */
    start: (workflowId, inputs, { wait = false, executionId } = {}) =>
        request(`/api/executions?wait=${wait}`, {
            method: 'POST',
            body: { workflowId, inputs, executionId },
        }),

    approve: (id, approver, comment) =>
        request(`/api/executions/${encodeURIComponent(id)}/approve`, {
            method: 'POST',
            body: { approver, comment },
        }),

    reject: (id, approver, comment) =>
        request(`/api/executions/${encodeURIComponent(id)}/reject`, {
            method: 'POST',
            body: { approver, comment },
        }),

    resume: (id) =>
        request(`/api/executions/${encodeURIComponent(id)}/resume`, { method: 'POST' }),

    cancel: (id) =>
        request(`/api/executions/${encodeURIComponent(id)}/cancel`, { method: 'POST' }),

    /** Opens the live event stream for one execution. Caller owns the returned EventSource. */
    stream: (id) => new EventSource(`/api/executions/${encodeURIComponent(id)}/events`),
};

/** Every named SSE event the engine can emit, so the UI can subscribe to all of them. */
export const EVENT_TYPES = [
    'connected', 'execution_started', 'execution_resumed', 'execution_finished',
    'execution_stopped', 'node_scheduled', 'node_started', 'node_completed', 'node_failed',
    'node_retry_scheduled', 'node_skipped', 'approval_requested', 'approval_granted',
    'approval_rejected', 'budget_warning', 'budget_exceeded', 'cache_hit', 'recovery_warning',
];
