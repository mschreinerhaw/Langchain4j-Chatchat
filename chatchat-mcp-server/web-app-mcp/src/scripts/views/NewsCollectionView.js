import { newsApi } from '../../services/api';
import '../../styles/views/news-collection.css';

const emptyRule = () => ({ listSelector: '', linkSelector: '', titleSelector: '', contentSelector: '', authorSelector: '', publishTimeSelector: '', urlPattern: '' });
const emptyForm = () => ({ id: null, sourceCode: '', sourceName: '', sourceType: 'WEB_LIST', entryUrl: '', allowedDomain: '', scheduleCron: '0 */10 * * * *', enabled: false, configuration: { sleepMillis: 1000, timeoutMillis: 20000, zoneId: 'Asia/Shanghai', language: 'zh-CN', attachmentSelector: '', attachmentAllowedDomains: '' }, rule: emptyRule() });
const intervalOptions = [
  { label: '5 分钟', cron: '0 */5 * * * *' },
  { label: '10 分钟', cron: '0 */10 * * * *' },
  { label: '15 分钟', cron: '0 */15 * * * *' },
  { label: '30 分钟', cron: '0 */30 * * * *' },
  { label: '1 小时', cron: '0 0 * * * *' },
  { label: '2 小时', cron: '0 0 */2 * * *' },
  { label: '4 小时', cron: '0 0 */4 * * *' },
  { label: '6 小时', cron: '0 0 */6 * * *' }
];
const weekdayOptions = [
  { label: '一', value: 'MON' }, { label: '二', value: 'TUE' }, { label: '三', value: 'WED' },
  { label: '四', value: 'THU' }, { label: '五', value: 'FRI' }, { label: '六', value: 'SAT' },
  { label: '日', value: 'SUN' }
];
const weekdayNumberMap = { '1': 'MON', '2': 'TUE', '3': 'WED', '4': 'THU', '5': 'FRI', '6': 'SAT', '0': 'SUN', '7': 'SUN' };
const monthDayOptions = [...Array.from({ length: 28 }, (_, index) => ({ label: String(index + 1), value: String(index + 1) })), { label: '末', value: 'L' }];
const emptyScheduleEditor = () => ({ mode: 'interval', intervalCron: '0 */10 * * * *', time: '09:00', weekdays: ['MON'], monthDays: ['1'] });
const sourceTypeOptions = [
  { label: '交易所首页', value: 'EXCHANGE_HOME' },
  { label: '资讯首页', value: 'NEWS_HOME' },
  { label: '财联社电报', value: 'CLS_TELEGRAPH' },
  { label: '网页列表', value: 'WEB_LIST' },
  { label: '固定网页', value: 'WEB_SINGLE_PAGE' },
  { label: 'RSS/Atom', value: 'RSS' },
  { label: 'JSON API', value: 'API' }
];
const selectorPresets = {
  title: [
    { label: '文章标题（h1）', value: 'h1' },
    { label: '常用标题类（.title）', value: '.title' },
    { label: '文章标题类（.article-title）', value: '.article-title' },
    { label: '新闻标题类（.news-title）', value: '.news-title' },
    { label: '详情标题类（.detail-title）', value: '.detail-title' },
    { label: '结构化标题（[itemprop="headline"]）', value: '[itemprop="headline"]' }
  ],
  publishTime: [
    { label: 'HTML time 标签', value: 'time' },
    { label: '常用时间类（.time）', value: '.time' },
    { label: '发布时间类（.publish-time）', value: '.publish-time' },
    { label: '发布日期类（.publish-date）', value: '.publish-date' },
    { label: '文章时间类（.article-time）', value: '.article-time' },
    { label: '详情时间类（.detail-time）', value: '.detail-time' },
    { label: '结构化发布时间（[itemprop="datePublished"]）', value: '[itemprop="datePublished"]' }
  ],
  author: [
    { label: 'HTML address 标签', value: 'address' },
    { label: '常用作者类（.author）', value: '.author' },
    { label: '来源类（.source）', value: '.source' },
    { label: '文章来源类（.article-source）', value: '.article-source' },
    { label: '发布者类（.publisher）', value: '.publisher' },
    { label: '结构化作者（[itemprop="author"]）', value: '[itemprop="author"]' }
  ],
  content: [
    { label: 'HTML article 标签', value: 'article' },
    { label: '常用正文类（.content）', value: '.content' },
    { label: '文章正文类（.article-content）', value: '.article-content' },
    { label: '新闻正文类（.news-content）', value: '.news-content' },
    { label: '详情正文类（.detail-content）', value: '.detail-content' },
    { label: '正文容器（#content）', value: '#content' },
    { label: '文章容器（#article-content）', value: '#article-content' }
  ],
  attachment: [
    { label: '自动识别常见附件（推荐）', value: 'a[href$=".pdf"], a[href$=".doc"], a[href$=".docx"], a[href$=".xls"], a[href$=".xlsx"], a[href$=".csv"]' },
    { label: 'PDF 文件', value: 'a[href$=".pdf"]' },
    { label: 'Word 文件', value: 'a[href$=".doc"], a[href$=".docx"]' },
    { label: 'Excel/CSV 文件', value: 'a[href$=".xls"], a[href$=".xlsx"], a[href$=".csv"]' },
    { label: '附件区域链接（.attachment）', value: '.attachment a' },
    { label: '附件列表链接（.attachment-list）', value: '.attachment-list a' },
    { label: '下载区域链接（.download）', value: '.download a' }
  ]
};

function cronTime(hour, minute) {
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

function decodeCron(value) {
  const cron = String(value || '').trim();
  const interval = intervalOptions.find((option) => option.cron === cron);
  if (interval) return { ...emptyScheduleEditor(), mode: 'interval', intervalCron: interval.cron, description: `每隔 ${interval.label}` };
  const parts = cron.split(/\s+/);
  if (parts.length !== 6 || parts[0] !== '0' || !/^\d+$/.test(parts[1]) || !/^\d+$/.test(parts[2])) {
    return { ...emptyScheduleEditor(), mode: 'advanced', description: cron ? `自定义计划` : '未配置调度' };
  }
  const time = cronTime(parts[2], parts[1]);
  if (parts[3] === '*' && parts[4] === '*' && parts[5] === '*') {
    return { ...emptyScheduleEditor(), mode: 'daily', time, description: `每天 ${time}` };
  }
  if (parts[3] === '*' && parts[4] === '*' && parts[5] !== '*') {
    const weekdays = parts[5].split(',').map((day) => weekdayNumberMap[day] || day.toUpperCase()).filter((day) => weekdayOptions.some((option) => option.value === day));
    const labels = weekdays.map((day) => weekdayOptions.find((option) => option.value === day)?.label).filter(Boolean);
    return { ...emptyScheduleEditor(), mode: 'weekly', time, weekdays: weekdays.length ? weekdays : ['MON'], description: `每周${labels.join('、')} ${time}` };
  }
  if (parts[3] !== '*' && parts[4] === '*' && parts[5] === '*') {
    const monthDays = parts[3].split(',').filter((day) => monthDayOptions.some((option) => option.value === day));
    const labels = monthDays.map((day) => day === 'L' ? '最后一天' : `${day} 日`);
    return { ...emptyScheduleEditor(), mode: 'monthly', time, monthDays: monthDays.length ? monthDays : ['1'], description: `每月 ${labels.join('、')} ${time}` };
  }
  return { ...emptyScheduleEditor(), mode: 'advanced', description: '自定义计划' };
}

export default {
  name: 'NewsCollectionView',
  emits: ['notify', 'error', 'result'],
  data: () => ({
    sources: [], presets: [], patternPresets: [], loading: false, saving: false, collectingId: null, dialogOpen: false,
    filters: { keyword: '', sourceType: '', enabled: '' }, page: 1, pageSize: 10, pageSizes: [10, 20, 50, 100],
    form: emptyForm(), scheduleEditor: emptyScheduleEditor(), intervalOptions, weekdayOptions, monthDayOptions, sourceTypeOptions, selectorPresets
  }),
  computed: {
    filteredSources() {
      const keyword = String(this.filters.keyword || '').trim().toLowerCase();
      return this.sources.filter((source) => {
        const searchable = [source.sourceName, source.sourceCode, source.entryUrl, source.allowedDomain, source.sourceType]
          .filter(Boolean)
          .join(' ')
          .toLowerCase();
        const keywordMatches = !keyword || searchable.includes(keyword);
        const typeMatches = !this.filters.sourceType || source.sourceType === this.filters.sourceType;
        const statusMatches = this.filters.enabled === '' || source.enabled === this.filters.enabled;
        return keywordMatches && typeMatches && statusMatches;
      });
    },
    pagedSources() {
      const start = (this.page - 1) * this.pageSize;
      return this.filteredSources.slice(start, start + this.pageSize);
    }
  },
  mounted() { this.load(); },
  methods: {
    async load() {
      this.loading = true;
      try { [this.sources, this.presets, this.patternPresets] = await Promise.all([newsApi.listSources(), newsApi.listPresets(), newsApi.listPatternPresets()]); }
      catch (error) { this.$emit('error', error); }
      finally {
        const lastPage = Math.max(1, Math.ceil(this.filteredSources.length / this.pageSize));
        if (this.page > lastPage) this.page = lastPage;
        this.loading = false;
      }
    },
    resetPage() { this.page = 1; },
    resetFilters() {
      this.filters = { keyword: '', sourceType: '', enabled: '' };
      this.page = 1;
    },
    sourceTypeLabel(type) {
      return this.sourceTypeOptions.find((option) => option.value === type)?.label || type || '-';
    },
    createSource() {
      this.form = emptyForm();
      this.scheduleEditor = decodeCron(this.form.scheduleCron);
      this.dialogOpen = true;
    },
    async editSource(source) {
      try {
        const rule = await newsApi.getRule(source.id);
        this.form = { ...emptyForm(), ...source, configuration: { ...emptyForm().configuration, ...(source.configuration || {}) }, rule: { ...emptyRule(), ...(rule || {}) } };
        this.scheduleEditor = decodeCron(this.form.scheduleCron);
        this.dialogOpen = true;
      } catch (error) { this.$emit('error', error); }
    },
    async save() {
      this.saving = true;
      try {
        this.applyVisualSchedule();
        const payload = { ...this.form, rule: undefined };
        const saved = await newsApi.saveSource(payload);
        if (['WEB_LIST', 'WEB_SINGLE_PAGE'].includes(saved.sourceType)) await newsApi.saveRule(saved.id, this.form.rule);
        this.dialogOpen = false;
        this.$emit('notify', { title: '资讯源配置已保存' });
        await this.load();
      } catch (error) { this.$emit('error', error); }
      finally { this.saving = false; }
    },
    async toggle(source) {
      try { await newsApi.saveSource({ ...source, enabled: !source.enabled }); await this.load(); }
      catch (error) { this.$emit('error', error); }
    },
    async collect(source) {
      this.collectingId = source.id;
      try { const result = await newsApi.collect(source.id); this.$emit('result', { title: `${source.sourceName} 采集结果`, value: result }); await this.load(); }
      catch (error) { this.$emit('error', error); }
      finally { this.collectingId = null; }
    },
    async remove(source) {
      try { await newsApi.removeSource(source.id); await this.load(); }
      catch (error) { this.$emit('error', error); }
    },
    applyVisualSchedule() {
      const editor = this.scheduleEditor;
      if (editor.mode === 'advanced') return;
      if (editor.mode === 'interval') {
        this.form.scheduleCron = editor.intervalCron;
        return;
      }
      const [hour, minute] = String(editor.time || '09:00').split(':');
      if (editor.mode === 'daily') {
        this.form.scheduleCron = `0 ${Number(minute)} ${Number(hour)} * * *`;
      } else if (editor.mode === 'weekly') {
        if (!editor.weekdays.length) editor.weekdays = ['MON'];
        const days = weekdayOptions.filter((option) => editor.weekdays.includes(option.value)).map((option) => option.value);
        this.form.scheduleCron = `0 ${Number(minute)} ${Number(hour)} * * ${days.join(',')}`;
      } else if (editor.mode === 'monthly') {
        if (!editor.monthDays.length) editor.monthDays = ['1'];
        const days = monthDayOptions.filter((option) => editor.monthDays.includes(option.value)).map((option) => option.value);
        this.form.scheduleCron = `0 ${Number(minute)} ${Number(hour)} ${days.join(',')} * *`;
      }
    },
    toggleMonthDay(day) {
      const selected = this.scheduleEditor.monthDays;
      this.scheduleEditor.monthDays = selected.includes(day) ? selected.filter((item) => item !== day) : [...selected, day];
      this.applyVisualSchedule();
    },
    describeCron(cron) {
      return decodeCron(cron).description;
    }
  }
};
