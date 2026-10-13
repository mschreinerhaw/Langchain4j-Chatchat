// Load a grammar only when its dialect is opened. The parser runs in the browser and
// never reads a database or sends editor contents to an external service.
let modulePromise;
const constructors = { spark: 'SparkSQL', hive: 'HiveSQL', trino: 'TrinoSQL', mysql: 'MySQL', postgresql: 'PostgreSQL' };
export async function sqlDiagnostics(source, dialect) {
  const name = constructors[dialect];
  if (!name || !source.trim() || source.length > 200_000) return [];
  try {
    modulePromise ||= import('dt-sql-parser');
    const grammar = await modulePromise;
    const parser = new grammar[name]();
    return parser.validate(source).map(problem => ({
      startLineNumber: Math.max(1, problem.startLine),
      startColumn: Math.max(1, problem.startCol + 1),
      endLineNumber: Math.max(1, problem.endLine),
      endColumn: Math.max(2, problem.endCol + 1),
      message: problem.message || 'SQL syntax error',
      severity: 8
    }));
  } catch (error) {
    // Grammar failure must not interrupt editing or falsely mark valid SQL.
    console.warn('SQL diagnostics unavailable', error);
    return [];
  }
}
