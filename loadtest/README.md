# 선착순 쿠폰 부하 테스트

쿠폰 500장에 N명이 동시에 발급을 요청할 때 정합성과 지연 시간을 측정합니다.

- **목표**: 1만 명 기준 `iteration_duration` p99 1초 이내
- **필수 조건**: 정확히 수량만큼 발급, 회원별 중복 0건, 204/409 외 응답·연결 실패 0건, 204를 받은 회원 = DB 발급 회원

k6의 `http_req_duration`에는 연결 대기·연결 수립 구간이 빠져 있습니다. 그래서 판정은 요청 1회 전체 시간인 `iteration_duration`으로 합니다.

## 구성

| 파일 | 역할 |
|---|---|
| `coupon-issue.js` | k6 시나리오. VU 1개가 회원 1명으로 1번만 요청합니다(`per-vu-iterations`). 결과는 응답 본문 코드로 분류합니다 |
| `run.sh` | 새 쿠폰 생성 → k6 실행 → DB 대조. 결과는 `results/<시각>-vus<N>/`에 저장합니다 |
| `promotion-service/.../application-loadtest.properties` | Tomcat 연결 한도와 대기열(backlog), 접근 로그 설정 |

## 로컬 실행

```bash
docker compose up -d mysql
./gradlew :promotion-service:bootJar
ACCESS_LOG_DIR=/tmp/promotion-loadtest java -Xms1g -Xmx1g "-Xlog:gc*:file=/tmp/promotion-loadtest/gc.log" \
  -jar promotion-service/build/libs/promotion-service-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=loadtest --server.port=18087

# 다른 터미널에서 실행 - 1단계는 쿠폰 수량을 VU보다 작게 잡아 소진 경로까지 검증
VUS=100 QUANTITY=50 ACCESS_LOG_DIR=/tmp/promotion-loadtest ./loadtest/run.sh
```

compose의 `promotion-service` 컨테이너가 8087 포트를 쓰므로, 로컬에서는 18087 포트로 띄웁니다.

## EC2 실행 (k6 인스턴스 1대, 앱+MySQL 인스턴스 1대, 같은 AZ)

### 앱 인스턴스
```bash
# 커널 listen backlog - Tomcat accept-count(10000)는 이 값을 넘을 수 없음
sudo sysctl -w net.core.somaxconn=65535 net.ipv4.tcp_max_syn_backlog=65535
```
- Docker로 띄우면 `--network host`를 씁니다. 브리지 네트워크의 NAT와 연결 추적을 거치지 않게 하고, 위 커널 값이 그대로 적용되게 하기 위해서입니다. 브리지에서는 컨테이너마다 `--sysctl`을 따로 줘야 합니다.
- 앱 환경 변수
  - `SPRING_PROFILES_ACTIVE=loadtest`
  - `JAVA_TOOL_OPTIONS="-Xms2g -Xmx2g -Xlog:gc*:file=/tmp/promotion-loadtest/gc.log"`
  - compose 공통값 `-Xmx256m`은 쓰지 않습니다.
- 8087(앱)과 3306(MySQL)은 보안 그룹에서 k6 인스턴스의 사설 IP에만 엽니다.
- 실행 전에 `SHOW CREATE TABLE member_coupon`으로 유니크 인덱스가 있는지 확인합니다. `run.sh`도 확인합니다.

### k6 인스턴스
```bash
sudo sysctl -w net.ipv4.ip_local_port_range="10240 65535"
ulimit -n 200000
VUS=1000 QUANTITY=500 BASE_URL=http://<앱 사설 IP>:8087 \
  MYSQL_HOST=<앱 사설 IP> MYSQL_USER=root MYSQL_PASSWORD=<비밀번호> ./loadtest/run.sh
```

### 실행할 때마다 기록할 것
- 앱 호스트
  - `nstat -az TcpExtListenOverflows TcpExtListenDrops`(실행 전후 차이)
  - `docker stats` 또는 `pidstat`으로 본 앱과 MySQL의 CPU
  - `mpstat`의 steal
  - `grep -c '/api/coupons/<쿠폰 id>/issue ' /tmp/promotion-loadtest/access*.log`(서버가 받은 요청 수)
- k6 호스트
  - k6의 CPU와 메모리
  - `ss -s`(TIME_WAIT 수)
- 실행 사이에 60초 이상 쉽니다. 이전 실행의 TIME_WAIT 연결이 포트를 점유하기 때문입니다.
- 앱을 띄우거나 재시작한 뒤에는 측정 전에 예열합니다. 별도 쿠폰으로 `VUS=1000 QUANTITY=500`을 4회 이상 돌리고, 결과에서 제외합니다. 기동 직후에는 JIT 컴파일러가 java CPU의 절반 이상을 써서, 같은 조건에서도 p99가 2~3배 높게 나왔습니다.
- 규모마다 3회씩 돌리고 원값을 모두 남깁니다.

## 결과 해석
- `issue_result{result:UNREACHED}`: 서버에 도달하지 못한 요청입니다. 연결 거부나 타임아웃이고, 앱 호스트의 ListenOverflows와 함께 봅니다.
- `issue_result{result:OTHER}`: 서버가 받았지만 204/409가 아닌 응답(5xx 등)입니다.
- `dispatch_offset`: 시나리오 시작부터 요청을 보낸 시점까지입니다. 최솟값과 최댓값의 차이가 크면 k6가 요청을 동시에 보내지 못한 것이니, 부하 생성기 용량부터 확인합니다.
- `request_total{result:...}`: 결과별 전체 시간입니다(연결 대기와 연결 수립 포함).
