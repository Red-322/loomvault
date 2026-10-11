/**
 * SVG renderer for the execution graph.
 *
 * Layout is longest-path layering: a node's column is one past its deepest predecessor. For the
 * graph sizes this engine targets that yields a readable left-to-right flow with no layout
 * library, and — more importantly — it is *stable*: the same workflow always produces the same
 * coordinates, so nodes do not jump around when the graph is redrawn on every incoming event.
 *
 * Colour is driven entirely by CSS custom properties (`var(--ok)` etc.) applied through the
 * `style` attribute, which means the light/dark switch needs no re-render at all.
 */

import { KIND_LABEL, STRINGS } from './i18n.js';

const NS = 'http://www.w3.org/2000/svg';

const NODE_W = 152;
const NODE_H = 60;
const COL_GAP = 56;
const ROW_GAP = 20;
const PAD = 22;
const BAR_W = 3;

/** Status → design tokens. `pulse` marks the states that mean "something is happening here". */
const STATE = {
    COMPLETED: { line: 'var(--ok)', pulse: false },
    RUNNING: { line: 'var(--info)', pulse: true },
    READY: { line: 'var(--accent-bright)', pulse: true },
    RETRYING: { line: 'var(--warn)', pulse: true },
    WAITING_APPROVAL: { line: 'var(--warn)', pulse: true },
    PENDING: { line: 'var(--stroke-strong)', pulse: false },
    FAILED: { line: 'var(--danger)', pulse: false },
    SKIPPED: { line: 'var(--stroke)', pulse: false },
};

function svgEl(name, attrs = {}, text) {
    const el = document.createElementNS(NS, name);
    for (const [k, v] of Object.entries(attrs)) {
        if (v !== null && v !== undefined) el.setAttribute(k, String(v));
    }
    if (text !== undefined && text !== null) el.textContent = text;
    return el;
}

/** Longest-path column assignment. A validated DAG has no cycles. */
function columns(nodes, edges) {
    const parents = new Map(nodes.map((n) => [n.id, []]));
    edges.forEach((e) => parents.get(e.to)?.push(e.from));

    const depth = new Map();
    const resolve = (id) => {
        if (depth.has(id)) return depth.get(id);
        depth.set(id, 0); // guard against a malformed definition rather than recursing forever
        const ps = parents.get(id) || [];
        const d = ps.length === 0 ? 0 : 1 + Math.max(...ps.map(resolve));
        depth.set(id, d);
        return d;
    };
    nodes.forEach((n) => resolve(n.id));
    return depth;
}

function truncate(text, max) {
    const s = String(text ?? '');
    return s.length <= max ? s : s.slice(0, max - 1) + '…';
}

function interpolate(template, vars) {
    if (!vars) return template;
    return template.replace(/\{(\w+)\}/g, (_, k) => (k in vars ? String(vars[k]) : `{${k}}`));
}

/** Compact duration for dense graph labels: 940ms, 3.1s, 1m12s. */
function fmtMs(ms) {
    if (ms === null || ms === undefined) return '';
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    const m = Math.floor(ms / 60_000);
    return `${m}m${Math.round((ms % 60_000) / 1000)}s`;
}

/**
 * Renders `workflow` into `svg`.
 *
 * @param svg        target <svg> element
 * @param workflow   WorkflowDefinition (nodes + edges)
 * @param nodeStates map nodeId → NodeView (empty when previewing a definition)
 * @param opts       { lang, selectedId, onSelect }
 */
export function renderGraph(svg, workflow, nodeStates = {}, opts = {}) {
    const lang = opts.lang === 'en' ? 'en' : 'zh';
    const t = (key, vars) => interpolate(STRINGS[lang][key] ?? key, vars);
    const selected = opts.selectedId || null;

    svg.replaceChildren();
    const nodes = workflow?.nodes || [];
    const edges = workflow?.edges || [];

    if (nodes.length === 0) {
        svg.setAttribute('viewBox', '0 0 320 90');
        svg.setAttribute('width', '320');
        svg.setAttribute('height', '90');
        svg.append(svgEl('text', { x: 20, y: 46, class: 'gnode-stat' }, t('card.graph.unavailable')));
        return;
    }

    const depth = columns(nodes, edges);
    const buckets = new Map();
    nodes.forEach((n) => {
        const d = depth.get(n.id) ?? 0;
        if (!buckets.has(d)) buckets.set(d, []);
        buckets.get(d).push(n);
    });

    const tallest = Math.max(...[...buckets.values()].map((l) => l.length));
    const height = tallest * (NODE_H + ROW_GAP) - ROW_GAP + PAD * 2;

    const pos = new Map();
    for (const [col, list] of [...buckets.entries()].sort((a, b) => a[0] - b[0])) {
        const stackH = list.length * NODE_H + (list.length - 1) * ROW_GAP;
        const top = (height - stackH) / 2;
        list.forEach((node, i) => {
            pos.set(node.id, { x: PAD + col * (NODE_W + COL_GAP), y: top + i * (NODE_H + ROW_GAP) });
        });
    }

    const maxCol = Math.max(...buckets.keys());
    const width = PAD * 2 + (maxCol + 1) * NODE_W + maxCol * COL_GAP;
    // The CSS scales this to the container width (never below a readable floor), so the viewBox
    // is what carries the aspect ratio.
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.removeAttribute('width');
    svg.removeAttribute('height');

    // ── Edges ─────────────────────────────────────────────────────────────────────────────────
    const edgeLayer = svgEl('g', { class: 'g-edge-layer' });
    edges.forEach((edge) => {
        const a = pos.get(edge.from);
        const b = pos.get(edge.to);
        if (!a || !b) return;

        const fromState = nodeStates[edge.from]?.status;
        const toState = nodeStates[edge.to]?.status;

        let cls = 'gedge';
        if (toState === 'SKIPPED' || toState === undefined) cls += ' gedge--dead';
        else if (fromState === 'COMPLETED' && toState !== 'PENDING') cls += ' gedge--live';

        // While a downstream node is actively working, the edge carries moving dashes: the
        // clearest possible signal that data is flowing along this path right now.
        if (toState && ['RUNNING', 'READY', 'RETRYING'].includes(toState)) cls += ' gedge--flow';

        const x1 = a.x + NODE_W;
        const y1 = a.y + NODE_H / 2;
        const x2 = b.x;
        const y2 = b.y + NODE_H / 2;
        const bend = Math.max(28, (x2 - x1) * 0.55);

        edgeLayer.append(svgEl('path', {
            class: cls,
            d: `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`,
        }));

        if (edge.condition) {
            const label = svgEl('text', {
                x: (x1 + x2) / 2,
                y: (y1 + y2) / 2 - 8,
                class: 'gedge-label',
                'text-anchor': 'middle',
            }, truncate(edge.condition, 28));
            label.append(svgEl('title', {}, edge.condition));
            edgeLayer.append(label);
        }
    });
    svg.append(edgeLayer);

    // ── Nodes ─────────────────────────────────────────────────────────────────────────────────
    const nodeLayer = svgEl('g', { class: 'g-node-layer' });
    nodes.forEach((node) => {
        const p = pos.get(node.id);
        const view = nodeStates[node.id];
        const status = view?.status || 'PENDING';
        const palette = STATE[status] || STATE.PENDING;

        const g = svgEl('g', {
            class: 'gnode' + (selected === node.id ? ' is-selected' : ''),
            transform: `translate(${p.x} ${p.y})`,
            'data-state': status,
        });

        const card = svgEl('rect', {
            class: 'gnode-card',
            x: 0, y: 0, width: NODE_W, height: NODE_H, rx: 10,
        });
        card.style.fill = 'var(--bg-elevated)';
        card.style.stroke = selected === node.id ? 'var(--accent-bright)' : palette.line;
        card.style.strokeWidth = ['RUNNING', 'WAITING_APPROVAL', 'RETRYING'].includes(status) ? '1.8' : '1.1';
        if (status === 'SKIPPED') card.style.strokeDasharray = '4 3';
        g.append(card);

        // Status bar down the left edge — readable at a glance even when the card is small.
        const bar = svgEl('rect', { x: 0.75, y: 9, width: BAR_W, height: NODE_H - 18, rx: 2 });
        bar.style.fill = palette.line;
        bar.style.opacity = status === 'PENDING' || status === 'SKIPPED' ? '.5' : '.95';
        g.append(bar);

        const kind = svgEl('text', { x: 13, y: 16, class: 'gnode-kind' },
            (KIND_LABEL[lang] || KIND_LABEL.en)[node.kind] || node.kind);
        kind.style.fill = palette.line;
        kind.style.opacity = '.85';
        g.append(kind);

        // Right-hand metric: actual duration when known, otherwise the declared timeout.
        const metric = (view?.durationMs !== undefined && view?.durationMs !== null)
            ? fmtMs(view.durationMs)
            : (node.timeout ? compactTimeout(node.timeout) : '');
        if (metric) {
            g.append(svgEl('text', {
                x: NODE_W - 12, y: 16, class: 'gnode-stat', 'text-anchor': 'end',
            }, metric));
        }

        g.append(svgEl('text', { x: 13, y: 36, class: 'gnode-title' },
            truncate(node.name || node.id, 19)));

        const bits = [];
        if (view?.attempt > 1) bits.push(lang === 'en' ? `try ${view.attempt}` : `第${view.attempt}次`);
        if (view?.usage?.totalTokens) bits.push(`${view.usage.totalTokens} tok`);
        if (view?.usage?.cacheHits) bits.push(lang === 'en' ? 'cached' : '缓存');
        if (node.optional) bits.push(lang === 'en' ? 'optional' : '可失败');
        if (bits.length) {
            g.append(svgEl('text', { x: 13, y: 51, class: 'gnode-stat' }, bits.join(' · ')));
        }

        if (palette.pulse) {
            const ring = svgEl('circle', { cx: NODE_W - 13, cy: NODE_H - 14, r: 3, class: 'gnode-ring' });
            ring.style.fill = palette.line;
            g.append(ring);
            const dot = svgEl('circle', { cx: NODE_W - 13, cy: NODE_H - 14, r: 3.2 });
            dot.style.fill = palette.line;
            g.append(dot);
        }

        const tip = [
            `${node.name || node.id} (${node.kind})`,
            `${t('node.status')}: ${status}`,
            node.timeout ? `${t('node.timeout')}: ${node.timeout}` : null,
            node.optional ? t('node.optional.yes') : null,
            node.idempotent ? null : t('node.idempotent.no'),
            view?.errorMessage ? `${t('node.error')}: ${view.errorMessage}` : null,
        ].filter(Boolean).join('\n');
        g.append(svgEl('title', {}, tip));

        if (opts.onSelect) g.addEventListener('click', () => opts.onSelect(node.id));
        nodeLayer.append(g);
    });
    svg.append(nodeLayer);
}

/** PT45S → 45s, PT5M → 5m, P7D → 7d */
function compactTimeout(iso) {
    const m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/.exec(String(iso));
    if (!m) return String(iso);
    const [, d, h, min, s] = m;
    if (d) return `${d}d`;
    if (h) return `${h}h`;
    if (min) return `${min}m`;
    if (s) return `${Number(s)}s`;
    return String(iso);
}

/** Renders the colour key shown above the graph. */
export function renderLegend(container, lang) {
    const L = STRINGS[lang === 'en' ? 'en' : 'zh'];
    const items = [
        ['var(--ok)', L['legend.completed']],
        ['var(--info)', L['legend.running']],
        ['var(--warn)', L['legend.waiting']],
        ['var(--stroke-strong)', L['legend.pending']],
        ['var(--danger)', L['legend.failed']],
    ];
    container.replaceChildren();
    items.forEach(([color, label]) => {
        const span = document.createElement('span');
        const i = document.createElement('i');
        i.style.background = color;
        span.append(i, document.createTextNode(label));
        container.append(span);
    });
}
