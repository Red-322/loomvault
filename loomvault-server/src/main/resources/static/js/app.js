/**
 * LoomVault control room.
 *
 * State lives in one plain object; every render is a pure function of it. Updates arrive from two
 * sources — user action and the SSE stream — and both funnel into `refresh()` so there is exactly
 * one code path that turns server state into pixels.
 *
 *   ├── rail          workflow catalogue, run history (polled lightly)
 *   └── stage         run header, metric strip, execution graph, journal, node inspector
 */

import { api, ApiError, EVENT_TYPES } from './api.js';
import { renderGraph } from './dag.js';

/* ── Sane defaults for the bundled example workflows ────────────────────────────────────── */

const SAMPLE_INPUTS = {
    'support-triage': { ticketId: 'TCK-1001' },
    'content-review': {
        draft: 'LoomVault is a durable execution engine for AI agent workflows. '
            + 'It journals every step so a crashed run can resume without repeating completed work. '
            + 'The engine enforces token and cost budgets, and it parks a run for human approval '
            + 'when the workflow asks for one.',
    },
    'reliability-drill': { subject: 'Q3 platform review' },
};

const state = {
    info: null,
    workflows: [],
    executions: [],
    selectedWorkflowId: null,
    selectedExecutionId: null,
    /** Definition of the currently displayed workflow (may differ from the run's version). */
    workflow: null,
    execution: null,
    journal: [],
    selectedNodeId: null,
    stream: null,
    refreshTimer: null,
    busy: false,
};

const $ = (id) => document.getElementById(id);

/* ── Formatting helpers ─────────────────────────────────────────────────────────────────── */

const fmtTime = (iso) => (iso ? new Date(iso).toLocaleTimeString([], { hour12: false }) : '—');
const fmtDuration = (ms) => {
    if (ms === null || ms === undefined) return '—';
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    return `${Math.floor(ms / 60_000)}m${Math.round((ms % 60_000) / 1000)}s`;
};
const fmtCost = (value) => {
    const n = Number(value ?? 0);
    if (!Number.isFinite(n) || n === 0) return '$0';
    return n < 0.01 ? `$${n.toFixed(6)}` : `$${n.toFixed(4)}`;
};
const fmtNumber = (value) => Number(value ?? 0).toLocaleString();

function toast(message, kind = '') {
    const box = document.createElement('div');
    box.className = `toast ${kind}`;
    box.textContent = message;
    $('toasts').append(box);
    setTimeout(() => box.remove(), kind === 'error' ? 7000 : 3800);
}

function describeError(error) {
    if (error instanceof ApiError) {
        return `${error.code}: ${error.message}`;
    }
    return error?.message || String(error);
}

/* ── Boot ───────────────────────────────────────────────────────────────────────────────── */

async function boot() {
    wireEvents();
    try {
        const [info, workflows, executions] = await Promise.all([
            api.info(), api.workflows(), api.executions(),
        ]);
        state.info = info;
        state.workflows = workflows;
        state.executions = executions;
        renderSystemFacts();
        renderWorkflows();
        renderRuns();

        // Prefer showing a run in progress; otherwise the newest one; otherwise a definition.
        const active = executions.find((e) => !e.terminal);
        if (active) {
            await selectExecution(active.id);
        } else if (executions.length > 0) {
            await selectExecution(executions[0].id);
        } else if (workflows.length > 0) {
            await selectWorkflow(workflows[0].id);
        }
    } catch (error) {
        toast(`Could not reach the server — ${describeError(error)}`, 'error');
    }
}

function wireEvents() {
    $('refresh-runs').addEventListener('click', () => refreshRuns(true));
    $('empty-run-demo').addEventListener('click', () => runWorkflow(state.selectedWorkflowId));
}

/* ── Rail ───────────────────────────────────────────────────────────────────────────────── */

function renderSystemFacts() {
    const info = state.info || {};
    const stats = state.info?.stats || {};
    const facts = [
        `<span class="fact">v<b>${esc(info.version || '?')}</b></span>`,
        `<span class="fact">${esc(info.llmProvider || 'unknown')}</span>`,
        `<span class="fact ${info.circuitState === 'CLOSED' ? 'ok' : 'warn'}">circuit <b>${esc(info.circuitState || '?')}</b></span>`,
        `<span class="fact">java <b>${esc(info.java || '?')}</b></span>`,
    ];
    $('system-facts').innerHTML = facts.join('');
}

function renderWorkflows() {
    const list = $('workflow-list');
    list.replaceChildren();
    $('workflow-count').textContent = String(state.workflows.length);

    if (state.workflows.length === 0) {
        list.innerHTML = '<li class="empty">No workflows registered</li>';
        return;
    }

    state.workflows.forEach((wf) => {
        const li = document.createElement('li');
        if (wf.id === state.selectedWorkflowId) li.className = 'active';

        const name = document.createElement('div');
        name.className = 'wf-name';
        name.textContent = wf.name || wf.id;

        const meta = document.createElement('div');
        meta.className = 'wf-meta';
        meta.textContent = `${wf.nodes} nodes · ${wf.edges} edges · v${wf.version}`;

        li.append(name, meta);
        li.addEventListener('click', () => selectWorkflow(wf.id));

        const runBtn = document.createElement('button');
        runBtn.className = 'ghost';
        runBtn.textContent = 'Run';
        runBtn.style.marginTop = '6px';
        runBtn.addEventListener('click', (event) => {
            event.stopPropagation();
            runWorkflow(wf.id);
        });
        li.append(runBtn);
        list.append(li);
    });
}

function renderRuns() {
    const list = $('run-list');
    list.replaceChildren();

    if (state.executions.length === 0) {
        list.innerHTML = '<li class="empty">No runs yet — press Run on a workflow</li>';
        return;
    }

    state.executions.forEach((run) => {
        const li = document.createElement('li');
        if (run.id === state.selectedExecutionId) li.className = 'active';

        const dot = document.createElement('span');
        dot.className = 'dot';
        dot.style.background = statusColor(run.status);
        if (!run.terminal) dot.classList.add('node-pulse');

        const label = document.createElement('div');
        const title = document.createElement('div');
        title.className = 'run-label';
        title.textContent = run.workflowId;
        const sub = document.createElement('div');
        sub.className = 'run-time';
        sub.textContent = `${fmtTime(run.createdAt)} · ${fmtDuration(run.elapsedMs)}`;
        label.append(title, sub);

        const cost = document.createElement('div');
        cost.className = 'run-time';
        cost.textContent = fmtCost(run.usage?.costUsd);

        li.append(dot, label, cost);
        li.addEventListener('click', () => selectExecution(run.id));
        list.append(li);
    });
}

function statusColor(status) {
    switch (status) {
        case 'SUCCEEDED': return 'var(--ok)';
        case 'FAILED': return 'var(--danger)';
        case 'BUDGET_EXCEEDED': return 'var(--warn)';
        case 'WAITING_APPROVAL': return 'var(--warn)';
        case 'RUNNING': return 'var(--info)';
        case 'CANCELLED': return 'var(--idle)';
        default: return 'var(--idle)';
    }
}

/* ── Selection ──────────────────────────────────────────────────────────────────────────── */

async function selectWorkflow(workflowId) {
    state.selectedWorkflowId = workflowId;
    state.selectedExecutionId = null;
    state.execution = null;
    state.journal = [];
    state.selectedNodeId = null;
    closeStream();

    try {
        state.workflow = await api.workflow(workflowId);
    } catch (error) {
        toast(`Could not load workflow — ${describeError(error)}`, 'error');
        return;
    }
    renderWorkflows();
    renderRuns();
    $('empty-state').classList.add('hidden');
    $('run-detail').classList.remove('hidden');

    $('run-title').textContent = state.workflow.name || state.workflow.id;
    $('run-sub').innerHTML = `<span class="chip s-pending">definition preview</span> `
        + `<span style="margin-left:8px">${esc(state.workflow.description || '')}</span>`;
    $('run-id').textContent = `${state.workflow.id} · v${state.workflow.version}`;
    renderActions();
    $('metrics').innerHTML = '';
    renderTimeline();
    renderNodeDetail();
    renderGraphToCanvas({});
}

async function selectExecution(executionId) {
    state.selectedExecutionId = executionId;
    state.selectedNodeId = null;
    closeStream();

    try {
        const [execution, journal] = await Promise.all([
            api.execution(executionId),
            api.journal(executionId),
        ]);
        state.execution = execution;
        state.journal = journal;
        state.selectedWorkflowId = execution.workflowId;
        try {
            state.workflow = await api.workflow(execution.workflowId);
        } catch {
            state.workflow = null;
        }
    } catch (error) {
        toast(`Could not load run — ${describeError(error)}`, 'error');
        return;
    }

    renderWorkflows();
    renderRuns();
    renderRunDetail();

    if (!state.execution.terminal) {
        openStream(executionId);
    }
}

function renderRunDetail() {
    const run = state.execution;
    if (!run) return;

    $('empty-state').classList.add('hidden');
    $('run-detail').classList.remove('hidden');

    $('run-title').textContent = state.workflow?.name || run.workflowId;
    $('run-sub').innerHTML =
        `<span class="chip s-${run.status.toLowerCase()}">${esc(run.status)}</span>`
        + (run.awaitingApproval ? ` <span class="chip s-waiting_approval">needs approval: ${esc(run.pendingApprovals.join(', '))}</span>` : '')
        + (run.resumeCount > 0 ? ` <span class="fact">resumed ×${run.resumeCount}</span>` : '');
    $('run-id').textContent = run.id;
    if (run.failureReason) {
        $('run-sub').innerHTML += `<div style="margin-top:6px;color:var(--danger);font-size:12px">`
            + `${esc(run.failureCode || '')} — ${esc(run.failureReason)}</div>`;
    }

    renderActions();
    renderMetrics();
    renderTimeline();
    renderNodeDetail();

    const states = {};
    (run.nodes || []).forEach((n) => { states[n.nodeId] = n; });
    renderGraphToCanvas(states);
}

function renderGraphToCanvas(states) {
    const svg = $('dag');
    if (!state.workflow) {
        svg.replaceChildren();
        $('dag-hint').textContent = 'workflow definition unavailable';
        return;
    }
    renderGraph(svg, state.workflow, states, (nodeId) => {
        state.selectedNodeId = nodeId;
        renderNodeDetail();
    });
    $('dag-hint').textContent = `${state.workflow.nodes.length} nodes · click a node for detail`;
}

/* ── Metrics ────────────────────────────────────────────────────────────────────────────── */

function renderMetrics() {
    const run = state.execution;
    if (!run) { $('metrics').innerHTML = ''; return; }

    const usage = run.usage || {};
    const budget = state.workflow?.budget || {};

    const cards = [
        metric('Status', run.status, run.terminal ? 'finished' : 'in flight', statusClass(run.status)),
        metric('Elapsed', fmtDuration(run.elapsedMs), run.finishedAt ? `ended ${fmtTime(run.finishedAt)}` : 'running', ''),
        metric('Tokens', fmtNumber(usage.totalTokens),
            `${fmtNumber(usage.inputTokens)} in · ${fmtNumber(usage.outputTokens)} out`, ''),
        metric('Cost', fmtCost(usage.costUsd),
            `budget ${budget.maxCostUsd ? fmtCost(budget.maxCostUsd) : 'unlimited'}`, 'accent'),
        metric('Model calls', fmtNumber(usage.llmCalls),
            `${fmtNumber(usage.cacheHits)} served from cache`, ''),
        metric('Nodes', `${(run.nodes || []).filter((n) => n.status === 'COMPLETED').length}/${(run.nodes || []).length}`,
            `${(run.nodes || []).filter((n) => n.status === 'SKIPPED').length} skipped`, ''),
    ];

    // Budget pressure bar — the single number an operator actually wants when a run is expensive.
    let budgetCard = '';
    if (budget.maxTotalTokens > 0) {
        const ratio = Math.min(1, (usage.totalTokens || 0) / budget.maxTotalTokens);
        const level = ratio >= 1 ? 'danger' : ratio >= (budget.warnThreshold || 0.8) ? 'warn' : '';
        budgetCard = `<div class="metric ${level}">
            <div class="label">Token budget</div>
            <div class="value">${Math.round(ratio * 100)}%</div>
            <div class="sub">${fmtNumber(usage.totalTokens)} of ${fmtNumber(budget.maxTotalTokens)} · on exceed: ${esc(budget.onExceed || '—')}</div>
            <div class="budget-bar ${level}"><i style="width:${(ratio * 100).toFixed(1)}%"></i></div>
        </div>`;
    }

    $('metrics').innerHTML = cards.join('') + budgetCard;
}

function metric(label, value, sub, cls) {
    return `<div class="metric ${cls}">
        <div class="label">${esc(label)}</div>
        <div class="value">${esc(String(value))}</div>
        <div class="sub">${esc(sub || '')}</div>
    </div>`;
}

function statusClass(status) {
    return ({ SUCCEEDED: 'ok', FAILED: 'danger', BUDGET_EXCEEDED: 'warn', WAITING_APPROVAL: 'warn', RUNNING: 'accent' })[status] || '';
}

/* ── Actions ────────────────────────────────────────────────────────────────────────────── */

function renderActions() {
    const box = $('run-actions');
    box.replaceChildren();

    if (state.execution) {
        const run = state.execution;

        if (run.awaitingApproval) {
            box.append(
                actionButton('Approve', 'primary', () => decide('approve')),
                actionButton('Reject', 'danger', () => decide('reject')),
            );
        }
        if (!run.terminal) {
            box.append(actionButton('Cancel', 'ghost', async () => {
                await guarded(() => api.cancel(run.id), 'Cancellation requested');
            }));
        }
        if (!run.terminal && !run.awaitingApproval) {
            box.append(actionButton('Resume', 'ghost', async () => {
                await guarded(() => api.resume(run.id), 'Resume requested');
            }));
        }
        box.append(actionButton('Replay (re-run)', 'ghost', () => {
            runWorkflowAction(run.workflowId, run.inputs);
        }));
    }

    if (state.workflow) {
        box.append(actionButton(`Run ${state.workflow.id}`, 'primary',
            () => runWorkflow(state.workflow.id)));
    }
}

function actionButton(label, kind, handler) {
    const button = document.createElement('button');
    button.className = kind;
    button.textContent = label;
    button.disabled = state.busy;
    button.addEventListener('click', handler);
    return button;
}

async function decide(kind) {
    const run = state.execution;
    if (!run) return;
    await guarded(
        () => (kind === 'approve'
            ? api.approve(run.id, 'operator', 'approved from control room')
            : api.reject(run.id, 'operator', 'rejected from control room')),
        kind === 'approve' ? 'Approved' : 'Rejected',
    );
}

async function runWorkflow(workflowId) {
    if (!workflowId) {
        toast('Select a workflow first', 'error');
        return;
    }
    await runWorkflowAction(workflowId, SAMPLE_INPUTS[workflowId] || {});
}

async function runWorkflowAction(workflowId, inputs) {
    await guarded(async () => {
        const run = await api.start(workflowId, inputs);
        toast(`Started ${workflowId}`, 'ok');
        return run;
    }, null, async (run) => {
        await refreshRuns(false);
        if (run?.id) await selectExecution(run.id);
    });
}

/** Runs an API call with busy-state handling, error toasting and a follow-up refresh. */
async function guarded(call, successMessage, after = null) {
    if (state.busy) return;
    state.busy = true;
    renderActions();
    try {
        const result = await call();
        if (successMessage) toast(successMessage, 'ok');
        if (after) {
            await after(result);
        } else if (state.selectedExecutionId) {
            await refreshExecution();
        }
    } catch (error) {
        toast(describeError(error), 'error');
    } finally {
        state.busy = false;
        renderActions();
    }
}

/* ── Live stream ────────────────────────────────────────────────────────────────────────── */

function openStream(executionId) {
    closeStream();
    const source = api.stream(executionId);
    state.stream = source;
    state.streamId = executionId;

    EVENT_TYPES.forEach((type) => {
        source.addEventListener(type, (event) => {
            if (type === 'connected') return;
            scheduleRefresh(executionId);
        });
    });

    source.onerror = () => {
        // The server closes the stream once the run reaches a terminal state; reconnect only
        // while the run is genuinely still active.
        if (state.execution && !state.execution.terminal) {
            scheduleRefresh(executionId);
        }
    };
}

function closeStream() {
    if (state.stream) {
        state.stream.close();
        state.stream = null;
    }
}

/** Coalesces bursts of events (a fan-out produces dozens) into one round-trip. */
function scheduleRefresh(executionId) {
    if (state.refreshTimer) return;
    state.refreshTimer = setTimeout(async () => {
        state.refreshTimer = null;
        if (state.selectedExecutionId !== executionId) return;
        await refreshExecution();
        if (state.execution?.terminal) {
            closeStream();
            await refreshRuns(false);
        }
    }, 220);
}

async function refreshExecution() {
    const id = state.selectedExecutionId;
    if (!id) return;
    try {
        const [execution, journal] = await Promise.all([api.execution(id), api.journal(id)]);
        if (state.selectedExecutionId !== id) return;
        state.execution = execution;
        state.journal = journal;
        renderRunDetail();
    } catch (error) {
        // A run that vanished (retention, restart) should not spam the toast stack.
        console.warn('refresh failed', error);
    }
}

async function refreshRuns(manual) {
    try {
        state.executions = await api.executions();
        renderRuns();
        if (manual) {
            renderSystemFacts();
            toast('Refreshed');
        }
    } catch (error) {
        if (manual) toast(describeError(error), 'error');
    }
}

/* ── Timeline & node inspector ──────────────────────────────────────────────────────────── */

function renderTimeline() {
    const list = $('timeline');
    list.replaceChildren();
    $('journal-count').textContent = state.journal.length ? `${state.journal.length} entries` : '';

    if (state.journal.length === 0) {
        list.innerHTML = '<li class="empty">No events recorded</li>';
        return;
    }

    state.journal.forEach((entry) => {
        const li = document.createElement('li');
        li.className = `t-${String(entry.type).toLowerCase()}`;

        const seq = document.createElement('div');
        seq.className = 'seq';
        seq.textContent = `#${entry.seq}`;

        const body = document.createElement('div');
        const type = document.createElement('div');
        type.className = 'etype';
        type.textContent = humanType(entry.type) + (entry.nodeId ? ` · ${entry.nodeId}` : '');
        body.append(type);

        const detail = summarize(entry);
        if (detail) {
            const line = document.createElement('div');
            line.className = 'edetail';
            line.textContent = detail;
            body.append(line);
        }

        const time = document.createElement('div');
        time.className = 'seq';
        time.textContent = fmtTime(entry.timestamp);
        body.append(time);

        li.append(seq, body);
        list.append(li);
    });
}

function humanType(raw) {
    return String(raw).toLowerCase().replaceAll('_', ' ');
}

function summarize(entry) {
    const p = entry.payload || {};
    switch (entry.type) {
        case 'EXECUTION_STARTED':
            return `workflow ${p.workflowId || '?'} · inputs ${JSON.stringify(p.inputs || {})}`;
        case 'EXECUTION_RESUMED':
            return `replayed ${p.replayedEntries ?? '?'} entries · restored ${(p.restoredNodes || []).length} nodes`;
        case 'EXECUTION_FINISHED':
            return `tokens ${p.usage?.totalTokens ?? '?'} · cost $${p.usage?.costUsd ?? '?'}`;
        case 'EXECUTION_STOPPED':
            return `${p.code || ''} ${p.reason || ''}`.trim();
        case 'NODE_STARTED':
        case 'NODE_SCHEDULED':
            return p.attempt ? `attempt ${p.attempt}` : (p.node || '');
        case 'NODE_COMPLETED':
            return `tokens ${p.usage?.totalTokens ?? 0}${p.cacheHit ? ' · cache hit' : ''} · ${p.attempt ?? 1} attempt(s)`;
        case 'NODE_FAILED':
            return `${p.error || ''}${p.retryable === false ? ' (not retryable)' : ''}`;
        case 'NODE_RETRY_SCHEDULED':
            return `retry in ${p.delayMs ?? '?'}ms`;
        case 'NODE_SKIPPED':
            return p.reason || '';
        case 'APPROVAL_REQUESTED':
            return String(p.summary || '').slice(0, 180);
        case 'APPROVAL_GRANTED':
            return `by ${p.approver || '?'}${p.comment ? ` — ${p.comment}` : ''}`;
        case 'APPROVAL_REJECTED':
            return p.reason || '';
        case 'BUDGET_WARNING':
        case 'BUDGET_EXCEEDED':
            return `${p.detail || ''}${p.action ? ` (${p.action})` : ''}`;
        case 'CACHE_HIT':
            return `model ${p.model || '?'} served from cache`;
        case 'RECOVERY_WARNING':
            return p.reason || '';
        default:
            return '';
    }
}

function renderNodeDetail() {
    const box = $('node-detail');
    const run = state.execution;
    if (!run || !state.selectedNodeId) {
        box.innerHTML = '<p class="empty">Click a node in the graph to inspect its input, output and usage.</p>';
        return;
    }
    const node = (run.nodes || []).find((n) => n.nodeId === state.selectedNodeId);
    if (!node) {
        box.innerHTML = '<p class="empty">Node not part of this run.</p>';
        return;
    }

    const usage = node.usage || {};
    box.innerHTML = `
        <dl class="kv">
            <dt>Node</dt><dd><b>${esc(node.name)}</b> <code>${esc(node.nodeId)}</code></dd>
            <dt>Kind</dt><dd>${esc(node.kind)}</dd>
            <dt>Status</dt><dd>${esc(node.status)}${node.attempt ? ` · attempt ${node.attempt}` : ''}</dd>
            <dt>Duration</dt><dd>${fmtDuration(node.durationMs)}</dd>
            <dt>Tokens</dt><dd>${fmtNumber(usage.totalTokens)} (${fmtNumber(usage.inputTokens)} in / ${fmtNumber(usage.outputTokens)} out)</dd>
            <dt>Cost</dt><dd>${fmtCost(usage.costUsd)}${usage.cacheHits ? ' · served from cache' : ''}</dd>
            <dt>Timeout</dt><dd>${esc(node.timeout || '—')}</dd>
            <dt>Retry-safe</dt><dd>${node.idempotent ? 'yes (idempotent)' : 'no — replay is warned'}</dd>
            <dt>Optional</dt><dd>${node.optional ? 'yes — failure does not abort the run' : 'no'}</dd>
        </dl>
        ${node.errorMessage ? `<div class="metric danger" style="margin-bottom:12px"><div class="label">Error</div><div class="value" style="font-size:13px;font-weight:500">${esc(node.errorCode || '')} ${esc(node.errorMessage)}</div></div>` : ''}
        <div class="label" style="margin:0 0 6px;font-size:10.5px;text-transform:uppercase;letter-spacing:.55px;color:var(--text-faint);font-weight:600">Output</div>
        <pre class="json">${esc(JSON.stringify(node.output || {}, null, 2))}</pre>
    `;
}

function esc(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}

/* ── Periodic light polling keeps the rail honest without an SSE firehose ────────────────── */

setInterval(() => {
    if (!document.hidden) refreshRuns(false);
}, 5000);

boot();
