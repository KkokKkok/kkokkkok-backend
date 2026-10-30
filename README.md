# 꼭꼭 Spring Boot 백엔드

Java 17 / Spring Boot 4.1.2 / Gradle Wrapper 기반 통합 검사 API 서버입니다.

앱 → Spring Boot(입력 검증·검사 작업 저장·접근 키·오류 처리) → 분석 드라이버 흐름입니다. 기본 `stub` 드라이버는 고정된 합성 결과를 돌려줘서 Node 서버 없이 화면부터 HTTP 응답까지 확인할 수 있습니다. 실제 사진을 분석하지 않으며 모든 응답에 `mock: true`를 표시합니다. 파이프라인이 준비되면 `NodeAnalysisClient` 등 실제 드라이버로 설정을 바꿉니다.

## 실행 (PowerShell)

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'
$env:APP_API_KEY = '개발용-비밀키로-교체'
$env:ANALYSIS_PROVIDER = 'stub'
$env:SERVER_ADDRESS = '127.0.0.1'
.\gradlew.bat bootRun
```

기본 포트는 8080, 바인딩은 127.0.0.1입니다. `.env.example`은 참고용이며 `.env`를 자동 로드하지 않습니다. 키는 저장소에 넣지 않습니다. API 호출은 `X-App-Api-Key`에 위에서 지정한 개발 키를 넣습니다.

Android 에뮬레이터와 로컬에서 연결할 때는 앱에 API 키를 넣지 않도록 `local` 프로필을 사용할 수 있습니다. 이 프로필은 API 키 검사를 끄며, 서버는 loopback 주소에만 바인딩한 상태로 실행합니다.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:ANALYSIS_PROVIDER = 'stub'
$env:SERVER_ADDRESS = '127.0.0.1'
.\gradlew.bat bootRun
```

Android 에뮬레이터에서 사용할 서버 주소는 `http://10.0.2.2:8080`입니다. `local` 프로필은 로컬 개발용으로만 사용하고 원격 또는 운영 서버에 적용하지 않습니다.

Node 분석 서버를 명시적으로 연결할 때만 다음 설정을 사용합니다. Node API는 확정된 꼭꼭 파이프라인 계약과 맞춰야 합니다.

```powershell
$env:ANALYSIS_PROVIDER = 'node'
$env:NODE_ANALYSIS_BASE_URL = 'http://127.0.0.1:3000'
$env:NODE_ANALYSIS_API_KEY = 'Node와-합의한-비밀키'
```

- `POST /api/inspections`: 갤러리에 보관된 사진의 `assetId`를 받아 단독 분석 시작
- `GET /api/inspections/{id}`: 진행 상태·bbox·위험 요소·반복 노출 결과 조회
- `GET /api/inspections?assetId={id}`: 사진 한 장의 단독 분석 이력 조회
- `POST /api/inspection-batches`, `GET /api/inspection-batches/{batchId}`: 여러 사진을 각각 독립적으로 분석하고 묶음·사진별 상태 조회
- `POST /api/edits`, `GET /api/edits/{editId}`: 분석 단서 선택과 모의 보호 편집 결과 조회
- `GET /actuator/health`: Spring 서버 상태 (Node 가용성을 뜻하지 않음)

분석 시작 요청은 `{"assetId":"..."}` JSON과 `Idempotency-Key` 헤더를 사용합니다. 기본 stub은 첫 응답에서 `queued`, 조회 시 `completed`와 합성 단서를 반환합니다. 이 결과는 실제 사진 분석이 아니며 `mock: true`로 표시합니다.

다량 분석은 최대 20장의 기존 자산을 묶어 단독 분석을 각각 시작합니다. 사진별 EXIF GPS가 없을 때만 각 항목의 `locationFallback`을 적용합니다. 사진 간 비교나 누적 점수 계산은 하지 않으며, 분석 항목 ID와 부분 실패를 묶음 응답에서 따로 확인합니다. API JSON은 [검사 API 문서](docs/inspection-api.md#다량-단독-분석)와 [OpenAPI 초안](docs/openapi.yaml)에 있습니다.

보호 편집은 현재 `EDITING_PROVIDER=stub`만 지원합니다. 선택한 단서와 보존 요청을 편집 드라이버 경계로 전달하지만, stub은 이미지 내용을 바꾸지 않고 원본의 복사본을 별도 수정 자산으로 저장합니다. 이 결과도 `mock: true`로 표시하며 실제 보호 편집 기능이나 GPT 연결로 간주하지 않습니다.

[요청·응답·오류·Node 구현 요구사항](docs/inspection-api.md)을 참고하세요.
[프론트 연동용 OpenAPI 초안](docs/openapi.yaml)도 함께 제공합니다. 현재 코드 범위의 초안이며 최종 운영 계약은 아닙니다.

## 빌드 및 검증

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'
.\gradlew.bat build --no-daemon
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar
```

테스트는 모의 HTTP Node 서버와 고정 stub 결과를 사용합니다. 실제 OpenAI나 AWS 분석을 호출하지 않습니다. `stub` 모드는 HTTP Node 서버에 연결하지 않고 작업 결과를 메모리에 보관하므로 서버 재시작 후에는 모의 작업을 조회할 수 없습니다. `node` 모드는 서버 주소 미설정 시 503, 연결 실패 시 504로 응답합니다.

현재 공유 앱 키는 단일 사용자 개발 환경의 접근 제어입니다. 계정·사용자별 권한 격리·장소 관리·군집화·점수 규칙·보호 편집은 포함되지 않습니다. 분석 작업 메타데이터는 Spring의 JPA 저장소에서 관리합니다. 다중 사용자 앱에는 로그인 및 사용자별 접근권한을 먼저 추가해야 합니다. 브라우저의 교차 출처 CORS는 아직 열지 않았으며 실제 프론트 주소 확정 후 허용 목록을 설정합니다.

이미지 자산 API는 `POST /api/assets` 업로드, `GET /api/assets` 목록, `GET /api/assets/{assetId}` 상세, `GET /api/assets/{assetId}/content` 이미지 읽기, `PUT /api/assets/{assetId}/labels` 라벨 변경, `DELETE /api/assets/{assetId}` 삭제를 제공합니다. 파일은 `ASSET_STORAGE_ROOT`(기본 `./private-data/assets`) 아래 로컬에 저장하고 메타데이터·라벨은 H2에 저장합니다. JPEG/PNG/WebP만 허용하며 최대 10MB입니다. EXIF 촬영 시각과 GPS를 추출하지만 응답에는 원시 GPS를 노출하지 않습니다. EXIF 누락·손상은 업로드 실패 사유가 아닙니다.

이 저장소는 개발용 단일 사용자 흐름입니다. 로그인·사용자별 권한 격리·클라우드 파일 저장·자산과 분석/편집 이력의 삭제 규칙은 아직 포함하지 않으며, 공개 서비스에 배포할 준비가 된 저장소가 아닙니다.

## 협업

기존 feat/defaut-frame 브랜치를 사용합니다. Conventional Commits 및 GitHub Flow를 따르며 작업 원칙은 [AGENTS.md](AGENTS.md)를 참고하세요.

## 계층 구조

```text
com.kkokkkok.backend
├── apiPayload       공통 오류 응답 / exception 예외 처리
├── config           Node HTTP 클라이언트 설정, 개발용 접근 키 필터
├── client           AnalysisClient 인터페이스, Node HTTP 구현
├── converter        Node 응답 → API 응답 DTO 변환
├── domain           Inspection / ImageAsset JPA 엔티티
├── repository       검사·이미지 자산 Spring Data JPA 저장소
├── service          검사 흐름, 이미지 저장소·EXIF 추출, DB 업무 흐름
├── validation       사진·장소 입력 검증과 요청 해시 계산
├── web
│   ├── controller   HTTP 입력/출력
│   └── dto          요청·응답 타입
└── BackendApplication
```

Controller → Service → Validator / Client / Converter / Store → Repository 흐름입니다.
Service는 외부 HTTP 호출 중 DB 트랜잭션을 유지하지 않습니다. Node 통신 구현을 바꾸더라도 Controller의 API는 유지할 수 있습니다. 기존 JSON을 감싸는 새 성공 envelope는 추가하지 않았습니다.

## 로컬 DB

기본 DB는 파일 H2(`private-data/backend`)입니다. 최초 실행 시 생성되고 재시작 후에도 검사 요청 메타데이터가 남습니다. 테스트는 메모리 H2를 사용합니다.

`inspections` 테이블: request_id(PK, Idempotency-Key), asset_id, fingerprint(SHA-256), inspection_id(Node ID, UNIQUE), status, created_at, updated_at, version(낙관적 잠금).

원본 사진은 이미지 자산 저장소에 있으며, 분석 작업은 `asset_id`로 연결됩니다. 전체 분석 결과는 이 테이블에 저장하지 않습니다. 요청 해시에는 자산 ID와 파일 내용이 반영됩니다. Node가 실제 결과를 보관하며, 조회 시 Spring은 상태를 갱신합니다. 이전 버전에서 생성한 Node 작업도 GET으로 조회할 수 있지만 Spring에 요청 기록이 없는 작업은 새 DB 기록을 자동 생성하지 않습니다.

동일 요청 키+다른 입력은 409입니다. 동일 키+같은 입력은 Node에 동일 키를 다시 보내므로 Node의 중복 실행 방지도 필요합니다. 통신 실패 시 `submitting` 상태가 남아 같은 키로 재시도할 수 있습니다. 이 상태는 Node 분석 실패 판정이 아닙니다.

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로 DB 연결을 설정할 수 있습니다. 현재 JDBC 드라이버는 H2만 포함합니다. 운영 DB 선정 후 해당 드라이버와 Flyway 같은 버전별 마이그레이션을 추가하고, 개발용 `ddl-auto=update`를 `validate`로 전환해야 합니다.
