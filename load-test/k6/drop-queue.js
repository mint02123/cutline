// 대기열을 거치는 드롭 오픈 재현 (설계 문서 2-3): 줄 서기 → 순번 조회(서버가 정한 간격) → 입장 → 주문 → 결제.
// 실행: SCRIPT=drop-queue.js ./run-baseline.sh <결과폴더> (DB 초기화, 앱 기동, 회원 시드, 측정, 정합성 확인까지 한 번에)
//
// 환경 변수 (drop-rush.js와 같음)
//   BASE_URL, BUYERS(3000), STOCK(1000), MEMBER_FROM(1), PAY_RATIO(0.8), ARRIVAL_MS(1000)
//
// 사용자 기준 지표
//   click_to_sold_out  처음 누른 순간부터 "품절"을 보기까지 (줄 서기 거절, 줄에서 품절 안내, 입장 후 주문 품절 중 하나)
//   click_to_admit     처음 누른 순간부터 입장까지
//   order_latency_created  입장한 사람의 주문 요청 응답 시간 (설계 목표: p95 1초 이하)
// 공정성: 구매자마다 "FAIR <처음 누른 시각 ms> <결과>" 한 줄을 남기고, run-baseline.sh가 도착 순서와 구매 성공의 일치율을 계산한다.
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

// 409(품절, 오픈 전)는 정상적인 거절이므로 실패로 세지 않는다. 5xx·403·연결 오류는 실패다.
http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409));

const joinLatency = new Trend('queue_join_latency', true);
const statusLatency = new Trend('queue_status_latency', true);
const clickToAdmit = new Trend('click_to_admit', true);
const clickToSoldOut = new Trend('click_to_sold_out', true);
const createdLatency = new Trend('order_latency_created', true);
const soldOutAfterAdmitLatency = new Trend('order_latency_sold_out', true);
const paymentLatency = new Trend('payment_latency', true);
const polls = new Counter('queue_polls');
const ordered = new Counter('orders_created');
const soldOutAtJoin = new Counter('sold_out_at_join');
const soldOutInLine = new Counter('sold_out_in_line');
const soldOutAfterAdmit = new Counter('sold_out_after_admit');
const bypassed = new Counter('admitted_bypassed');
const otherRejects = new Counter('other_reject');
const paid = new Counter('payments_paid');
const paymentRejects = new Counter('payments_rejected');

export const options = {
    setupTimeout: '60s',
    scenarios: {
        rush: {
            executor: 'per-vu-iterations',
            vus: BUYERS,
            iterations: 1,
            maxDuration: '5m',
        },
    },
    thresholds: {
        http_req_failed: ['rate==0'],
        checks: ['rate==1'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const json = { headers: { 'Content-Type': 'application/json' } };

export function setup() {
    const seller = http.post(`${BASE_URL}/api/sellers`, JSON.stringify({ name: 'k6 shop', feeRateBps: 1000 }), json).json();
    const product = http.post(`${BASE_URL}/api/sellers/${seller.id}/products`,
        JSON.stringify({ name: 'k6 Jordan 1', price: PRICE }), json).json();
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
    const queueUrl = `${BASE_URL}/api/drops/${data.dropId}/queue`;

    const arrival = data.opensAt + (Math.random() - 0.5) * ARRIVAL_MS;
    const wait = arrival - Date.now();
    if (wait > 0) {
        sleep(wait / 1000);
    }

    // 1) 줄 서기. 오픈 전이면 새로고침하듯 다시 누른다. "처음 누른 시각"은 오픈 뒤 첫 요청이다.
    let clickedAt = null;
    let res;
    for (let attempt = 0; attempt < 100; attempt++) {
        const sentAt = Date.now();
        res = http.post(queueUrl, null, { headers, tags: { name: 'POST /drops/{id}/queue' } });
        if (res.status === 409 && res.json('code') === 'DROP_NOT_OPEN') {
            sleep(0.2);
            continue;
        }
        clickedAt = sentAt;
        joinLatency.add(res.timings.duration);
        break;
    }
    if (res.status === 409 && res.json('code') === 'SOLD_OUT') {
        soldOutAtJoin.add(1);
        clickToSoldOut.add(Date.now() - clickedAt);
        fair(clickedAt, 'sold_out');
        return;
    }
    if (!check(res, { 'joined queue': (r) => r.status === 200 })) {
        otherRejects.add(1);
        fair(clickedAt, 'error');
        return;
    }

    // 2) 순번 조회. 서버가 내려준 간격(pollAfterMs)을 따른다.
    let view = res.json();
    while (view.state === 'WAITING') {
        sleep(view.pollAfterMs / 1000);
        res = http.get(`${queueUrl}/me`, { headers, tags: { name: 'GET /drops/{id}/queue/me' } });
        polls.add(1);
        statusLatency.add(res.timings.duration);
        if (res.status !== 200) {
            break;
        }
        view = res.json();
    }
    if (view.state === 'SOLD_OUT') {
        soldOutInLine.add(1);
        clickToSoldOut.add(Date.now() - clickedAt);
        fair(clickedAt, 'sold_out');
        return;
    }
    if (!check(view, { 'admitted': (v) => v.state === 'ADMITTED' })) {
        otherRejects.add(1);
        fair(clickedAt, `error_${view.state}`);
        return;
    }
    clickToAdmit.add(Date.now() - clickedAt);
    if (view.bypassed) {
        bypassed.add(1);
    }

    // 3) 주문
    res = http.post(`${BASE_URL}/api/drops/${data.dropId}/orders`, JSON.stringify({ qty: 1 }), {
        headers: Object.assign({ 'Idempotency-Key': `queue-${memberId}`, 'X-Admission-Token': view.admissionToken }, headers),
        tags: { name: 'POST /drops/{id}/orders' },
    });
    if (res.status !== 201) {
        if (res.status === 409 && res.json('code') === 'SOLD_OUT') {
            soldOutAfterAdmit.add(1);
            soldOutAfterAdmitLatency.add(res.timings.duration);
            clickToSoldOut.add(Date.now() - clickedAt);
            fair(clickedAt, 'sold_out');
        } else {
            otherRejects.add(1);
            fair(clickedAt, `error_${res.status}`);
        }
        check(res, { 'order rejected only for a known reason': (r) => r.status === 409 });
        return;
    }
    ordered.add(1);
    createdLatency.add(res.timings.duration);
    fair(clickedAt, 'bought');

    // 4) 결제
    if (Math.random() >= PAY_RATIO) {
        return;
    }
    sleep(1 + Math.random() * 2);
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

function fair(clickedAt, outcome) {
    console.log(`FAIR ${clickedAt} ${outcome}`);
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
    const t = (name) => `med=${ms(v(name, 'med'))} p95=${ms(v(name, 'p(95)'))} p99=${ms(v(name, 'p(99)'))} max=${ms(v(name, 'max'))}`;
    return [
        '',
        `buyers=${BUYERS} stock=${STOCK}`,
        `outcome: bought=${v('orders_created', 'count')} sold_out_at_join=${v('sold_out_at_join', 'count')} ` +
            `sold_out_in_line=${v('sold_out_in_line', 'count')} sold_out_after_admit=${v('sold_out_after_admit', 'count')} ` +
            `bypassed=${v('admitted_bypassed', 'count')} other=${v('other_reject', 'count')}`,
        `queue join:      ${t('queue_join_latency')}`,
        `queue status:    ${t('queue_status_latency')} polls=${v('queue_polls', 'count')}`,
        `click→admit:     ${t('click_to_admit')}`,
        `click→sold out:  ${t('click_to_sold_out')}`,
        `order created:   ${t('order_latency_created')}`,
        `order sold out:  ${t('order_latency_sold_out')}`,
        `payments: paid=${v('payments_paid', 'count')} rejected=${v('payments_rejected', 'count')} ${t('payment_latency')}`,
        `http: reqs=${v('http_reqs', 'count')} failed_rate=${v('http_req_failed', 'rate')} checks=${v('checks', 'rate')}`,
        '',
    ].join('\n');
}
