import http from 'k6/http';
import { check } from 'k6';

const mode = __ENV.LOAD_MODE || 'read';
const rate = positiveInteger(__ENV.LOAD_RATE, 10);
const duration = __ENV.LOAD_DURATION || '1m';
const preAllocatedVUs = positiveInteger(__ENV.LOAD_PREALLOCATED_VUS, 10);
const maxVUs = positiveInteger(__ENV.LOAD_MAX_VUS, 50);
const p95Ms = positiveInteger(__ENV.LOAD_P95_MS, 500);
const p99Ms = positiveInteger(__ENV.LOAD_P99_MS, 1000);

export const options = {
  discardResponseBodies: false,
  scenarios: {
    webhook_ingress: {
      executor: 'constant-arrival-rate',
      exec: 'sendWebhook',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs,
      maxVUs,
      gracefulStop: '10s',
      tags: {
        mode,
      },
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    dropped_iterations: ['count==0'],
    'http_req_failed{endpoint:vk_webhook}': ['rate<0.01'],
    'http_req_duration{endpoint:vk_webhook}': [
      `p(95)<${p95Ms}`,
      `p(99)<${p99Ms}`,
    ],
  },
  summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const baseUrl = required('LOAD_BASE_URL').replace(/\/+$/, '');
  const groupId = required('LOAD_VK_GROUP_ID');
  const secret = required('LOAD_VK_SECRET');

  if (mode !== 'read' && mode !== 'write') {
    throw new Error('LOAD_MODE must be read or write');
  }

  return {
    url: `${baseUrl}/vk/webhook`,
    groupId,
    secret,
  };
}

export function sendWebhook(config) {
  const uniqueId = `${Date.now()}-${__VU}-${__ITER}`;
  const payload = mode === 'write'
    ? writePayload(config, uniqueId)
    : readPayload(config, uniqueId);

  const response = http.post(config.url, JSON.stringify(payload), {
    headers: {
      'Content-Type': 'application/json',
    },
    tags: {
      endpoint: 'vk_webhook',
      mode,
    },
    timeout: '10s',
  });

  check(response, {
    'webhook returns 200': (result) => result.status === 200,
    'webhook acknowledges request': (result) => result.body === 'ok',
  });
}

function readPayload(config, uniqueId) {
  return {
    type: 'load_test_ping',
    group_id: config.groupId,
    event_id: `load-read-${uniqueId}`,
    secret: config.secret,
    object: {},
  };
}

function writePayload(config, uniqueId) {
  return {
    type: 'message_new',
    group_id: config.groupId,
    event_id: `load-write-${uniqueId}`,
    secret: config.secret,
    object: {
      message: {
        id: (__VU * 1000000000) + __ITER,
        date: Math.floor(Date.now() / 1000),
        peer_id: `load-peer-${uniqueId}`,
        from_id: `load-user-${uniqueId}`,
        text: `Load test message ${uniqueId}`,
        attachments: [],
      },
    },
  };
}

function required(name) {
  const value = __ENV[name];
  if (!value || value.trim() === '') {
    throw new Error(`${name} is required`);
  }
  return value;
}

function positiveInteger(value, fallback) {
  if (value === undefined || value === '') {
    return fallback;
  }
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`Expected a positive integer, got: ${value}`);
  }
  return parsed;
}
