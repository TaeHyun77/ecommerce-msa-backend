#!/usr/bin/env bash
# 선착순 쿠폰 부하 테스트 1회 실행 - 새 쿠폰 생성 → k6 실행 → DB와 결과 대조
# 실행마다 새 쿠폰을 만들므로 회원 번호가 이전 실행과 겹쳐도 중복 발급으로 집계되지 않음
#
# 사용 예 (로컬): VUS=100 QUANTITY=50 ./loadtest/run.sh
#   Redis 수량도 대조하려면: REDIS_CLI="docker exec ecommerce-redis-1 redis-cli" (Redis 선차감 적용 후)
# 사용 예 (EC2 k6 인스턴스):
#   VUS=1000 QUANTITY=500 BASE_URL=http://10.0.1.10:8087 \
#   MYSQL_HOST=10.0.1.10 MYSQL_USER=root MYSQL_PASSWORD=... ./loadtest/run.sh
set -euo pipefail

: "${VUS:?VUS(동시 요청 수)를 지정해야 합니다}"
: "${QUANTITY:?QUANTITY(쿠폰 수량)를 지정해야 합니다}"
BASE_URL=${BASE_URL:-http://localhost:18087}
LABEL=${LABEL:-}

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
RESULT_DIR="$SCRIPT_DIR/results/$(date +%Y%m%d-%H%M%S)-vus${VUS}${LABEL:+-$LABEL}"
mkdir -p "$RESULT_DIR"

# MYSQL_HOST가 없으면 로컬 compose의 MySQL 컨테이너를 사용
mysql_exec() {
    if [[ -n "${MYSQL_HOST:-}" ]]; then
        MYSQL_PWD="$MYSQL_PASSWORD" mysql -h "$MYSQL_HOST" -u "${MYSQL_USER:-root}" -N -B ecommerce_promotion
    else
        docker exec -i ecommerce-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B ecommerce_promotion'
    fi
}

# 1인 1장은 유니크 인덱스에만 의존하므로 없으면 측정 자체가 무의미
if [[ $(echo "SHOW INDEX FROM member_coupon WHERE Key_name = 'uk_member_coupon_coupon_member';" | mysql_exec | wc -l) -eq 0 ]]; then
    echo "member_coupon에 유니크 인덱스(uk_member_coupon_coupon_member)가 없습니다. 중단합니다." >&2
    exit 1
fi

# 앱(JVM)과 DB의 시간대가 달라도 발급 기간 안에 들도록 넉넉하게 잡음
COUPON_ID=$(mysql_exec <<SQL
INSERT INTO coupon (name, discount_type, discount_value, \`type\`, quantity,
                    issue_start_at, issue_end_at, use_start_at, use_end_at, created_at, updated_at)
VALUES ('부하 테스트 쿠폰 vus=${VUS}', 'AMOUNT', 1000, 'FIRST_COME', ${QUANTITY},
        NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 2 DAY, NOW(6), NOW(6));
SELECT LAST_INSERT_ID();
SQL
)
echo "쿠폰 생성 id=$COUPON_ID, 수량=$QUANTITY, 동시 요청=$VUS, 결과 디렉터리=$RESULT_DIR"

# 측정 조건 기록 - 커밋 동기화·격리 수준에 따라 처리량 상한이 달라짐
mysql_exec > "$RESULT_DIR/mysql-variables.txt" <<SQL
SHOW VARIABLES WHERE Variable_name IN ('version', 'transaction_isolation', 'innodb_flush_log_at_trx_commit',
    'sync_binlog', 'log_bin', 'innodb_buffer_pool_size', 'max_connections');
SQL

set +e # 목표 미달(threshold 실패)이어도 검증까지 진행
k6 run \
    -e BASE_URL="$BASE_URL" -e COUPON_ID="$COUPON_ID" -e VUS="$VUS" -e QUANTITY="$QUANTITY" \
    --console-output="$RESULT_DIR/console.log" \
    "$SCRIPT_DIR/coupon-issue.js" | tee "$RESULT_DIR/summary.txt"
K6_EXIT=${PIPESTATUS[0]}
set -e

EXPECTED_ISSUED=$(( VUS < QUANTITY ? VUS : QUANTITY ))
{
    echo "== DB 검증 (쿠폰 id=$COUPON_ID) =="
    REMAINING=$(echo "SELECT quantity FROM coupon WHERE id = $COUPON_ID;" | mysql_exec)
    ISSUED_ROWS=$(echo "SELECT COUNT(*) FROM member_coupon WHERE coupon_id = $COUPON_ID;" | mysql_exec)
    DUPLICATES=$(echo "SELECT COUNT(*) FROM (SELECT member_id FROM member_coupon WHERE coupon_id = $COUPON_ID GROUP BY member_id HAVING COUNT(*) > 1) d;" | mysql_exec)
    echo "남은 수량: $REMAINING (기대값 $(( QUANTITY - EXPECTED_ISSUED )))"
    echo "발급 행 수: $ISSUED_ROWS (기대값 $EXPECTED_ISSUED)"
    echo "회원별 중복 발급: $DUPLICATES (기대값 0)"
    # Redis 선차감을 쓸 때만 - Redis가 DB보다 적으면 발급되지 못한 수량이 생긴 것
    if [[ -n "${REDIS_CLI:-}" ]]; then
        echo "Redis 남은 수량: $($REDIS_CLI GET "coupon:stock:$COUPON_ID") (DB 남은 수량 $REMAINING와 같아야 함)"
    fi

    # 클라이언트가 타임아웃으로 끊어도 서버는 커밋할 수 있어 건수가 아니라 회원 목록으로 대조
    sed -n 's/.*msg="ISSUED \([0-9]*\)".*/\1/p' "$RESULT_DIR/console.log" | sort -n > "$RESULT_DIR/issued-k6.txt"
    echo "SELECT member_id FROM member_coupon WHERE coupon_id = $COUPON_ID;" | mysql_exec | sort -n > "$RESULT_DIR/issued-db.txt"
    if diff -q "$RESULT_DIR/issued-k6.txt" "$RESULT_DIR/issued-db.txt" > /dev/null; then
        echo "204 응답 회원 = DB 발급 회원: 일치 ($(wc -l < "$RESULT_DIR/issued-db.txt" | tr -d ' ')명)"
    else
        echo "204 응답 회원 = DB 발급 회원: 불일치 - issued-k6.txt와 issued-db.txt를 비교하세요"
    fi

    # 앱과 같은 호스트에서 실행할 때만 - 접근 로그는 버퍼에 모았다가 쓰므로 반영될 때까지 기다림
    if [[ -n "${ACCESS_LOG_DIR:-}" && -d "$ACCESS_LOG_DIR" ]]; then
        sleep 12
        RECEIVED=$(cat "$ACCESS_LOG_DIR"/access*.log | grep -c "/api/coupons/$COUPON_ID/issue " || true)
        echo "서버가 받은 요청: $RECEIVED (k6가 보낸 요청 $VUS)"
    else
        echo "서버가 받은 요청: 앱 호스트에서 확인 - grep -c '/api/coupons/$COUPON_ID/issue ' <접근 로그 디렉터리>/access*.log"
    fi
    echo "k6 종료 코드: $K6_EXIT (0이면 목표·정합성 기준 모두 통과, 99면 기준 미달)"
} | tee "$RESULT_DIR/verify.txt"
