// 드롭 오픈 순간 재현: 구매자 BUYERS명이 오픈 시각에 거의 동시에 주문하고, 성공한 사람 중 PAY_RATIO만 결제한다.
// 실행: ./run-baseline.sh (DB 초기화, 앱 기동, 회원 시드, 측정, 정합성 확인까지 한 번에)
//
// 환경 변수
//   BASE_URL      대상 주소 (기본 http://localhost:8080)
//   BUYERS        구매자 수 = 가상 사용자 수 (기본 3000)
//   STOCK         드롭 재고 (기본 1000)
//   MEMBER_FROM   시드된 회원 ID 시작값 (기본 1)
//   PAY_RATIO     주문 성공 후 결제까지 가는 비율 (기본 0.8)
//   ARRIVAL_MS    오픈 시각 전후로 구매자가 도착하는 폭 (기본 1000, 0이면 모두 같은 순간)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const BUYERS = parseInt(__ENV.BUYERS || '3000', 10);
const STOCK = parseInt(__ENV.STOCK || '1000', 10);
const MEMBER_FROM = parseInt(__ENV.MEMBER_FROM || '1', 10);
const PAY_RATIO = parseFloat(__ENV.PAY_RATIO || '0.8');
const ARRIVAL_MS = parseInt(__ENV.ARRIVAL_MS || '1000', 10);
const PRICE = 219000;

// 409(품절, 오픈 전)는 정상적인 거절이므로 실패로 세지 않는다. 5xx와 연결 오류만 실패다.
http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409));

const orderLatency = new Trend('order_latency', true);
const createdLatency = new Trend('order_latency_created', true);   // 주문 성공 응답
const soldOutLatency = new Trend('order_latency_sold_out', true);  // 품절 응답 (사용자가 "품절"을 보기까지)
const paymentLatency = new Trend('payment_latency', true);
const ordered = new Counter('orders_created');
const soldOut = new Counter('orders_sold_out');
const notOpenRetries = new Counter('orders_not_open_retry');
const otherRejects = new Counter('orders_other_reject');
const paid = new Counter('payments_paid');
const paymentRejects = new Counter('payments_rejected');

export const options = {
    setupTimeout: '60s',
    scenarios: {
        rush: {
            executor: 'per-vu-iterations',
            vus: BUYERS,
            iterations: 1,
            maxDuration: '3m',
        },
    },
    thresholds: {
        http_req_failed: ['rate==0'],      // 5xx·연결 오류 0
        checks: ['rate==1'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const json = { headers: { 'Content-Type': 'application/json' } };

export function setup() {
    const seller = http.post(`${BASE_URL}/api/sellers`, JSON.stringify({ name: 'k6 shop', feeRateBps: 1000 }), json).json();
    const product = http.post(`${BASE_URL}/api/sellers/${seller.id}/products`,
        JSON.stringify({ name: 'k6 Jordan 1', price: PRICE }), json).json();
    // 모든 가상 사용자가 준비될 시간을 두고 오픈한다.
    const opensAt = new Date(Date.now() + 20000 + BUYERS * 2);
    opensAt.setMilliseconds(0);
    const drop = http.post(`${BASE_URL}/api/sellers/${seller.id}/drops`, JSON.stringify({
        productId: product.id, totalQty: STOCK, perMemberLimit: 1,
        opensAt: opensAt.toISOString(), holdMinutes: 10,
    }), json);
    check(drop, { 'drop created': (r) => r.status === 201 });
    return { dropId: drop.json().id, opensAt: opensAt.getTime() };
}

export default function (data) {
    const memberId = MEMBER_FROM + __VU - 1;
    const headers = { 'Content-Type': 'application/json', 'X-Member-Id': String(memberId) };

    // 오픈 시각 근처에 도착 (ARRIVAL_MS 폭 안에서 고르게)
    const arrival = data.opensAt + (Math.random() - 0.5) * ARRIVAL_MS;
    const wait = arrival - Date.now();
    if (wait > 0) {
        sleep(wait / 1000);
    }

    let res;
    for (let attempt = 0; attempt < 50; attempt++) {
        res = http.post(`${BASE_URL}/api/drops/${data.dropId}/orders`, JSON.stringify({ qty: 1 }), {
            headers: Object.assign({ 'Idempotency-Key': `rush-${memberId}` }, headers),
            tags: { name: 'POST /drops/{id}/orders' },
        });
        if (res.status === 409 && res.json('code') === 'DROP_NOT_OPEN') {
            notOpenRetries.add(1); // 오픈 전 응답은 지연 시간 통계에서 뺀다 (DB를 거의 안 거치는 빠른 거절)
            sleep(0.2); // 오픈 스케줄러(1초 주기)가 상태를 바꿀 때까지 사용자가 새로고침하는 흐름
            continue;
        }
        orderLatency.add(res.timings.duration); // 주문 성공 또는 품절: 실제로 재고 행을 다툰 요청
        break;
    }

    if (res.status !== 201) {
        if (res.status === 409 && res.json('code') === 'SOLD_OUT') {
            soldOut.add(1);
            soldOutLatency.add(res.timings.duration);
        } else {
            otherRejects.add(1);
        }
        check(res, { 'order rejected only for a known reason': (r) => r.status === 409 });
        return;
    }
    ordered.add(1);
    createdLatency.add(res.timings.duration);

    if (Math.random() >= PAY_RATIO) {
        return; // 결제하지 않고 떠남 → 10분 뒤 만료로 재고 복구
    }
    sleep(1 + Math.random() * 2); // 결제창에서 머무는 시간
    const orderId = res.json('orderId');
    const pay = http.post(`${BASE_URL}/api/orders/${orderId}/payments`,
        JSON.stringify({ paymentKey: `pk-${orderId}`, amount: PRICE }),
        { headers, tags: { name: 'POST /orders/{id}/payments' } });
    paymentLatency.add(pay.timings.duration);
    if (pay.status === 200) {
        paid.add(1);
    } else {
        paymentRejects.add(1);
    }
    check(pay, { 'payment confirmed': (r) => r.status === 200 });
}

export function teardown(data) {
    const drop = http.get(`${BASE_URL}/api/drops/${data.dropId}`).json();
    const consistency = http.get(`${BASE_URL}/internal/consistency`).json();
    console.log(`drop remaining=${drop.remainingQty} consistency=${JSON.stringify(consistency)}`);
    check(consistency, { 'consistency checker passes': (c) => c.consistent === true });
}

export function handleSummary(data) {
    const out = __ENV.SUMMARY_OUT || 'summary.json';
    return { [out]: JSON.stringify(data, null, 2), stdout: textSummary(data) };
}

function textSummary(data) {
    const m = data.metrics;
    const v = (name, stat) => (m[name] ? m[name].values[stat] : 0);
    const ms = (x) => `${x.toFixed(1)}ms`;
    return [
        '',
        `buyers=${BUYERS} stock=${STOCK}`,
        `orders: created=${v('orders_created', 'count')} sold_out=${v('orders_sold_out', 'count')} ` +
            `not_open_retries=${v('orders_not_open_retry', 'count')} other=${v('orders_other_reject', 'count')}`,
        `order latency: med=${ms(v('order_latency', 'med'))} p95=${ms(v('order_latency', 'p(95)'))} ` +
            `p99=${ms(v('order_latency', 'p(99)'))} max=${ms(v('order_latency', 'max'))}`,
        `  created:  med=${ms(v('order_latency_created', 'med'))} p95=${ms(v('order_latency_created', 'p(95)'))} ` +
            `p99=${ms(v('order_latency_created', 'p(99)'))}`,
        `  sold out: med=${ms(v('order_latency_sold_out', 'med'))} p95=${ms(v('order_latency_sold_out', 'p(95)'))} ` +
            `p99=${ms(v('order_latency_sold_out', 'p(99)'))}`,
        `payments: paid=${v('payments_paid', 'count')} rejected=${v('payments_rejected', 'count')} ` +
            `p95=${ms(v('payment_latency', 'p(95)'))} p99=${ms(v('payment_latency', 'p(99)'))}`,
        `http: reqs=${v('http_reqs', 'count')} failed_rate=${v('http_req_failed', 'rate')} checks=${v('checks', 'rate')}`,
        '',
    ].join('\n');
}
