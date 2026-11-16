// Validate a report created or changed since an explicit pre-smoke boundary.
// The JS report schema still needs real-run verification.
const fs = require('node:fs');
const path = require('node:path');

const expected = {
  CircuitBreakerBenchmark: { closedExecute: [{}], openReject: [{}] },
  PipelineBenchmark: { legacyPipeline: [{}], typedPipeline: [{}] },
  RateLimiterBenchmark: { globalLimiter: [{keyCount: '1'}, {keyCount: '64'}], keyedLimiter: [{keyCount: '1'}, {keyCount: '64'}] },
  RateLimiterContentionBenchmark: { globalContended: [{callers: '8'}], keyedSameKey: [{callers: '8'}], keyedDistinctKeys: [{callers: '8'}] },
  GlobalAndKeyedCompositionBenchmark: { typedGlobalAndKeyed: [{keyCount: '64'}], nestedGlobalAndKeyed: [{keyCount: '64'}] },
  RetryPolicyBenchmark: { happyPath: [{localMaxAttempts: '1'}, {localMaxAttempts: '3'}, {localMaxAttempts: '5'}] },
  TimeoutBenchmark: { withinDeadline: [{}] },
  AsyncDescriptorSmokeBenchmark: { delayed: [{}] },
};

function verifyEntries(entries) {
  if (!Array.isArray(entries)) throw new Error('Expected a JSON array of benchmark results');
  for (const [suite, methods] of Object.entries(expected)) {
    for (const [method, parameters] of Object.entries(methods)) {
      for (const params of parameters) {
        const found = entries.some(entry => typeof entry.benchmark === 'string' &&
          entry.benchmark.endsWith(`.${suite}.${method}`) && Number.isFinite(entry.primaryMetric?.score) &&
          Object.entries(params).every(([key, value]) => String(entry.params?.[key]) === value));
        if (!found) throw new Error(`Missing JS result: ${suite}.${method} ${JSON.stringify(params)}`);
      }
    }
  }
}

function isCurrent(before, file) {
  const previous = before.find(item => item.path === file.path);
  return file.size > 0 && (!previous || file.time !== previous.time || file.size !== previous.size || file.changed !== previous.changed);
}

function requireCurrentFile(before, after, filename) {
  const file = after.find(item => item.path === filename);
  if (!file || !isCurrent(before, file)) throw new Error(`File is not current: ${filename}`);
  return filename;
}

function selectCurrentReport(before, after) {
  const reports = after.filter(file => path.basename(file.path) === 'js.json' && isCurrent(before, file))
    .sort((a, b) => b.time - a.time);
  if (!reports.length) throw new Error('No current JS JSON report found after boundary');
  return reports[0].path;
}

function files(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, {withFileTypes: true}).flatMap(entry => {
    const name = path.join(dir, entry.name);
    if (entry.isDirectory()) return files(name);
    const stat = fs.statSync(name);
    return [{path: name, time: stat.mtimeMs, changed: stat.ctimeMs, size: stat.size}];
  });
}

function snapshot(build) {
  return [...files(path.join(build, 'reports/benchmarks/smoke')), ...files(path.join(build, 'js-smoke-invocation'))];
}

function verifyCurrent(build, boundary) {
  const before = JSON.parse(fs.readFileSync(boundary, 'utf8'));
  if (!Array.isArray(before)) throw new Error('Invalid report boundary');
  const after = snapshot(build);
  const report = selectCurrentReport(before, after);
  verifyEntries(JSON.parse(fs.readFileSync(report, 'utf8')));
  const metadata = requireCurrentFile(before, after, path.join(build, 'js-smoke-invocation/invocation.txt'));
  const config = requireCurrentFile(before, after, path.join(build, 'js-smoke-invocation/config.txt'));
  return {report, metadata, config};
}

if (require.main === module) {
  const [operation, boundaryArgument] = process.argv.slice(2);
  const build = path.resolve(__dirname, 'build');
  const boundary = path.resolve(boundaryArgument || '');
  if (!boundaryArgument || !boundary.startsWith(build + path.sep)) {
    throw new Error('Usage: verify-js-report.cjs begin|verify <boundary-file-under-benchmark/build>');
  }
  if (operation === 'begin') {
    fs.mkdirSync(path.dirname(boundary), {recursive: true});
    fs.writeFileSync(boundary, JSON.stringify(snapshot(build)), {flag: 'wx'});
    console.log(`Captured report and invocation boundary in ${boundary}`);
  } else if (operation === 'verify') {
    const {report} = verifyCurrent(build, boundary);
    console.log(`Verified current JS smoke report and invocation: ${report}`);
  } else {
    throw new Error('Expected begin or verify');
  }
}
module.exports = { verifyEntries, selectCurrentReport, requireCurrentFile, verifyCurrent };
