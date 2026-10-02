export const viewCatalog = [
  { key: 'fatigue', path: '/views/fatigue', label: '疲劳 / 负荷', unit: '用户本地日期', endpoints: ['fatigue/distribution', 'fatigue/completion', 'fatigue/high-load-users'] },
  { key: 'ops', path: '/views/ops', label: '运营健康', unit: 'UTC', endpoints: ['ops/growth', 'ops/retention', 'ops/status-distribution'] },
  { key: 'collab', path: '/views/collab', label: '协作任务', unit: 'UTC', endpoints: ['collab/funnel', 'collab/completion-trend', 'collab/team-distribution'] },
  { key: 'ai', path: '/views/ai', label: 'AI 成本', unit: 'UTC / CNY', endpoints: ['ai/cost-trend', 'ai/feature-distribution', 'ai/key-health', 'ai/quota-alerts'] },
  { key: 'system', path: '/views/system', label: '系统运维', unit: '实时' },
  { key: 'security', path: '/views/security', label: '安全风控', unit: 'UTC', endpoints: ['security/login-anomalies', 'security/register-analysis'] },
]

export const dataLabels = {
  active: '已验证且启用', disabled: '已禁用', unverified: '邮箱未验证',
  created: '已创建', accepted: '已接受', completed: '已完成',
  schedule_parse: '日程解析', team_task_breakdown: '任务拆解',
  daily_plan: '今日建议', task_description_optimize: '描述优化',
  frequent_failures: '10 分钟内高频失败', invalid_credentials: '登录失败',
  network_changed: '24 小时内登录网段变化', high: '高风险', medium: '需关注', low: '低风险',
  global: '全局', user: '用户', team: '团队',
  pending: '待处理', confirmed: '已确认', false_positive: '误报', resolved: '已解决',
  near_limit: '接近上限', exceeded: '已超限', rejected: '配额拒绝', success: '成功', failed: '失败',
  quota: '配额限制', provider: '供应商故障', configuration: '配置 / 权限', skipped: '已跳过',
}

export const columnLabels = {
  id: '记录 ID', userId: '用户 ID', teamId: '团队 ID', taskId: '任务 ID', usageLogId: '调用 ID',
  nickname: '昵称', name: '名称', title: '标题', date: '日期', localDate: '用户本地日期', score: '疲劳分数',
  value: '数量', total: '总数', expected: '应填数', completed: '完成数', skipped: '跳过数', rate: '完成率 (%)',
  newUsers: '新增用户', totalUsers: '累计用户', dau: '日活', wau: '周活', cohort: '注册样本',
  nextDayRate: '次日留存 (%)', day7Rate: '7 日留存 (%)', onTime: '按时完成', overdue: '逾期完成',
  cancelled: '取消数', onTimeRate: '按时完成率 (%)', calls: '调用数', failures: '失败数', tokens: 'Token',
  cost: '估算成本 (CNY)', pricedCalls: '已计价次数', meteredCalls: '已计量次数', keyId: 'Key ID',
  switches: '切换次数', switchCount: '切换次数', failureRate: '失败率 (%)', featureType: '功能',
  status: '状态', userStatus: '账号状态', riskLevel: '风险', reason: '原因', reviewStatus: '复核状态',
  reviewNote: '复核备注', reviewedBy: '复核人 ID', reviewedAt: '复核时间 (UTC)', revision: '版本',
  ipAddress: '来源 IP', ipSegment: 'IP 网段', registrations: '注册数', createdAt: '创建时间 (UTC)',
  updatedAt: '更新时间 (UTC)', snapshotAt: '快照时间 (UTC)', joinedAt: '加入时间 (UTC)',
  plannedLoad: '计划负荷', completedLoad: '完成负荷', predictedScore: '预测疲劳', capacity75: '承受值',
  loadPercent: '负荷占比 (%)', timezoneSnapshot: '快照时区', surveyScore: '调查分数', role: '角色',
  modelName: '模型', inputTokens: '输入 Token', outputTokens: '输出 Token', estimatedCost: '估算成本 (CNY)',
  failureKind: '失败分类', latencyMs: '耗时 (ms)', adminId: '管理员 ID', username: '管理员',
  action: '操作', targetType: '目标类型', targetId: '目标 ID', afterData: '操作后快照',
  eventType: '事件类型', scopeType: '额度范围', subjectId: '主体 ID', dailyLimit: '每日上限',
  usedCalls: '已用次数', alertKind: '告警类型', usageDate: '额度日期 (UTC)', members: '有效成员数',
  deadlineTime: '截止时间 (UTC)', settledAt: '结案时间 (UTC)', emailVerifiedAt: '邮箱验证时间 (UTC)',
  lastActiveDate: '最近活跃日期 (UTC)', success: '是否成功', switched: '是否切换',
  email: '邮箱', phone: '手机号', timezone: '时区', profileVersion: '资料版本', avatarUrl: '头像',
  exportedAt: '导出时间 (UTC)', dateFrom: '范围开始', dateTo: '范围结束', dataset: '数据集',
  selector: '明细筛选', series: '指标', exportScope: '导出范围',
}

export const datasetLabels = {
  'fatigue/distribution': '疲劳分布', 'fatigue/completion': '调查完成', 'fatigue/high-load-users': '高负荷用户',
  'ops/growth': '用户增长', 'ops/retention': '活跃与留存', 'ops/status-distribution': '用户状态',
  'collab/funnel': '任务漏斗', 'collab/completion-trend': '任务结案', 'collab/team-distribution': '团队规模',
  'ai/cost-trend': 'AI 成本', 'ai/feature-distribution': 'AI 功能', 'ai/key-health': 'Key 健康',
  'ai/quota-alerts': '配额告警', 'security/login-anomalies': '异常登录', 'security/register-analysis': '注册来源',
}

export function utcDate(date = new Date()) { return date.toISOString().slice(0, 10) }

export function presetRange(days, now = new Date()) {
  const end = new Date(now)
  const start = new Date(now)
  start.setUTCDate(start.getUTCDate() - (days - 1))
  return [utcDate(start), utcDate(end)]
}

export function validRange(range, now = new Date()) {
  if (!Array.isArray(range) || range.length !== 2) return false
  const [start, end] = range
  if (![start, end].every(value => /^\d{4}-\d{2}-\d{2}$/.test(value))) return false
  const first = new Date(`${start}T00:00:00Z`)
  const last = new Date(`${end}T00:00:00Z`)
  if (!Number.isFinite(first.getTime()) || !Number.isFinite(last.getTime())) return false
  return utcDate(first) === start && utcDate(last) === end && start <= end
    && end <= utcDate(now) && (last - first) / 86400000 <= 365
}

export function viewEndpoint(metric) {
  return metric.startsWith('security/') || metric === 'ai/quota-alerts' ? `/admin/${metric}` : `/admin/views/${metric}`
}

export function formatMetric(value, digits = 0) {
  return value === null || value === undefined ? '--' : Number(value).toLocaleString('zh-CN', { maximumFractionDigits: digits })
}

export function csvText(rows, columns, labels = {}) {
  const cell = value => {
    let text = value === null || value === undefined ? '' : String(value)
    if (/^[\s]*[=+\-@]/.test(text) || /^[\t\r]/.test(text)) text = `'${text}`
    return `"${text.replaceAll('"', '""')}"`
  }
  const fields = columns || [...new Set(rows.flatMap(row => Object.keys(row)))]
  return '\uFEFF' + [fields.map(key => labels[key] || key), ...rows.map(row => fields.map(key => row[key]))]
    .map(row => row.map(cell).join(',')).join('\r\n')
}

export function downloadCsv(name, rows, metadata = {}) {
  const content = (rows.length ? rows : [{}]).map(row => ({ ...metadata, ...row }))
  const url = URL.createObjectURL(new Blob([csvText(content, undefined, columnLabels)], { type: 'text/csv;charset=utf-8;' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `${name}.csv`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function queryPage(value) {
  const page = Number(value)
  return Number.isInteger(page) && page >= 1 && page <= 1000000 ? page : 1
}
