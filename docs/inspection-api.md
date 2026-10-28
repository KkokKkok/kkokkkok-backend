# 통합 검사 API 계약 v1

현재 Spring Boot는 검사 요청을 관리하고 Node 분석 서비스를 호출하는 계층형 서버다. 기존 PoC의 `/api/analysis-jobs`와 자동 호환되지 않는다. Node에 아래 계약을 구현해야 한다. 검사 작업 메타데이터는 JPA/H2에 저장한다. 실제 사진 분석·누적 점수·사용자 계정 인증은 포함되지 않는다.

## 공통

- Spring 요청: `X-App-Api-Key`. 단일 사용자 개발 환경용 공유 키이며 사용자 계정 인증이 아니다. 여러 사용자의 데이터는 이 구조로 격리되지 않으므로 다중 사용자 서비스 전 인증/소유권 검증을 구현한다.
- Spring → Node: 설정된 `X-Analysis-Api-Key`를 전달한다. Node는 이를 검증해야 한다.
- 응답: JSON, `Cache-Control: no-store`. 사진·주소·응답·비밀키는 로그에 남기지 않는다.
- ID는 UUID. 분석 이미지 좌표는 `analysis-normalized`, bbox는 `[x1,y1,x2,y2]`, 각 값 0~1. GPS는 bbox null.

## POST /api/inspections

`multipart/form-data`, 필수 헤더 `Idempotency-Key: <UUID>`.

| 필드 | 규칙 |
|---|---|
| photo | 필수, JPEG/PNG/WebP, 최대 10MB. Spring은 파일 시그니처 검사, Node는 실제 디코딩·픽셀 제한·전처리 수행 |
| address | 선택, 최대 500자. placeId와 동시 입력 불가 |
| placeId | 선택, 기존 장소 UUID. Node에서 존재·접근권한 검증 |
| locationRole | capture(촬영 장소, 기본값), sensitive(보호 장소) |
| compareHistory | boolean, 기본 false. true이면 Node가 해당 사용자의 저장된 게시 이력을 비교 |

주소가 없는 사진도 검사할 수 있다. Node에 사용자 이력이 없으면 반복 비교를 `unavailable`로 반환하며 '노출 없음'으로 꾸미지 않는다.

응답 HTTP 202, Location `/api/inspections/{inspectionId}`:

```json
{"inspectionId":"11111111-1111-4111-8111-111111111111","status":"queued"}
```

Node는 요청을 저장·큐 등록한 뒤 즉시 응답한다. 긴 분석 완료까지 POST 연결을 유지하지 않는다. Node에서 동일 키+동일 입력은 동일 작업을 반환하고, 동일 키+다른 입력은 409를 반환해야 한다. Spring은 키와 정규화된 입력 해시를 DB에 저장해 다른 입력의 키 재사용을 409로 거절한다. 같은 입력의 재시도는 같은 키를 Node에 전달한다. 자동 재시도는 없다. 동일 키가 동시에 들어와도 DB의 PK 제약으로 하나의 요청 기록만 저장하며 Node에서도 해당 키를 중복 처리해야 한다. 타임아웃 후 앱은 같은 키와 같은 입력으로 재시도한다.

## GET /api/inspections/{inspectionId}

HTTP 200. 상태는 queued/processing/completed/failed. Spring은 ID·상태를 검증하고 나머지 Node 응답을 전달한다. 심층 결과 스키마 검증은 후속 작업이다.

```json
{
  "inspectionId":"11111111-1111-4111-8111-111111111111",
  "status":"completed",
  "image":{"url":"https://example.invalid/images/analysis.jpg","width":1536,"height":2048,"coordinateSpace":"analysis-normalized"},
  "riskSummary":{"score":null,"level":"pending","reason":"평가 규칙 미확정"},
  "riskFactors":[{"id":"factor-1","type":"sign_text","title":"창밖 간판","description":"문자열 확인","text":"예시 상호","bbox":[0.1,0.2,0.4,0.3],"sources":["ocr","gpt"],"reviewStatus":"unreviewed"}],
  "repeatedExposure":{"status":"unavailable","message":"게시 이력 없음","matchedImageIds":[],"samePlaceConfirmed":false}
}
```

이미지 URL은 프론트에서 접근 가능한 URL이어야 한다. Node 내부의 상대경로를 그대로 보내면 Spring 경로로 오인될 수 있다. 만료되는 접근 URL 또는 별도 이미지 게이트웨이는 Node 저장 구조 확정 후 구현한다.

## 오류

```json
{"error":{"code":"INVALID_REQUEST","message":"필수 입력과 UUID, boolean 형식을 확인해 주세요."}}
```

400 입력 오류 / 401 앱 키 오류 / 404 작업 없음 / 409 중복 키 충돌 / 413 용량 초과 / 415 형식 오류 / 429 요청 제한 / 502 Node 오류·잘못된 응답 / 503 서버 설정 누락 / 504 연결 실패·시간 초과.
Node 오류 본문은 개인정보 유출을 막기 위해 전달하지 않는다.

## 호출 예시 (PowerShell)

```powershell
$inspectionKey = [guid]::NewGuid().ToString()
curl.exe -X POST http://localhost:8080/api/inspections -H "X-App-Api-Key: $env:APP_API_KEY" -H "Idempotency-Key: $inspectionKey" -F "photo=@C:/samples/synthetic.jpg" -F "address=합성 테스트 주소" -F "locationRole=capture" -F "compareHistory=false"
curl.exe http://localhost:8080/api/inspections/11111111-1111-4111-8111-111111111111 -H "X-App-Api-Key: $env:APP_API_KEY"
```

## Spring 저장 책임

- POST 전에 요청 키와 입력 해시를 저장한다. Node 응답 검증 후 Node ID·상태를 연결한다.
- GET으로 Node 결과를 읽을 때 로컬 기록이 있으면 상태를 갱신한다. Node 연결 실패로 기존 완료 상태를 지우지 않는다.
- DB 상태 `submitting`은 Node 응답 미확정 상태다. API 응답 상태는 기존 queued/processing/completed/failed를 유지한다.
- API 응답 DTO의 inspectionId/status는 명시적 타입이며, 나머지 분석 상세 필드는 기존 JSON 키를 유지한다. 결과 내부 bbox·점수 스키마의 강제 검증은 아직 하지 않는다.
- 낙관적 잠금 충돌은 409 CONCURRENT_UPDATE이며 동일 키로 재시도한다.
- 서버 키는 단일 사용자 파일럿용이다. 현재 사용자 ID가 없으므로 compareHistory는 사용자별 이력이 구현된 기능을 의미하지 않는다.
