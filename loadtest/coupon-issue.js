// 선착순 쿠폰 동시 발급 부하 테스트 - VU 1개가 회원 1명으로 1번만 요청해 "N명이 같은 순간에 누름"을 재현
// 실행은 run.sh로 - 쿠폰 생성, 결과 검증(DB 대조)까지 함께 처리함
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18087';
const COUPON_ID = __ENV.COUPON_ID;
const VUS = Number(__ENV.VUS || 100);
const QUANTITY = Number(__ENV.QUANTITY || 50);
const ISSUED_COUNT = Math.min(VUS, QUANTITY);

// 결과 분류 - 409 하나에 소진과 중복이 함께 있어 상태 코드가 아니라 응답 본문 코드로 센다
const issueResult = new Counter('issue_result');
// 연결 대기·연결 수립까지 포함한 요청 1건의 전체 시간 - http_req_duration에는 이 구간이 빠져 있음
const requestTotal = new Trend('request_total', true);
// 시나리오 시작부터 이 요청을 보낸 시점까지 - 이 값이 넓게 퍼져 있으면 "동시" 요청이 아님
const dispatchOffset = new Trend('dispatch_offset', true);

// 204(발급)와 409(소진·중복)는 정상 응답 - 기본값은 4xx를 실패로 집계함
http.setResponseCallback(http.expectedStatuses(204, 409));

export const options = {
    scenarios: {
        spike: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '3m',
        },
    },
    // 끝난 VU가 연결을 붙잡아 서버의 연결 슬롯을 차지하지 않도록 요청마다 새 연결
    noConnectionReuse: true,
    summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        iteration_duration: ['p(99)<1000'], // 목표 - 1만 명 기준 p99 1초 이내
        // 정합성 필수 조건 - 하나라도 어긋나면 지연 시간과 관계없이 실패
        'issue_result{result:ISSUED}': [`count==${ISSUED_COUNT}`],
        'issue_result{result:COUPON_SOLD_OUT}': [`count==${VUS - ISSUED_COUNT}`],
        'issue_result{result:ALREADY_ISSUED}': ['count==0'],
        'issue_result{result:UNREACHED}': ['count==0'],
        'issue_result{result:OTHER}': ['count==0'],
        // 결과별 지연 시간을 요약에 보이게 하기 위한 항목 - 판정에는 쓰지 않음
        'request_total{result:ISSUED}': ['max>=0'],
        'request_total{result:COUPON_SOLD_OUT}': ['max>=0'],
        'http_req_connecting': ['max>=0'],
        'http_req_blocked': ['max>=0'],
    },
};

export function setup() {
    if (!COUPON_ID) throw new Error('COUPON_ID 환경 변수가 필요합니다.');
    return { startedAt: Date.now() };
}

export default function (data) {
    const memberId = Number(__ENV.MEMBER_OFFSET || 0) + exec.vu.idInTest;
    dispatchOffset.add(Date.now() - data.startedAt);

    const res = http.post(`${BASE_URL}/api/coupons/${COUPON_ID}/issue`, null, {
        headers: { 'X-Member-Id': String(memberId) },
    });

    const result = classify(res);
    issueResult.add(1, { result });
    const t = res.timings;
    requestTotal.add(t.blocked + t.connecting + t.sending + t.waiting + t.receiving, { result });

    // 발급 회원 목록은 VU끼리 공유할 수 없어 콘솔로 남기고 run.sh가 DB 발급 회원과 대조함
    if (result === 'ISSUED') console.log(`ISSUED ${memberId}`);
    if (result === 'UNREACHED' || result === 'OTHER') {
        console.warn(`FAILED member=${memberId} status=${res.status} error_code=${res.error_code} error=${res.error}`);
    }
}

function classify(res) {
    if (res.status === 0) return 'UNREACHED'; // 연결 거부·타임아웃 등 서버 응답을 받지 못함
    if (res.status === 204) return 'ISSUED';
    if (res.status === 409) {
        const code = res.json('code');
        if (code === 'COUPON_SOLD_OUT' || code === 'ALREADY_ISSUED') return code;
    }
    return 'OTHER';
}
