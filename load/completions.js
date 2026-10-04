// k6 load test for the completion endpoint.
//
// Run it through load/run.sh, which starts a fresh stack, runs a scenario, and then checks the
// database for the properties the pipeline promises. See load/README.md.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Gauge, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'throughput';
const LABEL = __ENV.LABEL || SCENARIO;

// contention: REQUESTS completions of ONE habit, VUS of them in flight at a time.
const REQUESTS = Number(__ENV.REQUESTS || 8000);
const VUS = Number(__ENV.VUS || 200);

// throughput: RATE completions per second for DURATION seconds, each of a different habit,
// spread over USERS users.
const RATE = Number(__ENV.RATE || 100);
const DURATION = Number(__ENV.DURATION || 30);
const USERS = Number(__ENV.USERS || 50);
const P95_LIMIT_MS = Number(__ENV.P95_LIMIT_MS || 200);

// Time for the completion request only, without the setup calls.
const completionDuration = new Trend('completion_duration', true);

// Wall-clock time of each completion. Its min and max bound the load phase, so the reported
// rate covers the load itself and not the time k6 spent registering users and creating habits.
const loadClock = new Gauge('load_clock_ms');

const scenarios = {
  contention: {
    executor: 'shared-iterations',
    vus: VUS,
    iterations: REQUESTS,
    maxDuration: '10m',
  },
  throughput: {
    // Open model: requests start on schedule whether or not earlier ones have finished,
    // so a slow server shows up as latency instead of quietly lowering the load.
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration: `${DURATION}s`,
    preAllocatedVUs: Math.max(20, RATE),
    maxVUs: Math.max(100, RATE * 4),
  },
};

const thresholds = {
  contention: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
  },
  throughput: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    completion_duration: [`p(95)<${P95_LIMIT_MS}`],
    dropped_iterations: ['count==0'],
  },
};

if (!scenarios[SCENARIO]) {
  throw new Error(`Unknown SCENARIO '${SCENARIO}'. Use one of: ${Object.keys(scenarios).join(', ')}`);
}

export const options = {
  scenarios: { [SCENARIO]: scenarios[SCENARIO] },
  thresholds: thresholds[SCENARIO],
  setupTimeout: '10m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function register(index) {
  const email = `load-${Date.now()}-${index}-${Math.floor(Math.random() * 1e9)}@example.com`;
  const res = http.post(
    `${BASE_URL}/api/auth/register`,
    JSON.stringify({ email, password: 'load-test-password' }),
    { headers: JSON_HEADERS, tags: { name: 'register' } },
  );
  if (res.status !== 200) {
    fail(`register failed: ${res.status} ${res.body}`);
  }
  return res.json('token');
}

function createHabits(token, count) {
  const headers = { ...JSON_HEADERS, Authorization: `Bearer ${token}` };
  const ids = [];
  for (let start = 0; start < count; start += 50) {
    const batch = [];
    for (let i = start; i < Math.min(start + 50, count); i++) {
      batch.push([
        'POST',
        `${BASE_URL}/api/habits`,
        JSON.stringify({ name: `load habit ${i}`, description: '', frequency: 'DAILY' }),
        { headers, tags: { name: 'create_habit' } },
      ]);
    }
    for (const res of http.batch(batch)) {
      if (res.status !== 201) {
        fail(`create habit failed: ${res.status} ${res.body}`);
      }
      ids.push(res.json('id'));
    }
  }
  return ids;
}

// Returns one token per user and, for each user, the ids of their habits.
export function setup() {
  if (SCENARIO === 'contention') {
    const token = register(0);
    return { tokens: [token], habits: [createHabits(token, 1)] };
  }

  // A few more habits than requests, so every request completes a habit nobody has completed yet.
  const habitsPerUser = Math.ceil((RATE * DURATION * 1.05 + 10) / USERS);
  const tokens = [];
  const habits = [];
  for (let u = 0; u < USERS; u++) {
    const token = register(u);
    tokens.push(token);
    habits.push(createHabits(token, habitsPerUser));
  }
  return { tokens, habits };
}

export default function (data) {
  let user = 0;
  let habit = 0;
  if (SCENARIO !== 'contention') {
    // Walk the users round-robin so consecutive requests belong to different users.
    const i = exec.scenario.iterationInTest;
    user = i % data.tokens.length;
    habit = Math.floor(i / data.tokens.length);
  }
  const habitId = data.habits[user][habit];
  if (habitId === undefined) {
    fail('ran out of prepared habits');
  }

  loadClock.add(Date.now());
  const res = http.post(`${BASE_URL}/api/habits/${habitId}/complete`, null, {
    headers: { Authorization: `Bearer ${data.tokens[user]}` },
    tags: { name: 'complete' },
  });
  loadClock.add(Date.now());

  completionDuration.add(res.timings.duration);
  check(res, { 'completion returned 200': (r) => r.status === 200 });
}

function value(metric, key, fallback = 0) {
  return metric && metric.values && metric.values[key] !== undefined ? metric.values[key] : fallback;
}

// A short, stable summary: one human-readable block and one machine-readable RESULT line.
export function handleSummary(data) {
  const m = data.metrics;
  const completions = value(m.iterations, 'count');
  const failedChecks = value(m.checks, 'fails');
  const failedRequests = value(m.http_req_failed, 'passes'); // for this metric "passes" counts failures
  const dropped = value(m.dropped_iterations, 'count');
  const loadSeconds = (value(m.load_clock_ms, 'max') - value(m.load_clock_ms, 'min')) / 1000;
  const perSecond = loadSeconds > 0 ? completions / loadSeconds : 0;
  const d = m.completion_duration;
  const ms = (key) => value(d, key).toFixed(2);

  const failedThresholds = [];
  for (const [name, metric] of Object.entries(m)) {
    for (const [expression, result] of Object.entries(metric.thresholds || {})) {
      if (!result.ok) {
        failedThresholds.push(`${name} ${expression}`);
      }
    }
  }

  const lines = [
    '',
    `== ${LABEL} ==`,
    `  completions        ${completions} (${perSecond.toFixed(1)}/s)`,
    `  failed requests    ${failedRequests}`,
    `  failed checks      ${failedChecks}`,
    `  dropped requests   ${dropped}`,
    `  latency ms         avg ${ms('avg')}  p50 ${ms('med')}  p95 ${ms('p(95)')}  p99 ${ms('p(99)')}  max ${ms('max')}`,
    `  thresholds         ${failedThresholds.length === 0 ? 'all passed' : 'FAILED: ' + failedThresholds.join('; ')}`,
    `RESULT label=${LABEL} completions=${completions} per_second=${perSecond.toFixed(1)} failed=${failedRequests} dropped=${dropped}`
      + ` avg_ms=${ms('avg')} p50_ms=${ms('med')} p95_ms=${ms('p(95)')} p99_ms=${ms('p(99)')} max_ms=${ms('max')}`,
    '',
  ];
  return { stdout: lines.join('\n') };
}
