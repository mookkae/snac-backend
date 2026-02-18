# AWS 분산 부하 테스트 환경 구성 가이드

## EC2 인스턴스 구성

| 역할 | 인스턴스 타입 | 서비스 | 보안 그룹 인바운드 |
|------|------------|--------|-------------------|
| **MySQL** | t3.medium | MySQL 8.0 | 3306 (App EC2) |
| **RabbitMQ + Redis** | t3.medium | RabbitMQ, Redis | 5672, 15672, 61613, 6379 (App EC2) |
| **Pinpoint** | t3.large | HBase, Collector, Web | 9991-9995 (App EC2), 28080 (내 IP) |
| **App (SUT)** | t3.medium | Spring Boot + Pinpoint Agent | 8080 (nGrinder EC2, Monitoring EC2) |
| **Monitoring** | t3.small | Prometheus, Grafana | 9090, 3000 (내 IP) |
| **nGrinder** | t3.medium | Controller, Agent | 9000 (내 IP) |

> 모든 EC2는 같은 VPC / Subnet에 배치. Private IP로 통신.

---

## 사전 준비

### 1. EC2 생성 시 User Data 설정

각 EC2 생성 시 `ec2-user-data.sh` 내용을 User Data에 붙여넣기.
Docker + Docker Compose가 자동 설치됨.

### 2. 소스 코드 클론

각 EC2에 SSH 접속 후:
```bash
git clone https://github.com/mookkae/snac-backend.git
cd snac-backend
```

### 3. 환경변수 설정 (App EC2에서만)

```bash
cp aws/env.example aws/.env
```

`.env` 파일에서 placeholder를 실제 EC2 Private IP로 치환:
```bash
sed -i 's/{MYSQL_IP}/10.0.1.10/g' aws/.env
sed -i 's/{REDIS_RABBITMQ_IP}/10.0.1.11/g' aws/.env
```

### 4. Pinpoint IP 설정 (App EC2에서만)

`aws/app/docker-compose.yml`에서 `{PINPOINT_IP}`를 Pinpoint EC2의 Private IP로 치환:
```bash
sed -i 's/{PINPOINT_IP}/10.0.1.12/g' aws/app/docker-compose.yml
```

### 5. Prometheus target 설정 (Monitoring EC2에서만)

`aws/monitoring/prometheus.yml`에서 `{APP_IP}`를 App EC2의 Private IP로 치환:
```bash
sed -i 's/{APP_IP}/10.0.1.13/g' aws/monitoring/prometheus.yml
```

---

## 서비스 기동 순서

**반드시 아래 순서대로 기동할 것.**

### Step 1: MySQL EC2

```bash
cd snac-backend
docker compose -f aws/mysql/docker-compose.yml up -d
```

확인:
```bash
docker exec snac-mysql mysqladmin ping -uroot -proot
```

### Step 2: RabbitMQ + Redis EC2

```bash
cd snac-backend
docker compose -f aws/rabbitmq-redis/docker-compose.yml up -d
```

확인:
```bash
# Redis
docker exec snac-redis redis-cli ping
# RabbitMQ (Management UI)
curl -s http://localhost:15672 | head -1
```

### Step 3: Pinpoint EC2

```bash
cd snac-backend
docker compose -f aws/pinpoint/docker-compose.yml up -d
```

확인 (HBase 초기화에 1~2분 소요):
```bash
curl -s http://localhost:28080 | head -1
```

### Step 4: App EC2 — 1차 기동 (테이블 생성)

App JAR 빌드 후 복사 (로컬에서):
```bash
./gradlew build -x test
scp build/libs/snac-*.jar ec2-user@{APP_EC2_IP}:~/snac-backend/aws/app/app.jar
```

App EC2에서:
```bash
cd snac-backend
docker compose -f aws/app/docker-compose.yml up -d
```

확인 (Hibernate ddl-auto가 테이블 생성 완료할 때까지 대기):
```bash
curl http://localhost:8080/actuator/health
```

### Step 5: 더미 데이터 삽입 (MySQL EC2)

App이 정상 기동되어 테이블이 생성된 후 실행.

MySQL EC2에서:
```bash
cd snac-backend
docker exec -i snac-mysql mysql -uroot -proot snac_db < aws/mysql/init-data.sql
```

약 131만 건 삽입, 1~2분 소요. 완료 확인:
```bash
docker exec snac-mysql mysql -uroot -proot snac_db -e "SELECT COUNT(*) FROM asset_history;"
```

### Step 6: Monitoring EC2

```bash
cd snac-backend
docker compose -f aws/monitoring/docker-compose.yml up -d
```

확인:
- Prometheus: `http://{MONITORING_EC2_PUBLIC_IP}:9090/targets` → snac-backend UP
- Grafana: `http://{MONITORING_EC2_PUBLIC_IP}:3000` (admin/admin)

### Step 7: nGrinder EC2

```bash
cd snac-backend
docker compose -f aws/ngrinder/docker-compose.yml up -d --build
```

확인 (Controller 초기화에 1~2분 소요):
- nGrinder Web: `http://{NGRINDER_EC2_PUBLIC_IP}:9000` (admin/admin)
- Agent Management에서 agent 1대 연결 확인

---

## 연결 확인 체크리스트

| From | To | 명령어 | 기대 결과 |
|------|----|--------|-----------|
| App EC2 | MySQL EC2 | `curl -s telnet://{MYSQL_IP}:3306` | 연결 성공 |
| App EC2 | RabbitMQ EC2 | `curl -s telnet://{RABBITMQ_IP}:5672` | 연결 성공 |
| App EC2 | Redis EC2 | `curl -s telnet://{REDIS_IP}:6379` | 연결 성공 |
| App EC2 | Pinpoint EC2 | `curl -s telnet://{PINPOINT_IP}:9994` | 연결 성공 |
| Monitoring EC2 | App EC2 | `curl http://{APP_IP}:8080/actuator/prometheus` | Metrics 출력 |
| nGrinder EC2 | App EC2 | `curl http://{APP_IP}:8080/actuator/health` | `{"status":"UP"}` |

---

## 부하 테스트 실행

nGrinder Web UI (`http://{NGRINDER_EC2_PUBLIC_IP}:9000`)에서 Groovy 스크립트 업로드 후 실행.

### Round 1: Baseline
- VUser: 10
- Duration: 3분
- 스크립트: `IdempotencyTest.groovy`

### Round 2: 단계적 부하 증가
- VUser: 50 → 100 → 200
- Duration: 각 5분
- 스크립트: `MixedScenarioTest.groovy`

### Round 3: 장애 주입
- VUser: 100
- Duration: 5분
- 스크립트: `RabbitMqFaultTest.groovy`, `CompensationTest.groovy`

### Round 4: 최대 부하
- VUser: 500
- Duration: 10분
- 스크립트: `MixedScenarioTest.groovy`

---

## 테스트 후 정리

모든 EC2에서 Terminate하여 비용 방지:
```bash
# AWS CLI (로컬에서)
aws ec2 terminate-instances --instance-ids i-xxx i-yyy i-zzz ...
```

또는 AWS Console에서 각 인스턴스 선택 → Terminate.
