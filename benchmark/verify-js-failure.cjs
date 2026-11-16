// Runs the actual generated JS benchmark with an intentional rejection.
// An unrelated Gradle or compilation failure must not count as a successful check.
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { TextDecoder } = require('node:util');
const { verifyCurrent } = require('./verify-js-report.cjs');

function runExpectedFailure(command, args, timeoutMs, options = {}) {
  return new Promise(resolve => {
    const child = spawn(command, args, {...options, detached: true, stdio: ['ignore', 'pipe', 'pipe']});
    let tail = '';
    let sawBoundary = false;
    let sawIntentional = false;
    let timedOut = false;
    let spawnError;
    function observe(chunk) {
      tail = (tail + chunk.toString()).slice(-8192);
      sawBoundary ||= tail.includes('Unexpected benchmark promise rejection:');
      sawIntentional ||= tail.includes('intentional smoke rejection');
    }
    child.stdout.on('data', observe);
    child.stderr.on('data', observe);
    child.on('error', error => { spawnError = error.message; });
    const timer = setTimeout(() => {
      timedOut = true;
      try { process.kill(-child.pid, 'SIGKILL'); } catch (error) {
        if (error.code !== 'ESRCH') spawnError = error.message;
      }
    }, timeoutMs);
    child.on('close', (code, signal) => {
      clearTimeout(timer);
      resolve({ok: !timedOut && !spawnError && code !== null && code !== 0 && sawBoundary && sawIntentional,
        code, signal, timedOut, spawnError, sawBoundary, sawIntentional, tail});
    });
  });
}

function runNodeRunner(runner, args, cwd, timeoutMs = 300000, node = process.execPath) {
  return runExpectedFailure(node, [runner, ...args], timeoutMs, {
    cwd,
    env: {...process.env, RESILIENT_BENCHMARK_FAIL_SMOKE: '1'},
  });
}

function parseInvocation(text) {
  const allowed = new Set(['node', 'cwd', 'runner', 'preload', 'config']);
  const result = {};
  for (const line of text.split('\n').filter(Boolean)) {
    const match = /^(node|cwd|runner|preload|config)=([A-Za-z0-9+/]+={0,2})$/.exec(line);
    if (!match || Object.hasOwn(result, match[1])) throw new Error('Invalid invocation metadata');
    const bytes = Buffer.from(match[2], 'base64');
    if (bytes.toString('base64') !== match[2]) throw new Error('Invalid invocation metadata base64');
    let value;
    try { value = new TextDecoder('utf-8', {fatal: true}).decode(bytes); }
    catch { throw new Error('Invalid invocation metadata UTF-8'); }
    if (!path.isAbsolute(value) || value.includes('\0') || value.includes('\n')) throw new Error('Invalid invocation metadata path');
    result[match[1]] = value;
  }
  if (Object.keys(result).length !== allowed.size) throw new Error('Incomplete invocation metadata');
  return result;
}

function failureConfig(text, report) {
  if (!path.isAbsolute(report) || /[\r\n]/.test(report)) throw new Error('Failure report path must be absolute');
  const matches = text.match(/^reportFile:[^\r\n]*\r?$/gm) || [];
  if (matches.length !== 1) throw new Error('Expected exactly one reportFile in smoke config');
  return text.replace(/^reportFile:[^\r\n]*\r?$/m, `reportFile:${report}`);
}

if (require.main === module) {
  const build = path.resolve(__dirname, 'build');
  const boundaryArgument = process.argv[2];
  const boundary = path.resolve(boundaryArgument || '');
  if (!boundaryArgument || process.argv.length !== 3 || !boundary.startsWith(build + path.sep)) {
    throw new Error('Usage: verify-js-failure.cjs <boundary-file-under-benchmark/build>');
  }
  const current = verifyCurrent(build, boundary);
  const invocation = parseInvocation(fs.readFileSync(current.metadata, 'utf8'));
  if (invocation.config !== current.config) throw new Error('Captured config path does not match current invocation');
  const output = path.dirname(current.config);
  const id = randomUUID();
  const config = path.join(output, `failure-${id}.txt`);
  const report = path.join(output, `failure-${id}.json`);
  if (config.includes(' ')) throw new Error('Node benchmark config path must not contain spaces');
  for (const file of [invocation.node, invocation.runner, invocation.preload, invocation.config]) {
    if (!fs.statSync(file).isFile()) throw new Error(`Missing invocation file: ${file}`);
  }
  if (!fs.statSync(invocation.cwd).isDirectory()) throw new Error('Missing runner working directory');
  fs.writeFileSync(config, failureConfig(fs.readFileSync(invocation.config, 'utf8'), report), {flag: 'wx'});
  runNodeRunner('--require', [invocation.preload, '-r', 'source-map-support/register', invocation.runner, config], invocation.cwd, 300000, invocation.node).then(result => {
    if (!result.ok) {
      console.error('Failure smoke did not prove an intentional nonzero rejection:', result);
      process.exitCode = 1;
    } else {
      console.log(`Intentional benchmark rejection exited nonzero (${result.code}) without hanging.`);
    }
  });
}
module.exports = { runExpectedFailure, runNodeRunner, parseInvocation, failureConfig };
