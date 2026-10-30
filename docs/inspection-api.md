# 검사 API·분석 드라이버 개발 계약

## 이미지 자산·개발용 갤러리

검사 흐름과 별도로 `POST /api/assets`가 업로드된 사진을 개발용 갤러리에 보관합니다. multipart 필드 `photo`는 필수이며 JPEG/PNG/WebP, 최대 10MB입니다. `labels`는 선택 반복 필드로 최대 10개, 각 40자까지 지정할 수 있습니다. 파일은 기본 `./private-data/assets`에 저장되고, H2에는 UUID·MIME 형식·크기·EXIF에서 추출한 선택 메타데이터·라벨이 기록됩니다. `ASSET_STORAGE_ROOT`로 파일 경로를 바꿀 수 있습니다.

예시 응답:

```json
{
  "assetId":"11111111-1111-4111-8111-111111111111",
  "contentType":"image/jpeg",
  "sizeBytes":4,
  "locationStatus":"unavailable",
  "capturedAt":null,
  "labels":["집"],
  "assetRole":"original",
  "parentAssetId":null,
  "mock":false,
  "createdAt":"2026-10-30T08:00:00Z"
}
```

`GET /api/assets`는 목록, `GET /api/assets/{assetId}`는 상세, `GET /api/assets/{assetId}/content`는 비공개 이미지 바이트를 돌려줍니다. 라벨은 `PUT /api/assets/{assetId}/labels`에 `{"labels":["집"]}`로 교체하며, 자산 삭제는 `DELETE /api/assets/{assetId}`입니다. 수정 자산은 `assetRole=edited`, `parentAssetId`에 원본 자산 ID를 표시합니다. EXIF가 없거나 읽을 수 없는 경우에도 업로드는 성공하고 `locationStatus`는 `unavailable`이 됩니다. GPS 좌표와 파일 저장 키는 일반 응답에 포함하지 않습니다.

현재 구현은 로컬 개발용 저장 방식이며 사용자 계정·소유권 분리와 클라우드 저장소가 없습니다. 그러므로 이 계약을 다중 사용자 프로덕션 API로 간주하지 않습니다. 자산 삭제 뒤 분석 기록을 처리하는 규칙은 후속 설계 대상입니다.

앱은 먼저 `/api/assets`로 사진을 저장하고 `POST /api/inspections`에 `assetId`를 전달한다. Spring은 파일을 불러와 선택한 분석 드라이버에 넘기며 작업 메타데이터와 `assetId`를 JPA/H2에 저장한다. 기존 PoC의 `/api/analysis-jobs`와 자동 호환되지 않는다. 실제 사진 분석·누적 점수·사용자 계정 인증은 포함되지 않는다.

## 개발용 stub 드라이버

로컬에서 요청·응답 흐름을 확인할 때는 `ANALYSIS_PROVIDER=stub`을 사용한다(기본값). 이 드라이버는 사진 바이트를 분석하지 않고 고정된 합성 단서 하나와 예시 위험 점수를 반환하며, 응답에 `mock: true`, `pipelineVersion: "stub-v1"`, 모의 결과 안내를 넣는다. 실제 사진·모델·정확도는 검증하지 않는다.

실제 분석 서버 어댑터는 `ANALYSIS_PROVIDER=node`를 지정하고 `NODE_ANALYSIS_BASE_URL`, 필요시 `NODE_ANALYSIS_API_KEY`를 설정해야 한다. 앱과 API 경로는 분석기 선택에 의존하지 않도록 `AnalysisClient` 인터페이스를 경계로 둔다.

## 공통

- Spring 요청: `X-App-Api-Key`. 단일 사용자 개발 환경용 공유 키이며 사용자 계정 인증이 아니다. 여러 사용자의 데이터는 이 구조로 격리되지 않으므로 다중 사용자 서비스 전 인증/소유권 검증을 구현한다.
- Spring → Node: 설정된 `X-Analysis-Api-Key`를 전달한다. Node는 이를 검증해야 한다.
- 응답: JSON, `Cache-Control: no-store`. 사진·주소·응답·비밀키는 로그에 남기지 않는다.
- ID는 UUID. 분석 이미지 좌표는 `analysis-normalized`, bbox는 `[x1,y1,x2,y2]`, 각 값 0~1. GPS는 bbox null.

## POST /api/inspections

`application/json`, 필수 헤더 `Idempotency-Key: <UUID>`.

| 필드 | 규칙 |
|---|---|
| assetId | 필수 UUID. `/api/assets`에서 업로드한 자산이어야 함. 서버가 저장된 이미지와 내부 EXIF를 로드 |

현재 `stub`은 사진 내용과 EXIF를 분석하지 않습니다. Node 어댑터에는 기존 multipart 입력을 유지하기 위해 이미지 파일과 `locationRole=capture`, `compareHistory=false`를 전달합니다. GPS·촬영 시각 필드는 Node 파이프라인 계약이 정해지지 않아 아직 전달하지 않습니다.

요청 예시:

```json
{"assetId":"11111111-1111-4111-8111-111111111111"}
```

응답 HTTP 202, Location `/api/inspections/{inspectionId}`:

```json
{"inspectionId":"11111111-1111-4111-8111-111111111111","status":"queued","assetId":"11111111-1111-4111-8111-111111111111","mock":true,"pipelineVersion":"stub-v1","riskSummary":{"score":null,"level":"pending"},"riskFactors":[]}
```

`stub` 응답은 고정 합성 결과를 즉시 완료한 뒤 첫 응답에서 `queued`를 반환하고 GET에서 `completed`를 반환합니다. 실제 Node도 요청을 저장·큐 등록한 뒤 즉시 응답해야 하며 긴 분석 완료까지 POST 연결을 유지하지 않습니다. Spring은 키와 자산 ID·이미지 해시를 DB에 저장해 다른 입력의 키 재사용을 409로 거절합니다. 같은 자산 재시도는 같은 키를 분석 드라이버에 전달합니다. 자동 재시도는 없습니다. 타임아웃 후 앱은 같은 키와 같은 자산으로 재시도합니다.

## GET /api/inspections/{inspectionId}

HTTP 200. 상태는 queued/processing/completed/failed. Spring은 ID·상태를 검증하고 나머지 Node 응답을 전달한다. 심층 결과 스키마 검증은 후속 작업이다.

```json
{
  "inspectionId":"11111111-1111-4111-8111-111111111111",
  "status":"completed",
  "assetId":"11111111-1111-4111-8111-111111111111",
  "mock":true,
  "pipelineVersion":"stub-v1",
  "mockNotice":"모의 예시 결과이며 실제 사진을 분석하지 않았습니다.",
  "image":{"url":"https://example.invalid/images/analysis.jpg","width":1536,"height":2048,"coordinateSpace":"analysis-normalized"},
  "riskSummary":{"score":null,"level":"pending","reason":"평가 규칙 미확정"},
  "riskFactors":[{"id":"factor-1","type":"sign_text","title":"창밖 간판","description":"문자열 확인","text":"예시 상호","bbox":[0.1,0.2,0.4,0.3],"sources":["ocr","gpt"],"reviewStatus":"unreviewed"}],
  "repeatedExposure":{"status":"unavailable","message":"게시 이력 없음","matchedImageIds":[],"samePlaceConfirmed":false}
}
```

이미지 URL은 프론트에서 접근 가능한 URL이어야 한다. Node 내부의 상대경로를 그대로 보내면 Spring 경로로 오인될 수 있다. 만료되는 접근 URL 또는 별도 이미지 게이트웨이는 Node 저장 구조 확정 후 구현한다.

## GET /api/inspections?assetId={assetId}

특정 자산에서 실행된 분석 작업을 최근 요청 순으로 반환한다. 이 목록은 Spring DB에 마지막으로 기록된 상태이며 Node 상태를 일괄 조회하지 않는다. `inspectionId`가 없는 `submitting` 항목은 요청이 전달됐는지 아직 확정되지 않은 작업이다.

```json
[
  {
    "inspectionId":"33333333-3333-4333-8333-333333333333",
    "assetId":"11111111-1111-4111-8111-111111111111",
    "status":"completed",
    "requestedAt":"2026-10-30T08:00:00Z",
    "updatedAt":"2026-10-30T08:00:03Z"
  }
]
```

존재하지 않는 자산은 404입니다. 이 API는 사진 한 장의 개별 실행 기록만 제공하며 묶음·누적 결과를 계산하지 않습니다.

## 보호 편집 API 초안

현재 API는 `EDITING_PROVIDER=stub` 모의 드라이버만 제공합니다. 편집 요청은 이미 완료된 단독 분석과 해당 결과에 실제로 존재하는 단서 ID를 받아 동기식으로 처리합니다.

```http
POST /api/edits
Idempotency-Key: <UUID>
Content-Type: application/json
```

```json
{
  "inspectionId":"33333333-3333-4333-8333-333333333333",
  "cueIds":["stub-clue-1"],
  "preservePrompt":"사진 분위기와 음식은 그대로 유지해 주세요."
}
```

응답에는 편집 작업 ID, 원본·수정 자산 ID, 상태, `mock`, `mockNotice`, provider 버전이 포함됩니다. stub은 원본 이미지 바이트를 그대로 복사해 새 자산으로 저장하고 다음 문구를 표시합니다: `모의 편집입니다. 이미지 내용은 수정하지 않았습니다.` 원본 자산은 유지됩니다.

stub은 즉시 처리하므로 성공 시 `201 Created`를 반환합니다. 요청한 단서 ID가 분석 결과에 없으면 400, 분석이 완료되지 않았으면 409입니다.

```json
{
  "editId":"55555555-5555-4555-8555-555555555555",
  "inspectionId":"33333333-3333-4333-8333-333333333333",
  "sourceAssetId":"11111111-1111-4111-8111-111111111111",
  "editedAssetId":"66666666-6666-4666-8666-666666666666",
  "verificationInspectionId":"77777777-7777-4777-8777-777777777777",
  "verificationStatus":"queued",
  "cueIds":["stub-clue-1"],
  "status":"completed",
  "mock":true,
  "mockNotice":"모의 편집입니다. 이미지 내용은 수정하지 않았습니다.",
  "providerVersion":"stub-copy-v1",
  "createdAt":"2026-10-30T08:00:00Z",
  "updatedAt":"2026-10-30T08:00:00Z"
}
```

수정 자산은 동일한 분석 드라이버로 재검사되고, 최초 응답에는 재검사 ID와 마지막으로 확인된 상태가 포함됩니다. 최신 상태·결과는 `GET /api/inspections/{verificationInspectionId}`에서 조회합니다. `GET /api/edits/{editId}`는 편집 기록을 다시 반환합니다. 같은 idempotency key와 같은 입력은 기존 작업을 반환하고, 다른 입력은 409입니다. 실제 이미지 생성, GPT 연결, 수정본 EXIF/GPS 제거 정책은 이 모의 구현에 포함하지 않습니다. 모의 수정본에는 원본과 같은 이미지 바이트가 있을 수 있으므로 보호된 결과로 사용하면 안 됩니다.

## 오류

```json
{"error":{"code":"INVALID_REQUEST","message":"필수 입력과 UUID 형식을 확인해 주세요."}}
```

400 입력 오류 / 401 앱 키 오류 / 404 작업·자산 없음 / 409 중복 키 충돌 / 413 용량 초과 / 415 형식 오류 / 429 요청 제한 / 502 Node 오류·잘못된 응답 / 503 서버 설정 누락 / 504 연결 실패·시간 초과.
Node 오류 본문은 개인정보 유출을 막기 위해 전달하지 않는다.

## 호출 예시 (PowerShell)

```powershell
$asset = curl.exe -s -X POST http://localhost:8080/api/assets -H "X-App-Api-Key: $env:APP_API_KEY" -F "photo=@C:/samples/synthetic.jpg" | ConvertFrom-Json
$inspectionKey = [guid]::NewGuid().ToString()
$body = @{ assetId = $asset.assetId } | ConvertTo-Json
curl.exe -X POST http://localhost:8080/api/inspections -H "X-App-Api-Key: $env:APP_API_KEY" -H "Idempotency-Key: $inspectionKey" -H "Content-Type: application/json" -d $body
curl.exe http://localhost:8080/api/inspections/11111111-1111-4111-8111-111111111111 -H "X-App-Api-Key: $env:APP_API_KEY"
```

## Spring 저장 책임

- POST 전에 요청 키와 입력 해시를 저장한다. Node 응답 검증 후 Node ID·상태를 연결한다.
- GET으로 Node 결과를 읽을 때 로컬 기록이 있으면 상태를 갱신한다. Node 연결 실패로 기존 완료 상태를 지우지 않는다.
- DB 상태 `submitting`은 Node 응답 미확정 상태다. API 응답 상태는 기존 queued/processing/completed/failed를 유지한다.
- API 응답 DTO의 inspectionId/status는 명시적 타입이며, 나머지 분석 상세 필드는 기존 JSON 키를 유지한다. 결과 내부 bbox·점수 스키마의 강제 검증은 아직 하지 않는다.
- 낙관적 잠금 충돌은 409 CONCURRENT_UPDATE이며 동일 키로 재시도한다.
- 서버 키는 단일 사용자 파일럿용이다. 현재 사용자 ID가 없으므로 compareHistory는 사용자별 이력이 구현된 기능을 의미하지 않는다.
