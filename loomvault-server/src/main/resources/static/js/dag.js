/**
 * Layered SVG renderer for workflow graphs.
 *
 * Layout is a straightforward longest-path layering: a node's column is one past its deepest
 * predecessor. For the graph sizes this engine targets (tens of nodes) that produces a readable
 * left-to-right flow with no dependency on a graph-layout library, and it is stable between
 * refreshes — which matters a lot when the same graph is redrawn on every incoming SSE event.
 */

const SVG_NS = 'http://www.w3.org/2000/svg';

const NODE_W = 158;
const NODE_H = 54;
const COL_GAP = 74;
const ROW_GAP = 22;
const PAD_X = 16;
const PAD_Y = 18;

/** Status → visual tokens. Kept in one table so the legend never drifts from the canvas. */
const STATUS = {
    COMPLETED: { fill: '#ecfdf5', stroke: '#059669', text: '#065f46', badge: 'done' },
    RUNNING: { fill: '#eff6ff', stroke: '#2563eb', text: '#1d4ed8', badge: 'running', pulse: true },
    READY: { fill: '#eff6ff', stroke: '#60a5fa', text: '#1d4ed8', badge: 'ready' },
    RETRYING: { fill: '#fffbeb', stroke: '#d97706', text: '#b45309', badge: 'retrying', pulse: true },
    WAITING_APPROVAL: { fill: '#fffbeb', stroke: '#f59e0b', text: '#b45309', badge: 'waiting', pulse: true },
    PENDING: { fill: '#f8fafc', stroke: '#cbd5e1', text: '#64748b', badge: 'pending' },
    FAILED: { fill: '#fef2f2', stroke: '#dc2626', text: '#b91c1c', badge: 'failed' },
    SKIPPED: { fill: '#f8fafc', stroke: '#cbd5e1', text: '#94a3b8', badge: 'skipped', dashed: true },
};

const KIND_LABEL = { LLM: 'model call', TOOL: 'tool', APPROVAL: 'approval' };

function el(name, attrs = {}, text = null) {
    const node = document.createElementNS(SVG_NS, name);
    for (const [key, value] of Object.entries(attrs)) {
        if (value !== null && value !== undefined) {
            node.setAttribute(key, String(value));
        }
    }
    if (text !== null) {
        node.textContent = text;
    }
    return node;
}

/** Assigns each node a column: 1 + max(column of predecessors). Cycles are impossible here. */
function assignColumns(nodes, edges) {
    const incoming = new Map(nodes.map((n) => [n.id, []]));
    edges.forEach((e) => incoming.get(e.to)?.push(e.from));

    const column = new Map();
    const visiting = new Set();

    function resolve(id) {
        if (column.has(id)) return column.get(id);
        if (visiting.has(id)) return 0; // defensive: should never happen on a validated DAG
        visiting.add(id);
        const parents = incoming.get(id) || [];
        const depth = parents.length === 0
            ? 0
            : 1 + Math.max(...parents.map(resolve));
        visiting.delete(id);
        column.set(id, depth);
        return depth;
    }

    nodes.forEach((n) => resolve(n.id));
    return column;
}

/**
 * Renders the graph into `svg`.
 *
 * @param svg       target <svg> element
 * @param workflow  WorkflowDefinition (nodes + edges)
 * @param states    map of nodeId -> NodeView (may be empty when only previewing a definition)
 * @param onSelect  callback(nodeId)
 */
export function renderGraph(svg, workflow, states = {}, onSelect = () => {}) {
    svg.replaceChildren();
    const nodes = workflow.nodes || [];
    const edges = workflow.edges || [];
    if (nodes.length === 0) {
        svg.setAttribute('viewBox', '0 0 300 80');
        svg.append(el('text', { x: 16, y: 40, class: 'node-stat' }, 'This workflow has no nodes.'));
        return;
    }

    const column = assignColumns(nodes, edges);
    const byColumn = new Map();
    nodes.forEach((n) => {
        const c = column.get(n.id) ?? 0;
        if (!byColumn.has(c)) byColumn.set(c, []);
        byColumn.get(c).push(n);
    });

    // Vertically centre each column so the flow reads as a balanced tree.
    const tallest = Math.max(...[...byColumn.values()].map((list) => list.length));
    const canvasHeight = tallest * (NODE_H + ROW_GAP) - ROW_GAP + PAD_Y * 2;

    const position = new Map();
    for (const [col, list] of [...byColumn.entries()].sort((a, b) => a[0] - b[0])) {
        const stackHeight = list.length * NODE_H + (list.length - 1) * ROW_GAP;
        const top = (canvasHeight - stackHeight) / 2;
        list.forEach((node, index) => {
            position.set(node.id, {
                x: PAD_X + col * (NODE_W + COL_GAP),
                y: top + index * (NODE_H + ROW_GAP),
            });
        });
    }

    const maxCol = Math.max(...byColumn.keys());
    const width = PAD_X * 2 + (maxCol + 1) * NODE_W + maxCol * COL_GAP;
    svg.setAttribute('viewBox', `0 0 ${width} ${canvasHeight}`);
    svg.setAttribute('style', `min-height:${Math.min(canvasHeight, 460)}px`);

    // ---- edges first so nodes paint on top -------------------------------------------------
    const edgeLayer = el('g', { class: 'edge-layer' });
    edges.forEach((edge) => {
        const from = position.get(edge.from);
        const to = position.get(edge.to);
        if (!from || !to) return;

        const sourceState = states[edge.from]?.status;
        const targetState = states[edge.to]?.status;
        let cls = 'edge-path';
        if (targetState === 'SKIPPED') {
            cls += ' skipped';
        } else if (sourceState === 'COMPLETED' && targetState && targetState !== 'PENDING') {
            cls += ' taken';
        }

        const x1 = from.x + NODE_W;
        const y1 = from.y + NODE_H / 2;
        const x2 = to.x;
        const y2 = to.y + NODE_H / 2;
        const bend = Math.max(24, (x2 - x1) * 0.55);
        const path = el('path', {
            class: cls,
            d: `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`,
        });
        edgeLayer.append(path);

        if (edge.condition) {
            const mx = (x1 + x2) / 2;
            const my = (y1 + y2) / 2 - 7;
            const label = el('text', { x: mx, y: my, class: 'edge-label', 'text-anchor': 'middle' },
                shorten(edge.condition, 26));
            label.append(el('title', {}, edge.condition));
            edgeLayer.append(label);
        }
    });
    svg.append(edgeLayer);

    // ---- nodes ----------------------------------------------------------------------------
    const nodeLayer = el('g', { class: 'node-layer' });
    nodes.forEach((node) => {
        const pos = position.get(node.id);
        const view = states[node.id];
        const status = view?.status || 'PENDING';
        const palette = STATUS[status] || STATUS.PENDING;

        const group = el('g', { class: 'node-box', transform: `translate(${pos.x} ${pos.y})` });
        group.append(el('rect', {
            width: NODE_W,
            height: NODE_H,
            rx: 9,
            fill: palette.fill,
            stroke: palette.stroke,
            'stroke-width': status === 'RUNNING' || status === 'WAITING_APPROVAL' ? 2 : 1.4,
            'stroke-dasharray': palette.dashed ? '5 4' : null,
        }));

        // Kind tag, top-left
        group.append(el('text', { x: 11, y: 15, class: 'node-kind' }, KIND_LABEL[node.kind] || node.kind));

        // Status badge, top-right — shortened so it never collides with the title
        group.append(el('text', {
            x: NODE_W - 11,
            y: 15,
            class: 'node-stat',
            'text-anchor': 'end',
            fill: palette.text,
        }, palette.badge));

        // Title
        group.append(el('text', { x: 11, y: 33, class: 'node-label' }, shorten(node.name || node.id, 20)));

        // Footer: duration + attempt when known
        const bits = [];
        if (view?.durationMs !== null && view?.durationMs !== undefined) {
            bits.push(`${view.durationMs}ms`);
        }
        if (view?.attempt > 1) {
            bits.push(`try ${view.attempt}`);
        }
        if (view?.usage?.totalTokens) {
            bits.push(`${view.usage.totalTokens} tok`);
        }
        if (bits.length) {
            group.append(el('text', { x: 11, y: 47, class: 'node-stat' }, bits.join(' · ')));
        }

        if (palette.pulse) {
            group.append(el('circle', {
                cx: NODE_W - 13,
                cy: NODE_H - 13,
                r: 3.2,
                fill: palette.stroke,
                class: 'node-pulse',
            }));
        }

        const title = el('title', {}, `${node.name || node.id} (${node.kind})\nstatus: ${status}`
            + (view?.errorMessage ? `\nerror: ${view.errorMessage}` : '')
            + (node.timeout ? `\ntimeout: ${node.timeout}` : '')
            + (node.optional ? '\noptional: failures do not abort the run' : '')
            + (!node.idempotent ? '\nnon-idempotent: replay is warned' : ''));
        group.append(title);

        group.addEventListener('click', () => onSelect(node.id));
        nodeLayer.append(group);
    });
    svg.append(nodeLayer);
}

function shorten(text, max) {
    const value = String(text ?? '');
    return value.length <= max ? value : `${value.slice(0, max - 1)}…`;
}
