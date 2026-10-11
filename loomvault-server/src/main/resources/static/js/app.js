/**
 * LoomVault control room — application shell.
 *
 * One plain state object; every render is a pure function of it. Updates arrive from two sources
 * (user action and the SSE stream) and both funnel into `refreshExecution()`, so there is exactly
 * one code path that turns server state into pixels.
 *
 * The UI is bilingual: the `lang` switch re-applies the static string table and re-renders the
 * dynamic parts in place — no page reload, no server round-trip.
 */

import { api, ApiError, EVENT_TYPES } from './api.js';
import { renderGraph, renderLegend } from './dag.js';
import { STRINGS, EVENT_LABEL, POLICY_LABEL } from './i18n.js';

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

/**
 * Language and theme resolve in this order: URL override → saved preference → default.
 * The URL override exists so a specific view can be linked directly, e.g.
 *   /?theme=light&lang=en   for a screenshot or a shared link
 */
const bootParams = new URLSearchParams(location.search);
const urlLang = bootParams.get('lang');
const urlTheme = bootParams.get('theme');

const state = {
    lang: urlLang === 'en' || urlLang === 'zh' ? urlLang : (localStorage.getItem('lv-lang') || 'zh'),
    theme: urlTheme === 'light' || urlTheme === 'dark' ? urlTheme : (localStorage.getItem('lv-theme') || 'dark'),
    info: null,
    workflows: [],
    executions: [],
    selectedWorkflowId: null,
    selectedExecutionId: null,
    workflow: null,
    execution: null,
    journal: [],
    selectedNodeId: null,
    stream: null,
    streamId: null,
    busy: false,
    refreshTimer: null,
};

const $ = (id) => document.getElementById(id);

/* ── i18n ───────────────────────────────────────────────────────────────────────────────── */

function t(key, vars) {
    const table = STRINGS[state.lang] || STRINGS.zh;
    const raw = table[key] !== undefined ? table[key] : (STRINGS.en[key] ?? key);
    return vars ? raw.replace(/\{(\w+)\}/g, (_, k) => (k in vars ? String(vars[k]) : `{${k}}`)) : raw;
}

function applyStaticI18n() {
    document.querySelectorAll('[data-i18n]').forEach((el) => {
        el.textContent = t(el.dataset.i18n);
    });
    document.querySelectorAll('[data-i18n-title]').forEach((el) => {
        el.title = t(el.dataset.i18nTitle);
    });
}

/* ── Formatting ─────────────────────────────────────────────────────────────────────────── */

const fmtTime = (iso) => (iso ? new Date(iso).toLocaleTimeString([], { hour12: false }) : '—');

function fmtDuration(ms) {
    if (ms === null || ms === undefined) return t('misc.none');
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    const m = Math.floor(ms / 60_000);
    if (m < 60) return `${m}m${Math.round((ms % 60_000) / 1000)}s`;
    return `${Math.floor(m / 60)}h${m % 60}m`;
}

function fmtCost(v) {
    const n = Number(v ?? 0);
    if (!Number.isFinite(n) || n === 0) return '$0';
    return n < 0.01 ? `$${n.toFixed(6)}` : `$${n.toFixed(4)}`;
}

const fmtNum = (v) => Number(v ?? 0).toLocaleString();

function fmtRelative(iso) {
    if (!iso) return '';
    const diff = (Date.now() - new Date(iso).getTime()) / 1000;
    if (diff < 10) return t('time.justNow');
    if (diff < 60) return t('time.secAgo', { n: Math.floor(diff) });
    if (diff < 3600) return t('time.minAgo', { n: Math.floor(diff / 60) });
    if (diff < 86400) return t('time.hourAgo', { n: Math.floor(diff / 3600) });
    return t('time.dayAgo', { n: Math.floor(diff / 86400) });
}

function esc(v) {
    return String(v ?? '')
        .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}

function interpolate(template, vars) {
    return template.replace(/\{(\w+)\}/g, (_, k) => (k in vars ? String(vars[k]) : `{${k}}`));
}

/* ── Boot ───────────────────────────────────────────────────────────────────────────────── */

async function boot() {
    document.documentElement.dataset.theme = state.theme;
    document.documentElement.lang = state.lang === 'zh' ? 'zh-CN' : 'en';
    $('lang-btn').textContent = state.lang === 'zh' ? 'EN' : '中文';
    applyStaticI18n();
    wireEvents();

    try {
        const [info, workflows, executions] = await Promise.all([
            api.info(), api.workflows(), api.executions(),
        ]);
        state.info = info;
        state.workflows = workflows;
        state.executions = executions;

        renderFacts();
        renderSidebar();

        const active = executions.find((e) => !e.terminal);
        if (active) await selectExecution(active.id);
        else if (executions.length) await selectExecution(executions[0].id);
        else if (workflows.length) await selectWorkflow(workflows[0].id);
    } catch (error) {
        toast(t('toast.loadFailed', { msg: describeError(error) }), 'error');
    }
}

function wireEvents() {
    $('refresh-btn').addEventListener('click', async () => {
        await refreshRuns();
        renderFacts();
        toast(t('action.refresh.done'));
    });

    $('theme-btn').addEventListener('click', () => {
        state.theme = state.theme === 'dark' ? 'light' : 'dark';
        document.documentElement.dataset.theme = state.theme;
        localStorage.setItem('lv-theme', state.theme);
    });

    $('lang-btn').addEventListener('click', () => {
        state.lang = state.lang === 'zh' ? 'en' : 'zh';
        localStorage.setItem('lv-lang', state.lang);
        document.documentElement.lang = state.lang === 'zh' ? 'zh-CN' : 'en';
        $('lang-btn').textContent = state.lang === 'zh' ? 'EN' : '中文';
        applyStaticI18n();
        renderFacts();
        renderSidebar();
        if (state.execution) renderRunDetail();
        else if (state.workflow) renderPreview();
        renderWelcomeIfVisible();
    });

    $('welcome-run').addEventListener('click', () => runWorkflow(state.selectedWorkflowId || 'support-triage'));
}

/* ── Top bar ────────────────────────────────────────────────────────────────────────────── */

function renderFacts() {
    const info = state.info;
    if (!info) return;
    const box = $('appbar-facts');
    box.replaceChildren();

    const facts = [
        { text: `v${info.version}`, cls: '' },
        { text: info.llmProvider, cls: '' },
        {
            text: `${t('stats.status')}: ${t(`status.${info.circuitState}`)}`,
            cls: info.circuitState === 'CLOSED' ? 'fact--ok' : 'fact--warn',
            dot: true,
        },
        { text: `Java ${info.java}`, cls: '' },
    ];
    facts.forEach((f) => {
        const span = document.createElement('span');
        span.className = `fact ${f.cls}`.trim();
        if (f.dot) {
            const d = document.createElement('span');
            d.className = 'dot';
            span.append(d);
        }
        span.append(document.createTextNode(f.text));
        box.append(span);
    });
}

/* ── Sidebar ────────────────────────────────────────────────────────────────────────────── */

function renderSidebar() {
    renderRuns();
    renderWorkflows();
}

function renderRuns() {
    const box = $('run-list');
    box.replaceChildren();
    $('run-count').textContent = String(state.executions.length);

    if (!state.executions.length) {
        box.append(emptyNote(t('side.empty.runs')));
        return;
    }

    state.executions.forEach((run, i) => {
        const item = document.createElement('div');
        item.className = 'runitem' + (run.id === state.selectedExecutionId ? ' is-active' : '');
        item.style.animationDelay = `${Math.min(i * 18, 220)}ms`;

        const bar = document.createElement('span');
        bar.className = 'runitem-bar';
        bar.style.color = statusColor(run.status);

        const body = document.createElement('div');
        body.className = 'runitem-body';
        const name = document.createElement('div');
        name.className = 'runitem-name';
        name.textContent = run.workflowId;
        const meta = document.createElement('div');
        meta.className = 'runitem-meta';
        const dot = document.createElement('span');
        dot.textContent = statusDotClass(run.status);
        const when = document.createElement('span');
        when.textContent = fmtRelative(run.createdAt);
        const dur = document.createElement('span');
        dur.textContent = fmtDuration(run.elapsedMs);
        meta.append(dot, when, dur);
        body.append(name, meta);

        const cost = document.createElement('div');
        cost.className = 'runitem-cost';
        cost.textContent = fmtCost(run.usage?.costUsd);

        item.append(bar, body, cost);
        item.addEventListener('click', () => selectExecution(run.id));
        box.append(item);
    });
}

function renderWorkflows() {
    const box = $('workflow-list');
    box.replaceChildren();
    $('workflow-count').textContent = String(state.workflows.length);

    if (!state.workflows.length) {
        box.append(emptyNote(t('side.empty.workflows')));
        return;
    }

    state.workflows.forEach((wf) => {
        const item = document.createElement('div');
        item.className = 'wfitem' + (wf.id === state.selectedWorkflowId ? ' is-active' : '');

        const name = document.createElement('div');
        name.className = 'wfitem-name';
        name.append(document.createTextNode(wf.name || wf.id));

        const runBtn = document.createElement('button');
        runBtn.className = 'ghostbtn';
        runBtn.style.marginLeft = 'auto';
        runBtn.style.padding = '2px 9px';
        runBtn.style.fontSize = '11px';
        runBtn.textContent = t('btn.run');
        runBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            runWorkflow(wf.id);
        });
        name.append(runBtn);

        const sub = document.createElement('div');
        sub.className = 'wfitem-sub';
        sub.textContent = `${wf.nodes} nodes · ${wf.edges} edges · v${wf.version}`;

        item.append(name, sub);
        item.addEventListener('click', () => selectWorkflow(wf.id));
        box.append(item);
    });
}

function statusColor(status) {
    return ({
        SUCCEEDED: 'var(--ok)',
        FAILED: 'var(--danger)',
        BUDGET_EXCEEDED: 'var(--warn)',
        WAITING_APPROVAL: 'var(--warn)',
        RUNNING: 'var(--info)',
    })[status] || 'var(--idle)';
}

function statusDotClass(status) {
    return ({ SUCCEEDED: '✓', FAILED: '✕', WAITING_APPROVAL: '⏸', RUNNING: '▶' })[status] || '·';
}

function emptyNote(text) {
    const div = document.createElement('div');
    div.className = 'empty-note';
    div.textContent = text;
    return div;
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
        toast(t('toast.loadFailed', { msg: describeError(error) }), 'error');
        return;
    }
    renderSidebar();
    renderPreview();
}

function renderPreview() {
    showDetail();
    const wf = state.workflow;
    $('run-title').textContent = wf.name || wf.id;
    $('run-status').className = 'statusbadge s-PENDING';
    $('run-status').textContent = state.lang === 'zh' ? '定义预览' : 'definition preview';
    $('run-meta').replaceChildren();
    $('run-id').textContent = `${wf.id} · v${wf.version}`;
    $('run-notice').classList.add('is-hidden');
    $('stats').replaceChildren();
    $('journal').replaceChildren(emptyNote(t('log.empty')));
    $('journal-hint').textContent = '';
    renderActions();
    renderNodeDetail();
    renderGraphInto({}, true);
}

async function selectExecution(executionId) {
    state.selectedExecutionId = executionId;
    state.selectedNodeId = null;
    closeStream();

    try {
        const [execution, journal] = await Promise.all([
            api.execution(executionId), api.journal(executionId),
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
        toast(t('toast.loadFailed', { msg: describeError(error) }), 'error');
        return;
    }

    renderSidebar();
    renderRunDetail();
    if (!state.execution.terminal) openStream(executionId);
}

/* ── Run detail ─────────────────────────────────────────────────────────────────────────── */

function showDetail() {
    $('welcome').classList.add('is-hidden');
    $('detail').classList.remove('is-hidden');
}

function renderWelcomeIfVisible() {
    if (!$('welcome').classList.contains('is-hidden')) applyStaticI18n();
}

function renderRunDetail() {
    const run = state.execution;
    if (!run) return;
    showDetail();

    $('run-title').textContent = state.workflow?.name || run.workflowId;
    $('run-status').className = `statusbadge s-${run.status}`;
    $('run-status').textContent = t(`status.${run.status}`);
    $('run-id').textContent = run.id;

    // Meta chips: version, resume count, approval hint
    const meta = $('run-meta');
    meta.replaceChildren();
    const chips = [];
    chips.push(chip(`v${run.workflowVersion}`));
    if (run.resumeCount > 0) chips.push(chip(state.lang === 'zh' ? `恢复 ${run.resumeCount} 次` : `resumed ×${run.resumeCount}`, 'warn'));
    if (run.awaitingApproval) chips.push(chip(t('notice.approval', { nodes: run.pendingApprovals.join(', ') }), 'warn'));
    chips.forEach((c) => meta.append(c));

    // Failure / approval notice
    const notice = $('run-notice');
    if (run.failureReason) {
        notice.className = 'notice notice--danger';
        notice.innerHTML = `<div><strong>${esc(t('notice.failure'))}</strong> · <code>${esc(run.failureCode || '')}</code><br>${esc(run.failureReason)}</div>`;
        notice.classList.remove('is-hidden');
    } else if (run.awaitingApproval) {
        notice.className = 'notice notice--warn';
        notice.innerHTML = `<div><strong>${esc(t('notice.approval', { nodes: run.pendingApprovals.join(', ') }))}</strong><br>${esc(t('notice.approval.hint'))}</div>`;
        notice.classList.remove('is-hidden');
    } else {
        notice.classList.add('is-hidden');
    }

    renderActions();
    renderStats();
    renderJournal();
    renderNodeDetail();
    renderGraphInto(nodeStateMap(), false);
}

function chip(text, kind = '') {
    const span = document.createElement('span');
    span.className = `chip${kind ? ` chip--${kind}` : ''}`;
    span.textContent = text;
    return span;
}

function nodeStateMap() {
    const map = {};
    (state.execution?.nodes || []).forEach((n) => { map[n.nodeId] = n; });
    return map;
}

function renderGraphInto(states, isPreview) {
    const svg = $('graph');
    if (!state.workflow) {
        svg.replaceChildren();
        $('graph-legend').replaceChildren();
        return;
    }
    renderGraph(svg, state.workflow, states, {
        lang: state.lang,
        selectedId: state.selectedNodeId,
        onSelect: (nodeId) => {
            state.selectedNodeId = nodeId;
            renderNodeDetail();
            renderGraphInto(isPreview ? {} : nodeStateMap(), isPreview);
        },
    });
    renderLegend($('graph-legend'), state.lang);
}

/* ── Stats ──────────────────────────────────────────────────────────────────────────────── */

function renderStats() {
    const run = state.execution;
    const usage = run?.usage || {};
    const budget = state.workflow?.budget || {};
    const box = $('stats');
    box.replaceChildren();

    const done = (run.nodes || []).filter((n) => n.status === 'COMPLETED').length;
    const skipped = (run.nodes || []).filter((n) => n.status === 'SKIPPED').length;

    const cards = [
        {
            label: t('stats.status'),
            value: t(`status.${run.status}`),
            sub: run.terminal ? t('stats.status.finished') : t('stats.status.running'),
            mod: statusModifier(run.status),
        },
        {
            label: t('stats.elapsed'),
            value: fmtDuration(run.elapsedMs),
            sub: run.finishedAt ? t('stats.elapsed.ended', { time: fmtTime(run.finishedAt) }) : t('stats.elapsed.live'),
        },
        {
            label: t('stats.tokens'),
            value: fmtNum(usage.totalTokens),
            sub: t('stats.tokens.split', { in: fmtNum(usage.inputTokens), out: fmtNum(usage.outputTokens) }),
        },
        {
            label: t('stats.cost'),
            value: fmtCost(usage.costUsd),
            sub: budget.maxCostUsd ? t('stats.cost.budget', { v: fmtCost(budget.maxCostUsd) }) : t('stats.cost.unlimited'),
            mod: 'accent',
        },
        {
            label: t('stats.calls'),
            value: fmtNum(usage.llmCalls),
            sub: usage.cacheHits ? t('stats.calls.cached', { n: usage.cacheHits }) : t('stats.cost.unlimited').replace(t('stats.cost.unlimited'), state.lang === 'zh' ? '全部实付' : 'all billed'),
        },
        {
            label: t('stats.nodes'),
            value: `${done}/${(run.nodes || []).length}`,
            sub: skipped ? t('stats.nodes.skipped', { n: skipped }) : '',
        },
    ];

    cards.forEach((c, i) => box.append(statCard(c, i)));

    if (budget.maxTotalTokens > 0) {
        const ratio = Math.min(1, (usage.totalTokens || 0) / budget.maxTotalTokens);
        const level = ratio >= 1 ? 'danger' : ratio >= (budget.warnThreshold || 0.8) ? 'warn' : '';
        const policy = (POLICY_LABEL[state.lang] || POLICY_LABEL.en)[budget.onExceed] || budget.onExceed;
        box.append(statCard({
            label: t('stats.budget'),
            value: `${Math.round(ratio * 100)}%`,
            sub: t('stats.budget.sub', {
                used: fmtNum(usage.totalTokens),
                max: fmtNum(budget.maxTotalTokens),
                policy,
            }),
            mod: level,
            meter: ratio,
        }, cards.length));
    }
}

function statCard({ label, value, sub, mod = '', meter = null }, index) {
    const el = document.createElement('div');
    el.className = `stat${mod ? ` stat--${mod}` : ''}`;
    el.style.animationDelay = `${Math.min(index * 25, 200)}ms`;

    const l = document.createElement('div');
    l.className = 'stat-label';
    l.textContent = label;

    const v = document.createElement('div');
    v.className = 'stat-value';
    v.textContent = value;

    el.append(l, v);

    if (sub) {
        const s = document.createElement('div');
        s.className = 'stat-sub';
        s.textContent = sub;
        el.append(s);
    }
    if (meter !== null) {
        const bar = document.createElement('div');
        bar.className = `meter${meter >= 1 ? ' is-danger' : meter >= 0.8 ? ' is-warn' : ''}`;
        const fill = document.createElement('i');
        fill.style.width = `${(meter * 100).toFixed(1)}%`;
        bar.append(fill);
        el.append(bar);
    }
    return el;
}

function statusModifier(status) {
    return ({
        SUCCEEDED: 'ok', FAILED: 'danger', BUDGET_EXCEEDED: 'warn',
        WAITING_APPROVAL: 'warn', RUNNING: 'accent',
    })[status] || '';
}

/* ── Actions ────────────────────────────────────────────────────────────────────────────── */

function renderActions() {
    const box = $('run-actions');
    box.replaceChildren();

    if (state.execution) {
        const run = state.execution;
        if (run.awaitingApproval) {
            box.append(btn(t('btn.approve'), 'warnbtn', () => decide('approve')));
            box.append(btn(t('btn.reject'), 'dangbtn', () => decide('reject')));
        }
        if (!run.terminal) box.append(btn(t('btn.cancel'), 'ghostbtn', () => guarded(() => api.cancel(run.id), t('toast.cancelRequested'))));
        if (!run.terminal && !run.awaitingApproval) box.append(btn(t('btn.resume'), 'ghostbtn', () => guarded(() => api.resume(run.id), t('action.refresh.done'))));
        box.append(btn(t('btn.replay'), 'ghostbtn', () => runWorkflowAction(run.workflowId, run.inputs)));
    }
    if (state.workflow) {
        box.append(btn(t('btn.runWorkflow', { id: state.workflow.id }), 'primarybtn', () => runWorkflow(state.workflow.id)));
    }
    if (state.busy) box.querySelectorAll('button').forEach((b) => { b.disabled = true; });
}

function btn(label, kind, handler) {
    const b = document.createElement('button');
    b.className = kind;
    b.textContent = label;
    b.addEventListener('click', handler);
    return b;
}

async function decide(kind) {
    const run = state.execution;
    if (!run) return;
    const msg = kind === 'approve' ? t('toast.approved') : t('toast.rejected');
    await guarded(
        () => (kind === 'approve'
            ? api.approve(run.id, 'operator', 'approved from control room')
            : api.reject(run.id, 'operator', 'rejected from control room')),
        msg,
    );
}

async function runWorkflow(workflowId) {
    if (!workflowId) { toast(t('toast.selectWorkflow'), 'error'); return; }
    await runWorkflowAction(workflowId, SAMPLE_INPUTS[workflowId] || {});
}

async function runWorkflowAction(workflowId, inputs) {
    await guarded(async () => {
        const run = await api.start(workflowId, inputs);
        toast(t('toast.started', { id: workflowId }), 'ok');
        return run;
    }, null, async (run) => {
        await refreshRuns();
        if (run?.id) await selectExecution(run.id);
    });
}

async function guarded(call, successMessage, after = null) {
    if (state.busy) return;
    state.busy = true;
    renderActions();
    try {
        const result = await call();
        if (successMessage) toast(successMessage, 'ok');
        if (after) await after(result);
        else if (state.selectedExecutionId) await refreshExecution();
    } catch (error) {
        toast(t('toast.actionFailed', { msg: describeError(error) }), 'error');
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
        source.addEventListener(type, () => {
            if (type !== 'connected') scheduleRefresh(executionId);
        });
    });
    source.onerror = () => {
        if (state.execution && !state.execution.terminal) scheduleRefresh(executionId);
    };
}

function closeStream() {
    if (state.stream) { state.stream.close(); state.stream = null; }
}

/** Coalesces bursts of events (a fan-out emits dozens) into one round-trip. */
function scheduleRefresh(executionId) {
    if (state.refreshTimer) return;
    state.refreshTimer = setTimeout(async () => {
        state.refreshTimer = null;
        if (state.selectedExecutionId !== executionId) return;
        await refreshExecution();
        if (state.execution?.terminal) { closeStream(); await refreshRuns(); }
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
        renderRuns();
    } catch (error) {
        console.warn('refresh failed', error);
    }
}

async function refreshRuns() {
    try {
        state.executions = await api.executions();
        renderRuns();
    } catch (error) {
        console.warn('runs refresh failed', error);
    }
}

/* ── Journal ────────────────────────────────────────────────────────────────────────────── */

function renderJournal() {
    const box = $('journal');
    box.replaceChildren();
    $('journal-hint').textContent = state.journal.length ? t('card.journal.count', { n: state.journal.length }) : '';

    if (!state.journal.length) {
        box.append(emptyNote(t('log.empty')));
        return;
    }

    const labels = EVENT_LABEL[state.lang] || EVENT_LABEL.en;

    state.journal.forEach((entry, i) => {
        const li = document.createElement('li');
        li.className = `logitem t-${toneOf(entry.type)}`;
        li.style.animationDelay = `${Math.min(i * 7, 160)}ms`;

        const seq = document.createElement('div');
        seq.className = 'logitem-seq';
        seq.textContent = `#${entry.seq}`;

        const body = document.createElement('div');
        const type = document.createElement('div');
        type.className = 'logitem-type';
        type.append(document.createTextNode(labels[entry.type] || entry.type));
        if (entry.nodeId) {
            const node = document.createElement('span');
            node.className = 'logitem-node';
            node.textContent = entry.nodeId;
            type.append(node);
        }
        body.append(type);

        const detail = summarize(entry);
        if (detail) {
            const d = document.createElement('div');
            d.className = 'logitem-detail';
            d.textContent = detail;
            body.append(d);
        }

        const time = document.createElement('div');
        time.className = 'logitem-time';
        time.textContent = fmtTime(entry.timestamp);
        body.append(time);

        li.append(seq, body);
        box.append(li);
    });
}

function toneOf(type) {
    if (['NODE_COMPLETED', 'EXECUTION_FINISHED', 'APPROVAL_GRANTED'].includes(type)) return 'ok';
    if (['NODE_FAILED', 'EXECUTION_STOPPED', 'APPROVAL_REJECTED'].includes(type)) return 'bad';
    if (['BUDGET_WARNING', 'BUDGET_EXCEEDED', 'NODE_RETRY_SCHEDULED', 'APPROVAL_REQUESTED', 'RECOVERY_WARNING'].includes(type)) return 'warn';
    if (type === 'CACHE_HIT') return 'info';
    if (type === 'NODE_SKIPPED') return 'idle';
    return 'accent';
}

function summarize(entry) {
    const p = entry.payload || {};
    switch (entry.type) {
        case 'EXECUTION_STARTED':
            return `workflow ${p.workflowId || '?'} · inputs ${JSON.stringify(p.inputs || {})}`;
        case 'EXECUTION_RESUMED':
            return state.lang === 'zh'
                ? `重放 ${p.replayedEntries ?? '?'} 条日志，恢复 ${(p.restoredNodes || []).length} 个节点`
                : `replayed ${p.replayedEntries ?? '?'} entries · restored ${(p.restoredNodes || []).length} nodes`;
        case 'EXECUTION_FINISHED':
            return `tokens ${p.usage?.totalTokens ?? '?'} · ${fmtCost(p.usage?.costUsd)}`;
        case 'EXECUTION_STOPPED':
            return `${p.code || ''} ${p.reason || ''}`.trim();
        case 'NODE_STARTED':
        case 'NODE_SCHEDULED':
            return p.attempt ? t('node.attempt', { n: p.attempt }) : (p.node || '');
        case 'NODE_COMPLETED':
            return `tokens ${p.usage?.totalTokens ?? 0}${p.cacheHit ? (state.lang === 'zh' ? ' · 缓存命中' : ' · cache hit') : ''}`;
        case 'NODE_FAILED':
            return `${p.error || ''}${p.retryable === false ? (state.lang === 'zh' ? ' （不可重试）' : ' (not retryable)') : ''}`;
        case 'NODE_RETRY_SCHEDULED':
            return state.lang === 'zh' ? `${p.delayMs ?? '?'}ms 后重试` : `retry in ${p.delayMs ?? '?'}ms`;
        case 'NODE_SKIPPED':
            return p.reason || '';
        case 'APPROVAL_REQUESTED':
            return String(p.summary || '').slice(0, 160);
        case 'APPROVAL_GRANTED':
            return `${p.approver || '?'}${p.comment ? ` — ${p.comment}` : ''}`;
        case 'APPROVAL_REJECTED':
            return p.reason || '';
        case 'BUDGET_WARNING':
        case 'BUDGET_EXCEEDED':
            return `${p.detail || ''}${p.action ? ` (${p.action})` : ''}`;
        case 'CACHE_HIT':
            return `model ${p.model || '?'}`;
        case 'RECOVERY_WARNING':
            return p.reason || '';
        default:
            return '';
    }
}

/* ── Node inspector ─────────────────────────────────────────────────────────────────────── */

function renderNodeDetail() {
    const box = $('node-detail');
    const run = state.execution;
    const nodeId = state.selectedNodeId;

    if (!run || !nodeId) {
        box.replaceChildren(emptyNote(t('card.node.empty')));
        return;
    }
    const node = (run.nodes || []).find((n) => n.nodeId === nodeId);
    if (!node) {
        box.replaceChildren(emptyNote(t('node.orphan')));
        return;
    }

    const u = node.usage || {};
    const dl = document.createElement('dl');
    dl.className = 'kv';

    const rows = [
        [t('node.name'), `<b>${esc(node.name)}</b> <code>${esc(node.nodeId)}</code>`],
        [t('node.kind'), esc(node.kind)],
        [t('node.status'), `${esc(t(`status.${node.status}`))}${node.attempt ? ` · ${esc(t('node.attempt', { n: node.attempt }))}` : ''}`],
        [t('node.duration'), esc(fmtDuration(node.durationMs))],
        [t('node.tokens'), `${fmtNum(u.totalTokens)} <span style="opacity:.6">(${esc(t('node.tokens.split', { in: fmtNum(u.inputTokens), out: fmtNum(u.outputTokens) }))})</span>`],
        [t('node.cost'), u.cacheHits ? esc(t('node.cached')) : esc(fmtCost(u.costUsd))],
        [t('node.timeout'), esc(node.timeout || t('misc.none'))],
        [t('node.idempotent'), node.idempotent ? esc(t('node.idempotent.yes')) : esc(t('node.idempotent.no'))],
        [t('node.optional'), esc(node.optional ? t('node.optional.yes') : t('node.optional.no'))],
    ];
    rows.forEach(([k, v]) => {
        const dt = document.createElement('dt');
        dt.textContent = k;
        const dd = document.createElement('dd');
        dd.innerHTML = v;
        dl.append(dt, dd);
    });
    box.replaceChildren(dl);

    if (node.errorMessage) {
        const label = document.createElement('p');
        label.className = 'section-label';
        label.textContent = t('node.error');
        const pre = document.createElement('pre');
        pre.className = 'code';
        pre.textContent = `${node.errorCode || ''}  ${node.errorMessage}`;
        pre.style.color = 'var(--danger)';
        box.append(label, pre);
    }

    const outLabel = document.createElement('p');
    outLabel.className = 'section-label';
    outLabel.style.marginTop = '14px';
    outLabel.textContent = t('node.output');
    const outPre = document.createElement('pre');
    outPre.className = 'code';
    outPre.textContent = JSON.stringify(node.output || {}, null, 2);
    box.append(outLabel, outPre);
}

/* ── Toasts ─────────────────────────────────────────────────────────────────────────────── */

function toast(message, kind = '') {
    const el = document.createElement('div');
    el.className = `toast${kind ? ` toast--${kind}` : ''}`;
    el.textContent = message;
    $('toasts').append(el);
    setTimeout(() => el.remove(), kind === 'error' ? 7000 : 3600);
}

function describeError(error) {
    if (error instanceof ApiError) return `${error.code}: ${error.message}`;
    return error?.message || String(error);
}

/* ── Background poll keeps the rail honest without a second stream ───────────────────────── */

setInterval(() => {
    if (!document.hidden && !state.stream) refreshRuns();
}, 6000);

boot();
