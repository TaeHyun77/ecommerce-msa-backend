### 프로젝트 설명 
---
이커머스 도메인을 멀티 모듈 기반의 MSA 구조로 설계하고 구현한 프로젝트입니다. 

회원, 상품, 주문, 결제, 검색 등의 서비스를 분리하고, 서비스 간 호출과 메시징 과정에서 발생할 수 있는 재고, 결제 정합성 및 검색 데이터 동기화 문제를 해결하는 데 중점을 두었습니다.<br><br>

개발 과정 기술 블로그 : <a href="https://velog.io/@ayeah77/series/E-commerce-MSA-%ED%94%84%EB%A1%9C%EC%A0%9D%ED%8A%B8-%EA%B0%9C%EB%B0%9C%EA%B8%B0" target="_blank">E-commerce MSA 프로젝트 개발기</a><br><br>

### 기술 스택 
---
Backend : Java 17, Spring Boot, Spring MVC, Spring Data JPA, Spring Security, Spring Cloud Gateway

Database / Cache : MySQL, Redis, Elasticsearch

Messaging : Kafka, Outbox Pattern

Infrastructure : Docker, Docker Compose

Etc : Toss Payments API

Test : JUnit 5, Testcontainers<br><br>

### 아키텍처
---
<img width="2216" height="1320" alt="image" src="https://github.com/user-attachments/assets/6f3ea9ea-633c-4d8d-be20-963bfda8289a" /><br><br>

**결제 과정**

<img width="1838" height="1296" alt="image" src="https://github.com/user-attachments/assets/ac92ddad-ec76-4f5f-95ae-3e35353450ce" />


[ 요약 ]

주문 요청 → (쿠폰 선점) & 재고 선점 & 주문 생성 → 결제 인증 → 재고 선점 확정 & (쿠폰 사용 확정) & PG 승인 → 결제 완료<br><br>

[ 주문 준비 ]

1. 장바구니 또는 상품 상세 페이지에서 주문하기를 누르면 주문 페이지로 이동합니다.
2. 주문 페이지에서 장바구니 상품과 보유 쿠폰을 조회하고, 쿠폰 선택 시 예상 할인 금액을 표시합니다.

[ 주문 생성 ]

3. 결제하기를 누르면 주문 생성 API를 호출합니다.
4. 서버는 할인 금액을 계산하고 재고와 쿠폰을 선점한 뒤 주문을 생성합니다. 이때 결제 기한(10분)이 시작됩니다.

[ 결제 인증 ]

5. 주문 생성에 성공하면 토스페이먼츠 SDK를 통해 결제창을 엽니다.
6. 사용자가 결제수단을 선택하고 인증을 진행합니다. 인증 성공 시 `successUrl`, 실패 시 `failUrl`로 이동합니다.

[ 결제 승인 ]

7. 인증 성공 후 `successUrl`에서 결제 승인 API를 호출합니다.
8. `order-service`가 `payment-service`에 승인을 요청하고, `payment-service`가 토스페이먼츠 승인 API를 호출합니다.

[ 결제 완료 ]

9. PG 승인 결과에 따라 주문 상태를 갱신하고 결제 결과를 화면에 표시합니다.
