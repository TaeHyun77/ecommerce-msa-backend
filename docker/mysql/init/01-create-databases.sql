-- 서비스마다 DB를 분리 - 테이블은 각 서비스가 ddl-auto=update로 만든다
CREATE DATABASE IF NOT EXISTS ecommerce_product;
CREATE DATABASE IF NOT EXISTS ecommerce_member;
CREATE DATABASE IF NOT EXISTS ecommerce_order;
CREATE DATABASE IF NOT EXISTS ecommerce_payment;
CREATE DATABASE IF NOT EXISTS ecommerce_promotion;
