import { computed, ref, watch } from 'vue';
import { readSqlFileBlob } from '../../../services/sqlStudioApi';

export default {
  props: { file: { type: Object, required: true }, projectId: { type: String, required: true } },
  emits: ['reference'],
  setup(props, { emit }) {
    const workbook = ref(null), activeSheet = ref(''), loading = ref(false), error = ref(''), selectedColumn = ref(null), selectedRow = ref(null);
    const sheets = computed(() => workbook.value?.SheetNames || []);
    const rows = computed(() => workbook.value?.data?.[activeSheet.value] || []);
    watch(activeSheet, () => { selectedColumn.value = null; selectedRow.value = null; });
    const columnName = index => { let name = ''; for (let i = index + 1; i > 0; i = Math.floor((i - 1) / 26)) name = String.fromCharCode(65 + (i - 1) % 26) + name; return name; };
    watch(() => [props.projectId, props.file.id, props.file.version], async (_, __, onCleanup) => {
      const path = props.file.path.toLowerCase(); workbook.value = null; error.value = ''; selectedColumn.value = null; selectedRow.value = null;
      if (!/\.(csv|xlsx|xls)$/.test(path)) return;
      let cancelled = false; onCleanup(() => { cancelled = true; }); loading.value = true;
      try {
        const [blob, xlsx] = await Promise.all([readSqlFileBlob(props.projectId, props.file), import('xlsx')]);
        const book = xlsx.read(await blob.arrayBuffer(), { type: 'array', sheetRows: 501 });
        if (!cancelled) { workbook.value = { SheetNames: book.SheetNames, data: Object.fromEntries(book.SheetNames.map(name => [name, xlsx.utils.sheet_to_json(book.Sheets[name], { header: 1, defval: '' }).map(row => row.slice(0, 100))])) }; activeSheet.value = book.SheetNames[0] || ''; }
      } catch (ex) { if (!cancelled) error.value = ex.message || '无法预览 Mapping'; }
      finally { if (!cancelled) loading.value = false; }
    }, { immediate: true });
    function referenceField() {
      const column = selectedColumn.value;
      if (column === null) return;
      emit('reference', { file: props.file, sheet: activeSheet.value, row: selectedRow.value === null ? null : selectedRow.value + 1,
        field: String(rows.value[0]?.[column] || columnName(column)), value: selectedRow.value === null ? '' : String(rows.value[selectedRow.value]?.[column] || '').slice(0, 200) });
    }
    return { sheets, rows, activeSheet, loading, error, selectedColumn, selectedRow, columnName, referenceField };
  }
};
