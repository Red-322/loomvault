/**
 * Bilingual string table.
 *
 * The console ships in Chinese by default because its primary audience is the person running and
 * demonstrating the engine; English is one click away for the GitHub audience. Keeping both in
 * one file makes a missing translation obvious rather than silently falling back to a key name.
 *
 * Interpolation uses {name} placeholders: t('stat.nodes.count', { done: 3, total: 8 })
 */

export const STRINGS = {
    zh: {
        'brand.tagline': '持久化执行引擎',

        'action.refresh': '刷新',
        'action.refresh.done': '已刷新',
        'action.theme': '切换主题',

        'side.runs': '执行记录',
        'side.workflows': '工作流',
        'side.empty.runs': '还没有执行记录',
        'side.empty.workflows': '没有已注册的工作流',

        'welcome.title': '选择一条执行记录开始',
        'welcome.body': '每次执行都保留了完整的可重放日志。启动一条新执行，或从左侧挑一条历史记录——执行图、预算和事件时间轴会实时填充。',
        'welcome.cta': '运行演示工作流',

        'card.graph': '执行图',
        'card.graph.hint': '{n} 个节点 · 点击查看详情',
        'card.graph.unavailable': '工作流定义不可用',
        'card.journal': '事件日志',
        'card.journal.count': '{n} 条',
        'card.node': '节点详情',
        'card.node.hint': '点击图中任意节点查看',
        'card.node.empty': '点击执行图中的任意节点，查看它的输出、耗时和用量。',

        'legend.completed': '已完成',
        'legend.running': '进行中',
        'legend.waiting': '待审批',
        'legend.pending': '未开始',
        'legend.skipped': '已跳过',
        'legend.failed': '失败',

        'btn.run': '运行',
        'btn.runWorkflow': '运行 {id}',
        'btn.approve': '批准',
        'btn.reject': '驳回',
        'btn.cancel': '取消执行',
        'btn.resume': '继续',
        'btn.replay': '用相同输入重跑',

        'stats.status': '状态',
        'stats.status.running': '进行中',
        'stats.status.finished': '已结束',

        /* Execution and node statuses. Keys mirror the server-side enum names so that
           t(`status.${run.status}`) resolves without a lookup table in the view layer. */
        'status.PENDING': '等待中',
        'status.RUNNING': '运行中',
        'status.READY': '就绪',
        'status.RETRYING': '重试中',
        'status.WAITING_APPROVAL': '待审批',
        'status.COMPLETED': '已完成',
        'status.SUCCEEDED': '成功',
        'status.FAILED': '失败',
        'status.SKIPPED': '已跳过',
        'status.CANCELLED': '已取消',
        'status.BUDGET_EXCEEDED': '预算超限',
        'status.CLOSED': '正常',
        'status.OPEN': '熔断',
        'status.HALF_OPEN': '半开',
        'stats.elapsed': '耗时',
        'stats.elapsed.ended': '结束于 {time}',
        'stats.elapsed.live': '计时中',
        'stats.tokens': 'Token 用量',
        'stats.tokens.split': '输入 {in} · 输出 {out}',
        'stats.cost': '预估成本',
        'stats.cost.budget': '预算上限 {v}',
        'stats.cost.unlimited': '预算未设上限',
        'stats.calls': '模型调用',
        'stats.calls.cached': '其中 {n} 次命中缓存',
        'stats.nodes': '节点完成',
        'stats.nodes.skipped': '{n} 个被跳过',
        'stats.budget': '预算使用',
        'stats.budget.sub': '{used} / {max} · 超限策略 {policy}',

        'node.name': '节点',
        'node.kind': '类型',
        'node.status': '状态',
        'node.attempt': '第 {n} 次尝试',
        'node.duration': '耗时',
        'node.tokens': 'Token 用量',
        'node.tokens.split': '{in} 输入 / {out} 输出',
        'node.cost': '成本',
        'node.cached': '缓存命中，未计费',
        'node.timeout': '单次超时',
        'node.idempotent': '可安全重放',
        'node.idempotent.yes': '是（幂等）',
        'node.idempotent.no': '否 — 重放会记录告警',
        'node.optional': '失败不中断',
        'node.optional.yes': '是',
        'node.optional.no': '否',
        'node.output': '输出',
        'node.error': '错误',
        'node.orphan': '该节点不在当前工作流定义中',

        'notice.failure': '执行失败',
        'notice.approval': '等待人工审批：{nodes}',
        'notice.approval.hint': '执行已完整落盘并暂停，进程可以随时重启，等待审批不会占用任何资源。',

        'toast.started': '已启动 {id}',
        'toast.approved': '已批准，执行继续',
        'toast.rejected': '已驳回，执行终止',
        'toast.cancelRequested': '已请求取消',
        'toast.loadFailed': '加载失败 — {msg}',
        'toast.actionFailed': '{msg}',
        'toast.selectWorkflow': '请先选择一个工作流',
        'toast.seeded': '演示执行已启动',

        'log.empty': '暂无事件记录',

        'time.justNow': '刚刚',
        'time.secAgo': '{n} 秒前',
        'time.minAgo': '{n} 分钟前',
        'time.hourAgo': '{n} 小时前',
        'time.dayAgo': '{n} 天前',

        'misc.unlimited': '不限',
        'misc.none': '—',
    },

    en: {
        'brand.tagline': 'Durable execution engine',

        'action.refresh': 'Refresh',
        'action.refresh.done': 'Refreshed',
        'action.theme': 'Toggle theme',

        'side.runs': 'Runs',
        'side.workflows': 'Workflows',
        'side.empty.runs': 'No runs yet',
        'side.empty.workflows': 'No workflows registered',

        'welcome.title': 'Pick a run to inspect',
        'welcome.body': 'Every execution keeps a replayable journal. Start one, or pick a past run on the left — the graph, the budget and the event timeline fill in live.',
        'welcome.cta': 'Run the demo workflow',

        'card.graph': 'Execution graph',
        'card.graph.hint': '{n} nodes · click for detail',
        'card.graph.unavailable': 'Workflow definition unavailable',
        'card.journal': 'Journal',
        'card.journal.count': '{n} entries',
        'card.node': 'Node detail',
        'card.node.hint': 'Click any node in the graph',
        'card.node.empty': 'Click a node in the graph to inspect its output, duration and usage.',

        'legend.completed': 'completed',
        'legend.running': 'running',
        'legend.waiting': 'waiting',
        'legend.pending': 'pending',
        'legend.skipped': 'skipped',
        'legend.failed': 'failed',

        'btn.run': 'Run',
        'btn.runWorkflow': 'Run {id}',
        'btn.approve': 'Approve',
        'btn.reject': 'Reject',
        'btn.cancel': 'Cancel run',
        'btn.resume': 'Resume',
        'btn.replay': 'Re-run with same input',

        'stats.status': 'Status',
        'stats.status.running': 'in flight',
        'stats.status.finished': 'finished',

        'status.PENDING': 'pending',
        'status.RUNNING': 'running',
        'status.READY': 'ready',
        'status.RETRYING': 'retrying',
        'status.WAITING_APPROVAL': 'awaiting approval',
        'status.COMPLETED': 'completed',
        'status.SUCCEEDED': 'succeeded',
        'status.FAILED': 'failed',
        'status.SKIPPED': 'skipped',
        'status.CANCELLED': 'cancelled',
        'status.BUDGET_EXCEEDED': 'budget exceeded',
        'status.CLOSED': 'closed',
        'status.OPEN': 'open',
        'status.HALF_OPEN': 'half-open',
        'stats.elapsed': 'Elapsed',
        'stats.elapsed.ended': 'ended {time}',
        'stats.elapsed.live': 'counting',
        'stats.tokens': 'Tokens',
        'stats.tokens.split': '{in} in · {out} out',
        'stats.cost': 'Cost',
        'stats.cost.budget': 'cap {v}',
        'stats.cost.unlimited': 'no cost cap',
        'stats.calls': 'Model calls',
        'stats.calls.cached': '{n} served from cache',
        'stats.nodes': 'Nodes done',
        'stats.nodes.skipped': '{n} skipped',
        'stats.budget': 'Budget used',
        'stats.budget.sub': '{used} of {max} · on exceed: {policy}',

        'node.name': 'Node',
        'node.kind': 'Kind',
        'node.status': 'Status',
        'node.attempt': 'attempt {n}',
        'node.duration': 'Duration',
        'node.tokens': 'Tokens',
        'node.tokens.split': '{in} in / {out} out',
        'node.cost': 'Cost',
        'node.cached': 'served from cache, not billed',
        'node.timeout': 'Timeout per attempt',
        'node.idempotent': 'Safe to replay',
        'node.idempotent.yes': 'yes (idempotent)',
        'node.idempotent.no': 'no — replay raises a warning',
        'node.optional': 'Failure tolerated',
        'node.optional.yes': 'yes — does not abort the run',
        'node.optional.no': 'no',
        'node.output': 'Output',
        'node.error': 'Error',
        'node.orphan': 'This node is not part of the current workflow definition',

        'notice.failure': 'Execution failed',
        'notice.approval': 'Waiting for approval: {nodes}',
        'notice.approval.hint': 'The run is fully persisted and parked — the process may restart freely and the wait costs nothing.',

        'toast.started': 'Started {id}',
        'toast.approved': 'Approved — the run continues',
        'toast.rejected': 'Rejected — the run is terminated',
        'toast.cancelRequested': 'Cancellation requested',
        'toast.loadFailed': 'Load failed — {msg}',
        'toast.actionFailed': '{msg}',
        'toast.selectWorkflow': 'Select a workflow first',
        'toast.seeded': 'Demo run started',

        'log.empty': 'No events recorded',

        'time.justNow': 'just now',
        'time.secAgo': '{n}s ago',
        'time.minAgo': '{n}m ago',
        'time.hourAgo': '{n}h ago',
        'time.dayAgo': '{n}d ago',

        'misc.unlimited': 'unlimited',
        'misc.none': '—',
    },
};

/** Node kinds, shown as a tag on every graph node and in the inspector. */
export const KIND_LABEL = {
    zh: { LLM: '模型', TOOL: '工具', APPROVAL: '审批' },
    en: { LLM: 'MODEL', TOOL: 'TOOL', APPROVAL: 'APPROVAL' },
};

/** Journal event types — the long uppercase names are unreadable in a dense log. */
export const EVENT_LABEL = {
    zh: {
        EXECUTION_STARTED: '执行开始', EXECUTION_RESUMED: '执行恢复', EXECUTION_FINISHED: '执行完成',
        EXECUTION_STOPPED: '执行终止', NODE_SCHEDULED: '节点入队', NODE_STARTED: '节点开始',
        NODE_COMPLETED: '节点完成', NODE_FAILED: '节点失败', NODE_RETRY_SCHEDULED: '安排重试',
        NODE_SKIPPED: '节点跳过', APPROVAL_REQUESTED: '请求审批', APPROVAL_GRANTED: '审批通过',
        APPROVAL_REJECTED: '审批驳回', BUDGET_WARNING: '预算预警', BUDGET_EXCEEDED: '预算超限',
        CACHE_HIT: '缓存命中', RECOVERY_WARNING: '恢复告警',
    },
    en: {
        EXECUTION_STARTED: 'execution started', EXECUTION_RESUMED: 'execution resumed',
        EXECUTION_FINISHED: 'execution finished', EXECUTION_STOPPED: 'execution stopped',
        NODE_SCHEDULED: 'node queued', NODE_STARTED: 'node started',
        NODE_COMPLETED: 'node completed', NODE_FAILED: 'node failed',
        NODE_RETRY_SCHEDULED: 'retry scheduled', NODE_SKIPPED: 'node skipped',
        APPROVAL_REQUESTED: 'approval requested', APPROVAL_GRANTED: 'approval granted',
        APPROVAL_REJECTED: 'approval rejected', BUDGET_WARNING: 'budget warning',
        BUDGET_EXCEEDED: 'budget exceeded', CACHE_HIT: 'cache hit',
        RECOVERY_WARNING: 'recovery warning',
    },
};

/** Approval policies and budget strategies, surfaced in the stat cards. */
export const POLICY_LABEL = {
    zh: { FAIL: '终止', DEGRADE: '降级模型', TRUNCATE: '压缩上下文', WARN: '仅告警' },
    en: { FAIL: 'fail', DEGRADE: 'degrade', TRUNCATE: 'truncate', WARN: 'warn only' },
};
