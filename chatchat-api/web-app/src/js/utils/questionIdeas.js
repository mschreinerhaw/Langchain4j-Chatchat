// Compose editable question starters from the user's own context. No analysis or data access.
export function questionIdeas({draft = '', context = {}, agent = {}} = {}) {
  const input = String(draft).trim();
  const subject = context.hasResult
    ? `基于当前“${context.title || '已有分析'}”，沿用已确认的对象、期间和口径。`
    : agent.name ? `请在“${agent.name}”已授权的数据范围内开展分析。` : '我想开展一次业务数据分析。';
  const base = input || subject;
  const financial = /金融|资产|交易|盈亏|投资|资金|持仓/.test(`${input} ${context.title || ''} ${agent.name || ''}`);
  return [
    {title: '看变化与趋势', prompt: `${base}\n\n请分析关键指标在指定期间的变化，比较不同时间段，找出变化较大的项目。请先确认分析对象、起止日期和比较基准；区分数据能验证的原因与待验证假设。`},
    {title: '看结构与占比', prompt: `${base}\n\n请按有业务意义的维度分析结构、占比与集中度，说明分母、统计范围、单位${financial ? '和币种' : ''}。请先列出需要补充的分析对象和期间，不推测缺失口径。`},
    {title: financial ? '查异常与勾稽' : '查异常与一致性', prompt: `${base}\n\n请检查缺失、重复和异常值，核对${financial ? '交易、资产、盈亏与资金数据' : '相关数据和指标'}之间的一致性。区分算术差异和口径未确认，列出证据、影响范围及需要进一步核实的问题。`},
    {title: '帮我完善问题', prompt: `${base}\n\n请先帮我明确分析对象、时间范围、关注指标、比较基准及希望得到的结论，再列出需要我补充的信息。信息不足时先澄清，不编造数据或直接下结论。`},
  ];
}
