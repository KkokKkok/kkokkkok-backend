# 검사 API·분석 드라이버 개발 계약

현재 개발 엔드포인트의 기계 판독용 스키마는 [OpenAPI 초안](openapi.yaml)에 있습니다. 둘 다 미확정 파이프라인·운영 계약을 포함하지 않는 개발용 문서입니다.

## 이미지 자산·개발용 갤러리

검사 흐름과 별도로 `POST /api/assets`가 업로드된 사진을 개발용 갤러리에 보관합니다. multipart 필드 `photo`는 필수이며 JPEG/PNG/WebP, 최대 10MB입니다. `labels`는 선택 반복 필드로 최대 10개, 각 40자까지 지정할 수 있습니다. 이 값은 집·회사 등 사진에 붙이는 분류 태그이며 위치 좌표나 민감 장소 정보가 아닙니다. 추후 누적 분석에 활용할 예정이며 현재 단독 분석 요청에는 따로 전달하지 않습니다. 파일은 기본 `./private-data/assets`에 저장되고, H2에는 UUID·MIME 형식·크기·EXIF에서 추출한 선택 메타데이터·태그가 기록됩니다. `ASSET_STORAGE_ROOT`로 파일 경로를 바꿀 수 있습니다.

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

현재 구현은 로컬 개발용 저장 방식이며 사진 갤러리는 아직 설치 단위로 격리되지 않습니다. 누적 분석의 민감 장소·실행 이력은 `X-Installation-Id`별로 구분하지만, 이 헤더는 로그인 인증이 아닙니다. 이 계약을 다중 사용자 프로덕션 API로 간주하지 않습니다. 자산 삭제 뒤 분석 기록을 처리하는 규칙은 후속 설계 대상입니다.

앱은 먼저 `/api/assets`로 사진을 저장하고 `POST /api/inspections`에 `assetId`를 전달한다. Spring은 파일을 불러와 선택한 분석 드라이버에 넘기며 작업 메타데이터와 `assetId`를 JPA/H2에 저장한다. 기존 PoC의 `/api/analysis-jobs`와 자동 호환되지 않는다. 단독 분석의 최종 점수·사용자 계정 인증은 포함되지 않는다. 누적 분석 점수는 아래의 임시 규칙 API만 제공한다.

## 개발용 stub 드라이버

로컬에서 요청·응답 흐름을 확인할 때는 `ANALYSIS_PROVIDER=stub`을 사용한다(기본값). 이 드라이버는 사진 바이트를 분석하지 않고 고정된 합성 단서 하나와 예시 위험 점수를 반환하며, 응답에 `mock: true`, `pipelineVersion: "stub-v1"`, 모의 결과 안내를 넣는다. 실제 사진·모델·정확도는 검증하지 않는다.

실제 분석 서버 어댑터는 `ANALYSIS_PROVIDER=node`를 지정하고 `NODE_ANALYSIS_BASE_URL`, `NODE_ANALYSIS_API_KEY`를 설정해야 한다. 사진을 외부 AI 분석으로 전송하려면 사용자가 전송에 동의한 뒤 `NODE_ANALYSIS_EXTERNAL_IMAGE_CONSENT=true`로 명시적으로 활성화한다. 기본값은 비활성화다. 이 설정은 현재 개발용 단일 사용자 흐름이며, 최종 사용자 동의 UX를 대체하지 않는다. 앱과 API 경로는 분석기 선택에 의존하지 않도록 `AnalysisClient` 인터페이스를 경계로 둔다.

## 공통

- 기본 Spring 요청에는 `X-App-Api-Key`가 필요하다. 단일 사용자 개발 환경용 공유 키이며 사용자 계정 인증이 아니다. 로컬 에뮬레이터 연동 시에만 `SPRING_PROFILES_ACTIVE=local` 프로필로 키 검사를 끌 수 있으며 서버를 loopback에 바인딩한다. 여러 사용자의 데이터는 이 구조로 격리되지 않으므로 다중 사용자 서비스 전 인증/소유권 검증을 구현한다.
- Spring → 분석 서버: 설정된 `X-Analysis-Api-Key`와 동의 설정 `X-Analysis-Consent: true`를 전달한다. 분석 서버는 둘 다 확인해야 한다. 촬영 위치 보완 입력은 Spring의 비공개 검사 기록에만 저장하며 사진의 보이는 픽셀만 분석하는 모델에 전달하지 않는다.
- 응답: JSON, `Cache-Control: no-store`. 사진·주소·응답·비밀키는 로그에 남기지 않는다.
- ID는 UUID. 분석 이미지 좌표는 `analysis-normalized`, bbox는 `[x1,y1,x2,y2]`, 각 값 0~1. GPS는 bbox null.

## POST /api/inspections

`application/json`, 필수 헤더 `Idempotency-Key: <UUID>`.

| 필드 | 규칙 |
|---|---|
| assetId | 필수 UUID. `/api/assets`에서 업로드한 자산이어야 함. 서버가 저장된 이미지와 내부 EXIF를 로드 |
| locationFallback | 선택, 최대 120자. EXIF GPS가 없을 때만 적용하는 사용자의 촬영 위치 텍스트 |

현재 `stub`은 사진 내용과 EXIF를 분석하지 않습니다. 앱은 `assetId`와 선택적 `locationFallback`을 보내고 Spring이 저장된 사진을 분석 드라이버에 전달합니다. EXIF GPS가 있으면 서버가 이를 우선하며 수동 입력값을 버립니다. GPS가 없을 때만 최대 120자의 사용자가 입력한 촬영 위치를 Spring의 비공개 검사 기록에 보관합니다. 이는 위치 검색이나 지오코딩을 뜻하지 않습니다. 사진의 보이는 픽셀만 분석하는 Node 모델에는 이 위치 텍스트를 전달하지 않습니다. 사진 태그는 분석 요청에 포함하지 않습니다.

Spring `ANALYSIS_PROVIDER=node`의 단독 요청은 Node의 `/api/inspections`에 multipart `photo`와 동일한 `Idempotency-Key`를 보냅니다. API Key와 외부 전송 동의 헤더도 중계합니다. Node 결과의 `riskSummary`, `riskFactors`, bbox와 기타 분석 상세는 Spring이 전달합니다. 현재 연결 서버가 최종 점수를 만들지 않으므로 `riskSummary.score`는 `null`이며 앱은 미산정 상태로 표시합니다. 현재 Feature Discovery에서 bbox가 제공되지 않으면 앱은 영역을 표시하지 않고 관찰 설명만 보여줍니다. 이 응답 모양은 기초 연결용이고 API 계약은 파이프라인 합의 후 갱신합니다.

## 누적 분석 개발 API (v1 provisional)

누적 실행은 등록 장소와 연결한 저장 사진의 최신 완료 단독 검사를 다시 읽어 계산합니다. 앱은 첫 실행 때 UUID `X-Installation-Id`를 만들어 앱 내부에 저장하고 아래 장소·누적 이력 요청에 함께 보냅니다. 이 식별자는 로그인이나 갤러리 전체의 사용자 소유권 검사를 대신하지 않습니다.

민감 장소 좌표는 요청에서만 받고 응답에 내보내지 않습니다. 사진 촬영 좌표는 원본 EXIF를 우선 사용합니다. EXIF가 없는 사진은 `PUT /api/assets/{assetId}/capture-location`에 사용자가 직접 입력한 위도·경도와 `source: manual`을 보냅니다. 원본 EXIF를 덮어쓰지 않고 직접 입력 위치를 별도 필드로 보관하며, 응답의 `locationStatus`는 `manual`이 됩니다. 민감 장소 좌표를 사진 촬영 좌표 대신 넣지 않습니다.

```http
POST /api/places
X-Installation-Id: 11111111-1111-4111-8111-111111111111
Content-Type: application/json
```

```json
{"name":"우리 집","category":"home","latitude":37.5001,"longitude":127.0001}
```

장소 응답은 `placeId`, 이름, 종류, 생성 시각만 포함하고 좌표는 포함하지 않습니다. 저장 사진을 `POST /api/places/{placeId}/assets`의 `{"assetId":"..."}`로 한 장씩 연결합니다. 한 사진을 여러 민감 장소에 연결할 수 있습니다.

누적 분석은 `POST /api/cumulative-runs`로 동기 실행합니다. 단독 모델을 다시 실행하지 않고 최신 완료 결과를 재사용합니다.

```json
{"placeId":"22222222-2222-4222-8222-222222222222","assetIds":["33333333-3333-4333-8333-333333333333"]}
```

결과에는 `runId`, `algorithmVersion`, 점수 상태, 그룹과 근거, 제외 사유가 들어가며 전체 스냅샷을 H2에 저장합니다. `GET /api/cumulative-runs/{runId}`는 이후에도 당시 결과를 그대로 돌려줍니다. `GET /api/cumulative-runs/place/{placeId}`로 해당 장소의 실행 기록을 확인할 수 있습니다. 점수 버전은 `cumulative-score-v1-provisional`로, 단독 분석의 `humanRisk`(있으면 우선) 또는 `modelRisk`를 지점 단위 1.0, 주의/중간 0.6, 낮음 0.2로 임시 대응합니다. 실제 위치 특정도 분류 규칙으로 확정된 값이 아니므로 상대 점수로만 표시하고 확률로 해석하지 않습니다. 평가된 단서가 하나도 없으면 0점이 아니라 `score: null`, `scoreStatus: not_assessed`를 반환합니다.

그룹 중심은 각 남은 사진 중 반경 100m 이내 이웃이 가장 많은 촬영 좌표(medoid 후보)로 정하고, 각 구성 사진이 그 중심에서 100m 이내인지 확인합니다. 사진 간 연쇄 연결은 허용하지 않습니다. 같은 파일은 한 장의 독립 증거로 계산하고 방문 횟수는 촬영일 기준으로 셉니다. 점수 거리 구간만 결과에 표시하며 촬영·등록 좌표와 정확한 거리는 반환하지 않습니다. 이 API는 초기 규칙을 검토하기 위한 개발 버전으로, 팀 평가 후 그룹화·단서 강도·점수를 교체할 수 있습니다.

요청 예시:

```json
{"assetId":"11111111-1111-4111-8111-111111111111","locationFallback":"서울시 영등포구 예시로 12"}
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

존재하지 않는 자산은 404입니다. 이 API는 사진 한 장의 개별 실행 기록만 제공합니다.

## 다량 단독 분석

여러 사진을 한 번에 선택한 경우에도 각 사진은 독립된 단독 분석으로 처리합니다. 사진끼리 비교하거나 누적 점수를 계산하지 않습니다. 묶음 API는 접수와 사진별 상태를 함께 관리하는 용도이며 실제 AI 파이프라인에 별도 다량 추론 계약을 요구하지 않습니다.

`POST /api/inspection-batches`는 사진 1~20개의 `assetId`와 사진별 선택 위치를 받습니다. 각 자산에 EXIF GPS가 있으면 해당 사진의 `locationFallback`은 무시되고, GPS가 없으면 그 사진의 입력만 해당 단독 분석에 전달됩니다. 하나라도 자산·사진 검증에 실패하면 묶음 접수를 시작하지 않습니다. 개별 분석 서버 오류는 해당 항목만 `failed`로 기록하고 나머지 사진 요청은 계속합니다.

```http
POST /api/inspection-batches
Idempotency-Key: <UUID>
Content-Type: application/json
```

```json
{
  "items": [
    {"assetId":"11111111-1111-4111-8111-111111111111","locationFallback":"서울 예시 위치 1"},
    {"assetId":"22222222-2222-4222-8222-222222222222"}
  ]
}
```

응답은 HTTP 202이며 `Location: /api/inspection-batches/{batchId}`를 제공합니다. 각 항목은 기존 `/api/inspections/{inspectionId}`에서 조회 가능한 독립 ID를 가집니다.

```json
{
  "batchId":"33333333-3333-4333-8333-333333333333",
  "status":"queued",
  "mock":true,
  "items":[
    {"assetId":"11111111-1111-4111-8111-111111111111","inspectionId":"44444444-4444-4444-8444-444444444444","status":"queued","errorCode":null,"errorMessage":null,"mock":true,"requestedAt":"2026-10-30T08:00:00Z","updatedAt":"2026-10-30T08:00:00Z"},
    {"assetId":"22222222-2222-4222-8222-222222222222","inspectionId":"55555555-5555-4555-8555-555555555555","status":"queued","errorCode":null,"errorMessage":null,"mock":true,"requestedAt":"2026-10-30T08:00:00Z","updatedAt":"2026-10-30T08:00:00Z"}
  ]
}
```

`GET /api/inspection-batches/{batchId}`는 각 진행 중 항목의 단독 분석 상태를 새로 확인합니다. 묶음 상태는 `queued`, `processing`, `completed`, `partial`, `failed` 중 하나입니다. 분석 서버가 일시적으로 응답하지 않으면 마지막 확인 상태를 유지하고, 사진 한 장의 실패가 다른 사진 결과를 가리지 않습니다. 같은 키·같은 입력으로 다시 접수하면 기존 묶음을 반환하며, 키를 다른 입력에 재사용하면 409입니다. 현재 드라이버의 결과는 모두 합성 모의 응답입니다.

## 보호 편집 API 초안

보호 편집은 `EDITING_PROVIDER=stub`(기본값) 또는 `EDITING_PROVIDER=openai`를 선택할 수 있습니다. `stub`은 원본 바이트를 복사하고 실제 수정이 아님을 응답에 표시합니다. `openai`는 완료된 단독 분석의 선택 단서 bbox로 mask를 만들고 이미지 편집 API를 한 번 호출합니다. OpenAI 키는 `OPENAI_API_KEY` 환경 변수 또는 저장소 루트의 로컬 `.env`에서만 읽습니다.

```http
POST /api/edits
Idempotency-Key: <UUID>
Content-Type: application/json
```

```json
{
  "inspectionId":"33333333-3333-4333-8333-333333333333",
  "cueIds":["stub-clue-1"],
  "preservePrompt":"사진 분위기와 음식은 그대로 유지해 주세요.",
  "editStrength":50
}
```

`cueIds`에는 완료된 분석의 `riskFactors[].id` 또는 `visualObservations[].id`를 넣을 수 있습니다. 선택하는 항목은 한 번의 편집 요청에서 하나의 mask로 합쳐집니다. 시각 단서는 `bbox`가 `[왼쪽 x, 위쪽 y, 오른쪽 x, 아래쪽 y]` 순서의 0~1 정규화 좌표이며, `coordinateSpace`가 `analysis-normalized` 또는 `exif-oriented-normalized`로 제공되어야 합니다. 좌표가 빠진 시각 단서는 `409 ANALYSIS_CUE_GEOMETRY_UNAVAILABLE`로 거절합니다. GPS/EXIF 전용 metadata 단서는 bbox 없이 선택할 수 있으며 사진 결과를 PNG로 다시 인코딩해 촬영 메타데이터를 제거합니다.

`editStrength`는 0~100 정수이며 생략하면 50(자연스럽게)을 사용합니다. 낮은 값은 적은 변경, 높은 값은 단서를 분명하게 제거하도록 서버 프롬프트에 반영합니다. 이미지 API의 출력 품질(`OPENAI_IMAGE_QUALITY`)과는 별도입니다.

예를 들어 bbox가 있는 OCR 관찰 영역을 편집하려면 해당 관찰의 ID를 `cueIds`로 전달합니다.

```json
{
  "inspectionId":"33333333-3333-4333-8333-333333333333",
  "cueIds":["33333333-3333-4333-8333-333333333333:ocr:1"],
  "preservePrompt":"사진의 색감과 구도는 유지해 주세요.",
  "editStrength":50
}
```

분석 단서의 좌표 원점은 왼쪽 위, x는 오른쪽으로, y는 아래쪽으로 증가합니다. 사진은 EXIF 방향을 보정한 기준으로 좌표를 mask에 옮깁니다. 단서 ID는 `riskFactors`와 bbox가 있는 `visualObservations`에서 가져올 수 있습니다. 관찰 결과를 그대로 선택하는 경우 해당 관찰 ID를 보냅니다. 서버는 양쪽 목록을 확인하고 좌표를 검증한 뒤 선택 영역을 하나의 mask로 합칩니다.

실제 provider 설정 예시는 `.env.example`을 참고합니다. `EDITING_PROVIDER=openai`와 키가 설정된 경우에만 유료 이미지 편집 요청을 보냅니다. 기본 model은 `gpt-image-2.5-sunburst`, 품질은 `high`입니다. 실제 편집은 JPEG/PNG 입력을 지원하며, 현재 provider는 WebP 디코딩을 지원하지 않습니다.

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

수정 자산은 동일한 분석 드라이버로 재검사되고, 최초 응답에는 재검사 ID와 마지막으로 확인된 상태가 포함됩니다. 최신 상태·결과는 `GET /api/inspections/{verificationInspectionId}`에서 조회합니다. `GET /api/edits/{editId}`는 편집 기록을 다시 반환합니다. 같은 idempotency key와 같은 입력은 기존 작업을 반환하고, 다른 입력은 409입니다. 모의 수정본에는 원본과 같은 이미지 바이트가 있을 수 있으므로 `mock: true`인 결과를 보호된 사진으로 사용하면 안 됩니다.

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
