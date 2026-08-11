# Chat App Backend (Spring Boot)

## 개요
현재 현대화 기준선은 Spring Boot 4.1.0과 Java 25인 실시간 채팅 백엔드입니다. MongoDB를 통한 영속화와 MongoDB TTL 기반 세션·레이트리밋, JWT 인증, OpenAI 연동을 제공하며 Socket.IO 호환 실시간 메시징을 지원합니다.

백엔드 현대화 목표는 Java 25 LTS, Spring Boot 4.1.0, Spring AI 2.0.x입니다. 이 문서는 Boot 4 전환 후의 실행·검증 기준을 기록합니다.

## 주요 기술 스택
- 현재 기준선: Java 25, Spring Boot 4.1.0 (Web, Validation, Security, OAuth2 Resource Server)
- AI: Spring AI 2.0.x
- MongoDB 8.3
- Netty Socket.IO 서버 (`com.corundumstudio:netty-socketio`)
- Spring Security + JWT, 커스텀 레이트 리미터
- Spring AI(OpenAI) 기반 대화형 응답 생성
- Testcontainers, JUnit 5, Reactor Test를 이용한 검증

## 프로젝트 구조
```text
src/main/java/com/ktb/chatapp
├── controller   # REST 엔드포인트
├── service      # 도메인 비즈니스 로직
├── repository   # MongoDB 접근 계층
├── websocket    # Socket.IO 서버/핸들러
├── security     # 인증/인가 설정
├── config       # 공통 설정(Async, RateLimit, Retry 등)
├── dto | model  # 요청/응답 DTO 및 엔티티
└── validation   # 커스텀 검증 로직

```

## 사전 준비물
- 현재 기준선 실행과 현대화 검증: Java 25 (JDK) - `make setup-java`로 설치/활성화 가능
- Docker & Docker Compose - `make dev`, `make test` 실행 시 필요
- make (선택 사항, 편의 명령 제공)

## Java 개발 환경 설정

### 현재 기준선과 현대화 목표

- 현재 `pom.xml` 기준선은 Java 25와 Spring Boot 4.1.0입니다.
- Stage 2 이후 현대화 검증은 Java 25 활성 셸에서 수행합니다.
- Maven Wrapper(`./mvnw`)가 백엔드의 표준 실행 경로입니다. 로컬 Maven 전역 설치에 의존하지 마세요.
- Java 25가 설치되어 있지 않은 환경에서는 `java -version`이 Java 21을 보여도 현대화 준비 완료로 간주하지 않습니다.

### Java 25 자동 설치 (현대화 경로)
```bash
# Java 25 자동 설치 또는 활성화 (SDKMAN 포함)
make setup-java

# 터미널 재시작 또는 설정 다시 로드
source ~/.bashrc   # bash
source ~/.zshrc    # zsh

# Java 25 활성화 확인
make verify-java
```

`make setup-java`는 Java가 설치되어 있다는 이유만으로 성공 처리하지 않습니다. 활성 Java가 25가 아니면 `JAVA_TARGET_SDKMAN` 후보를 설치하고 기본 JDK로 지정합니다.

기본값:

```bash
JAVA_TARGET_MAJOR=25
JAVA_TARGET_SDKMAN=25.0.3-librca
```

### Java 25 수동 설치
이미 Java 25가 설치되어 있거나 다른 방법으로 설치하려면:
- SDKMAN: `sdk install java 25.0.3-librca` 후 `sdk use java 25.0.3-librca`
- Homebrew(macOS): `brew install openjdk@25` 후 셸의 `JAVA_HOME`과 `PATH`를 해당 JDK로 지정
- Oracle JDK 25 또는 OpenJDK 25 설치 후 `JAVA_HOME`과 `PATH`를 해당 JDK로 지정

설치 후 다음 명령으로 확인하세요.

```bash
java -version
./mvnw --version
make verify-java
```

현재 셸에서 Java 21이 활성화되어 있으면 `make verify-java`는 실패해야 정상입니다. 현재 백엔드 기준선과 현대화 검증은 모두 Java 25를 사용합니다.

## 환경 변수 설정
애플리케이션은 `.env` 혹은 호스트 환경 변수에서 설정을 읽습니다. 필수 항목을 채운 뒤 서버를 시작하세요.

| 변수 | 필수 | 기본값 | 설명                          |
| --- | --- | --- |-----------------------------|
| `ENCRYPTION_KEY` | ✅ | 없음 | AES-256 암복호화를 위한 64자리 HEX 키 |
| `ENCRYPTION_SALT` | ✅ | 없음 | 암복호화에 사용하는 솔트 값             |
| `JWT_SECRET` | ✅ | 없음 | HMAC-SHA256 JWT 서명 비밀키      |
| `MONGO_URI` | ✅ | 없음 | MongoDB 연결 문자열              |
| `REDIS_HOST` | ✅ | 없음 | Redis 호스트                    |
| `REDIS_PORT` | ✅ | 없음 | Redis 포트                      |
| `REDIS_CLUSTER_NODES` | ❌ | 없음 | 쉼표로 구분한 Redis Cluster 노드 주소. 설정 시 단일 Redis보다 우선 적용 |
| `PORT` | ❌ | `5001` | HTTP API 포트 (`server.port`) |
| `WS_PORT` | ❌ | `5002` | Socket.IO 서버 포트             |
| `CORS_ALLOWED_ORIGINS` | ❌ | `*` | REST API CORS 허용 Origin 목록. 쉼표로 구분 |
| `SOCKETIO_SERVER_ORIGIN` | ❌ | `*` | Socket.IO 허용 Origin. Netty Socket.IO 설정은 단일 Origin 값을 사용 |
| `MANAGEMENT_HEALTH_SHOW_DETAILS` | ❌ | `when_authorized` | Actuator health 상세 노출 수준 |
| `SWAGGER_ENABLED` | ❌ | `true` | Swagger UI와 OpenAPI JSON 노출 여부 |
| `OPENAI_API_KEY` | ❌ | `your_openai_api_key_here` | OpenAI 호출용 API Key          |

`.env.template` 파일을 복사해 기본 값을 채운 뒤 필요에 따라 수정하세요.

예시:
```bash
cp .env .env.backup 2>/dev/null || true
cp .env.template .env
# 필요에 맞게 값 갱신 (예: OpenSSL로 키 생성)
sed -i '' "s/change_me_64_hex_chars____________________________________/$(openssl rand -hex 32)/" .env
sed -i '' "s/change_me_32_hex_chars________________/$(openssl rand -hex 16)/" .env
```

## 애플리케이션 실행
가장 간편한 방법은 Maven Wrapper와 Makefile을 사용하는 것입니다.

```bash
make dev      # dev 프로파일로 로컬 서버 실행 (Testcontainers 지원)
make build    # 패키지 및 테스트 수행
make test     # 단위/통합 테스트 실행
```

직접 Maven 명령을 사용할 수도 있습니다.
```bash
./mvnw clean package          # 전체 빌드 및 테스트
./mvnw compile spring-boot:test-run -Dspring-boot.run.profiles=dev        # 애플리케이션 실행
java -jar target/ktb-chat-backend-0.0.1-SNAPSHOT.jar
```
기본 포트는 HTTP `5001`, Socket.IO `5002`입니다.

## Docker 이미지

백엔드 Dockerfile은 Java 25 런타임을 사용합니다.

```bash
docker build -t ktb-chat-backend:java25 .
docker image inspect ktb-chat-backend:java25 --format '{{json .Config.ExposedPorts}}'
```

이미지는 HTTP API `5001/tcp`와 Socket.IO `5002/tcp`를 노출합니다.

## API 문서
애플리케이션 실행 후 다음 URL에서 API 문서를 확인할 수 있습니다:

- **REST API**: [http://localhost:5001/api/swagger-ui.html](http://localhost:5001/api/swagger-ui.html)
- **Socket.IO API**: [http://localhost:5001/api/docs/socketio/index.html](http://localhost:5001/api/docs/socketio/index.html)

## IntelliJ IDEA에서 실행

프로젝트에는 사전 구성된 IntelliJ IDEA 실행 구성이 포함되어 있습니다.

### 실행 구성 사용

1. IntelliJ IDEA에서 프로젝트를 엽니다
2. 상단 툴바에서 **ChatAppApplication** 실행 구성을 선택합니다
3. 실행(▶️) 또는 디버그(🐛) 버튼을 클릭합니다

### 실행 구성 세부 사항

`.run/ChatAppApplication.run.xml` 파일이 다음 설정을 제공합니다:

- **Active Profile**: `dev` - 개발 환경 프로파일 사용
- **Update Policy**: `UpdateClassesAndResources` - 코드 변경 시 자동 재로드
- **Working Directory**: `apps/backend` - 프로젝트 루트 디렉토리
- **Main Class**: `com.ktb.chatapp.ChatAppApplication`

### DevTools 자동 재시작

Spring Boot DevTools가 활성화되어 있어 다음 변경사항을 자동으로 감지합니다:

- Java 소스 코드 변경 (`src/main/java`)
- 리소스 파일 변경 (`src/main/resources`)
- 정적 파일은 제외 (`static/**`, `public/**`)

코드 변경 후 빌드(Ctrl+F9 / Cmd+F9)만 실행하면 애플리케이션이 자동으로 재시작됩니다.

### 실행 전 확인사항

1. `.env` 파일에 필수 환경 변수가 설정되어 있는지 확인
2. MongoDB와 Redis가 실행 중인지 확인 (또는 Testcontainers 사용)
3. Java 25가 프로젝트 SDK로 설정되어 있는지 확인


## 테스트
```bash
./mvnw test
```
테스트는 JUnit 5와 Testcontainers를 사용하며, Docker가 필요할 수 있습니다. 로컬에서 서비스가 실행 중이면 Testcontainers는 자동으로 재사용합니다.

## 종속 서비스 실행
`make dev` 실행시 spring-boot-docker-compose 의해 자동으로 구동됩니다. 아래는 별도로 구동할 경우의 예시 입니다.
```bash
docker compose up -d
```
MongoDB와 Redis가 이미 실행 중이라면 이 단계를 건너뛸 수 있습니다.

## 모니터링 UI 접속
`docker compose up -d`(또는 `make dev`)로 종속 서비스를 구동하면 모니터링 스택도 함께 올라옵니다. 아래 UI에서 접속할 수 있습니다(dev 기준):

- **Grafana** (대시보드): [http://localhost:9091](http://localhost:9091) — 기본 로그인 `admin` / `admin` (환경 변수 `GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD`로 변경)
- **Prometheus** (메트릭 쿼리 · 타깃 상태): [http://localhost:9090](http://localhost:9090)

> prod 구성(`docker-compose.o11y.yaml`)에서는 Grafana가 `3000` 포트로 노출됩니다.

Prometheus가 수집하는 메트릭 원본 엔드포인트:

- **애플리케이션**: [http://localhost:5001/actuator/prometheus](http://localhost:5001/actuator/prometheus)
- **mongodb-exporter**: [http://localhost:9216/metrics](http://localhost:9216/metrics)
- **redis-exporter**: [http://localhost:9121/metrics](http://localhost:9121/metrics)

## 트러블슈팅
- `.env`의 필수 키가 누락되면 애플리케이션이 부팅 중 예외를 발생시킵니다.
- MongoDB/Redis 연결 오류 시 `docker compose ps`로 컨테이너 상태를 확인하거나 `application.properties`의 기본값을 검토하세요.
- OpenAI 통합을 사용하지 않을 경우 `OPENAI_API_KEY`를 제거하면 관련 기능은 비활성화됩니다.
