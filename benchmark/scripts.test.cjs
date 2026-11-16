const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { verifyEntries, selectCurrentReport, requireCurrentFile } = require('./verify-js-report.cjs');
const { runExpectedFailure, runNodeRunner, parseInvocation, failureConfig } = require('./verify-js-failure.cjs');

// Synthetic public JMH-style JSON, not a captured kotlinx-benchmark report.
const synthetic = [
  ['CircuitBreakerBenchmark', 'closedExecute', {}], ['CircuitBreakerBenchmark', 'openReject', {}],
  ['PipelineBenchmark', 'legacyPipeline', {}], ['PipelineBenchmark', 'typedPipeline', {}],
  ...['globalLimiter', 'keyedLimiter'].flatMap(method => ['1', '64'].map(keyCount => ['RateLimiterBenchmark', method, {keyCount}])),
  ...['globalContended', 'keyedSameKey', 'keyedDistinctKeys'].map(method => ['RateLimiterContentionBenchmark', method, {callers: '8'}]),
  ...['typedGlobalAndKeyed', 'nestedGlobalAndKeyed'].map(method => ['GlobalAndKeyedCompositionBenchmark', method, {keyCount: '64'}]),
  ...['1', '3', '5'].map(localMaxAttempts => ['RetryPolicyBenchmark', 'happyPath', {localMaxAttempts}]),
  ['TimeoutBenchmark', 'withinDeadline', {}], ['AsyncDescriptorSmokeBenchmark', 'delayed', {}],
].map(([suite, method, params]) => ({benchmark: `com.example.${suite}.${method}`, params, primaryMetric: {score: 1}}));

function smokeWarmups() {
  const source = fs.readFileSync(path.join(__dirname, 'build.gradle.kts'), 'utf8');
  const match = /register\("smoke"\)\s*\{[\s\S]*?warmups\s*=\s*(\d+)/.exec(source);
  assert.ok(match, 'smoke warmups configuration missing');
  return Number(match[1]);
}

test('smoke uses a positive JS deferred initial count', () => {
  assert.ok(smokeWarmups() > 0, 'kotlinx-benchmark maps warmups to benchmark.js initCount');
});

const benchmarkJs = path.resolve(__dirname, '../build/js/node_modules/benchmark/benchmark.js');
test('configured deferred initial count completes a real benchmark.js sample', {skip: !fs.existsSync(benchmarkJs) && !process.env.BENCHMARK_REQUIRE_INSTALLED}, async () => {
  const code = `const Benchmark = require(${JSON.stringify(benchmarkJs)});
    const bench = new Benchmark('promise', d => Promise.resolve(1).then(() => d.resolve()),
      {defer: true, async: true, initCount: ${smokeWarmups()}, minSamples: 1, minTime: 0.02, maxTime: 0.02});
    bench.on('complete', function () { if (this.error || !this.stats.sample.length) process.exit(2); console.log('completed'); });
    bench.run();`;
  const {stdout} = await promisify(execFile)(process.execPath, ['-e', code], {timeout: 3000});
  assert.match(stdout, /completed/);
});

test('report completeness accepts all suites and parameters and rejects each missing row', () => {
  assert.doesNotThrow(() => verifyEntries(synthetic));
  synthetic.forEach((_, index) => assert.throws(() => verifyEntries(synthetic.filter((_, i) => i !== index)), /Missing JS result/));
  assert.throws(() => verifyEntries(synthetic.map((row, index) => index ? row : {...row, primaryMetric: {score: 'NaN'}})), /Missing JS result/);
});

test('report selection requires a report created or changed after the boundary', () => {
  const before = [{path: '/smoke/a/js.json', time: 1, size: 42}];
  assert.throws(() => selectCurrentReport(before, before), /No current JS JSON report/);
  assert.throws(() => selectCurrentReport([], []), /No current JS JSON report/);
  assert.throws(() => selectCurrentReport([], [{path: '/smoke/a/js.json', time: 2, size: 0}]), /No current JS JSON report/);
  assert.equal(selectCurrentReport(before, [...before, {path: '/smoke/b/js.json', time: 2, size: 10}]), '/smoke/b/js.json');
  assert.equal(selectCurrentReport(before, [{...before[0], time: 2}]), '/smoke/a/js.json');
  assert.throws(() => verifyEntries([]), /Missing JS result/);
  assert.throws(() => verifyEntries(synthetic.slice(1)), /Missing JS result/);
  assert.doesNotThrow(() => verifyEntries(synthetic));
});

test('both captured metadata files must be current', () => {
  const old = [{path: '/meta/invocation.txt', time: 1, size: 20}, {path: '/meta/config.txt', time: 1, size: 20}];
  const current = old.map(item => ({...item, time: 2}));
  assert.throws(() => requireCurrentFile(old, old, old[0].path), /not current/);
  assert.throws(() => requireCurrentFile(old, [current[0]], old[1].path), /not current/);
  assert.equal(requireCurrentFile(old, current, old[0].path), old[0].path);
  assert.equal(requireCurrentFile(old, current, old[1].path), old[1].path);
});

test('metadata is strict UTF-8 base64 and failure config replaces only reportFile', () => {
  const data = {node: '/path with space/node', cwd: '/work', runner: '/work/kotlin/main.js', preload: '/work/register.js', config: '/work/config.txt'};
  const encoded = Object.entries(data).map(([key, value]) => `${key}=${Buffer.from(value).toString('base64')}`).join('\n');
  assert.deepEqual(parseInvocation(encoded), data);
  assert.throws(() => parseInvocation(encoded + '\nnode=YWJj'), /metadata/);
  assert.throws(() => parseInvocation(encoded + '\nextra=YWJj'), /metadata/);
  assert.throws(() => parseInvocation(encoded.replace('node=', 'node=@@')), /metadata/);
  assert.equal(failureConfig('name:js\nreportFile:/old.json\nmode:avgt\n', '/new.json'), 'name:js\nreportFile:/new.json\nmode:avgt\n');
  assert.throws(() => failureConfig('name:js\n', '/new.json'), /reportFile/);
  assert.throws(() => failureConfig('reportFile:/a\nreportFile:/b\n', '/new.json'), /reportFile/);
  assert.throws(() => failureConfig('reportFile:/a\n', 'relative'), /absolute/);
});

test('direct Node runner passes its arguments and working directory', async () => {
  const script = "if (process.argv[1] !== 'expected' || process.cwd() !== process.argv[2] || process.env.RESILIENT_BENCHMARK_FAIL_SMOKE !== '1') process.exit(2); console.error('Unexpected benchmark promise rejection: intentional smoke rejection'); process.exit(7)";
  const result = await runNodeRunner('-e', [script, 'expected', process.cwd()], process.cwd(), 3000);
  assert.equal(result.ok, true);
});

test('timeout kills the owned runner and its child and grandchild', async () => {
  const grandchild = "console.log('grandchild=' + process.pid); setInterval(() => {}, 1000)";
  const child = `console.log('child=' + process.pid); require('node:child_process').spawn(process.execPath, ['-e', ${JSON.stringify(grandchild)}], {stdio: 'inherit'}); setInterval(() => {}, 1000)`;
  const runner = `console.log('runner=' + process.pid); require('node:child_process').spawn(process.execPath, ['-e', ${JSON.stringify(child)}], {stdio: 'inherit'}); setInterval(() => {}, 1000)`;
  const result = await runNodeRunner('-e', [runner], process.cwd(), 1500);
  assert.equal(result.ok, false);
  assert.equal(result.timedOut, true);
  const pids = [...result.tail.matchAll(/(?:runner|child|grandchild)=(\d+)/g)].map(match => Number(match[1]));
  assert.equal(pids.length, 3, result.tail);
  for (const pid of pids) {
    let alive = true;
    for (let i = 0; i < 100 && alive; i++) {
      try { process.kill(pid, 0); await new Promise(resolve => setTimeout(resolve, 20)); }
      catch (error) { if (error.code === 'ESRCH') alive = false; else throw error; }
    }
    assert.equal(alive, false, `process ${pid} survived timeout`);
  }
});

test('failure harness accepts only marked nonzero exit', async () => {
  const marker = "Unexpected benchmark promise rejection: intentional smoke rejection";
  assert.equal((await runExpectedFailure(process.execPath, ['-e', `console.error(${JSON.stringify(marker)}); process.exit(7)`], 3000)).ok, true);
  assert.equal((await runExpectedFailure(process.execPath, ['-e', "console.error('compile error'); process.exit(1)"], 3000)).ok, false);
  assert.equal((await runExpectedFailure(process.execPath, ['-e', `console.error(${JSON.stringify(marker)})`], 3000)).ok, false);
  const timed = await runExpectedFailure(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], 200);
  assert.equal(timed.ok, false);
  assert.equal(timed.timedOut, true);
});
