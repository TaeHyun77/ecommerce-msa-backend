### 프로젝트 설명 
---
이커머스 도메인을 멀티 모듈 기반의 MSA 구조로 설계하고 구현한 프로젝트입니다. 

회원, 상품, 주문, 결제, 검색 등의 서비스를 분리하고, 서비스 간 호출과 메시징 과정에서 발생할 수 있는 재고, 결제 정합성 및 검색 데이터 동기화 문제를 해결하는 데 중점을 두었습니다.<br><br>

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
<img width="2000" height="1079" alt="image" src="https://github.com/user-attachments/assets/0149a2d2-fd7a-4c58-95c1-b643a12b40e9" /><br><br>

**결제 과정**

[ 쿠폰 적용 시 ]

주문 요청 → 쿠폰 선점 → 재고 선점 → 주문 생성 → 결제 인증 → 재고 선점 확정 → 쿠폰 사용 확정 → PG 승인 → 결제 완료

[ 쿠폰 미적용 시 ]

주문 요청 → 재고 선점 → 주문 생성 → 결제 인증 → 재고 선점 확정 → PG 승인 → 결제 완료<br><br>
