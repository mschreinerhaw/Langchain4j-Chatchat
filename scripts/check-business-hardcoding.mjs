import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const code = /\.(?:java|kt|mjs|cjs|js|ts|tsx|vue|py|go|rs|cs|sql)$/i;
export function productionSource(path) {
  return code.test(path)
    && /(?:^|\/)(?:src|gateway|lib|views|components|monitor)\//.test(path)
    && !/(?:^|\/)(?:tests?|fixtures?|examples?|__tests__|__fixtures__|target|node_modules|dist)\//.test(path)
    && !/(?:\.(?:test|spec)\.|Test\.(?:java|kt)$)/i.test(path);
}

const accountField = /\b(?:account(?:Id|No|Number)?|customer(?:Id|No|Number)?)\b/i;
const identityField = /\b(?:assetId|templateId|workerId|sessionId|taskId|modelAlias|modelName|modelProfile)\b/i;
const identityLiteral = /^(?:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|(?:asset|template|task|worker|session)[_-][\w.-]{4,}|(?:qwen|gpt|deepseek|claude|llama|hy3)[\w.-]*)$/i;

export function reviewCategories(line, previous = '') {
  const categories = new Set();
  const literals = line.matchAll(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`/g);
  const values = [...literals].map(match => match[0].slice(1,-1));
  const context = /[=(:,?]\s*$/.test(previous) ? previous+' '+line : line;
  if (values.some(value => /(?:^|[^0-9])(?:10\.\d{1,3}|192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}(?:$|[^0-9])/.test(value)))
    categories.add('private-network-literal');
  if (accountField.test(context) && (values.some(value => /^\d{8,24}$/.test(value))
    || /(?:^|[=(:,]\s*)\d{8,24}[Ll]?(?![\w.])/.test(line))) categories.add('account-literal');
  if (identityField.test(context) && values.some(value => identityLiteral.test(value)))
    categories.add('bound-identity-literal');
  if (/\b(?:if|else if|switch|case|when|equals|includes|contains|matches)\b/.test(context)
    && values.some(value => /\b(?:innodb|docker|livedata|livebos|cdh.?yarn)\b/i.test(value)))
    categories.add('scenario-conditional');
  if (/\b(?:reportFileName|reportBasename|reportFilePath)\b/i.test(context)
    && values.some(value => /(?:^|[\\/])[^\\/:]+\.(?:html?|md|pdf|docx)$/i.test(value)))
    categories.add('fixed-report-name');
  if (/\b(?:testBudget|fixtureBudget|benchmarkBudget)\b/i.test(context) && /\d/.test(line))
    categories.add('test-budget-literal');
  if (values.some(value => /(?:预制报告|模拟成功回执|prebuilt report|synthetic success receipt)/i.test(value)))
    categories.add('prebuilt-result-text');
  return [...categories];
}

export function reviewDiff(diff) {
  const findings = [];
  let path;
  let lineNumber = 0;
  let previous = '';
  for (const line of diff.split(/\r?\n/)) {
    if (line.startsWith('+++ ')) path = line.startsWith('+++ b/') ? line.slice(6).trimEnd() : undefined;
    else if (line.startsWith('@@ ')) {
      lineNumber = Number(/ \+(\d+)/.exec(line)?.[1] ?? 0);
      previous = '';
    }
    else if (line.startsWith('+')) {
      if (path && productionSource(path)) {
        for (const category of reviewCategories(line.slice(1),previous)) findings.push({path,line:lineNumber,category});
      }
      previous = line.slice(1);
      lineNumber++;
    } else if (line.startsWith(' ')) {
      previous = line.slice(1);
      lineNumber++;
    }
  }
  return findings;
}

function main() {
  const args = process.argv.slice(2);
  const base = args[0] === '--base' ? args[1] : 'HEAD';
  if (!base || args.length > 2 || (args.length && args[0] !== '--base'))
    throw Error('Usage: node scripts/check-business-hardcoding.mjs [--base <git-ref>]');
  const cwd = fileURLToPath(new URL('..',import.meta.url));
  const git = (...command) => execFileSync('git', ['-c','core.quotepath=false',...command],
    {cwd,encoding:'utf8',maxBuffer:32*1024*1024});
  const revision = git('rev-parse','--verify','--end-of-options',base+'^{commit}').trim();
  const diff = git('diff','--no-ext-diff','--no-color','--diff-filter=AMR','--unified=1',revision,'--');
  const findings = reviewDiff(diff);
  const untracked = git('ls-files','--others','--exclude-standard','-z').split('\0').filter(productionSource);
  for (const path of untracked) {
    const rows = readFileSync(resolve(cwd,path),'utf8').split(/\r?\n/);
    rows.forEach((row,index) => {
      for (const category of reviewCategories(row,rows[index-1])) findings.push({path,line:index+1,category});
    });
  }
  for (const finding of findings) console.log(`${finding.path}:${finding.line} ${finding.category}`);
  console.log(`Business hardcoding review candidates: ${findings.length}`);
  if (findings.length) process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 2;
  }
}
