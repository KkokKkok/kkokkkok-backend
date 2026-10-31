# 보호 편집 품질 테스트

분석 파이프라인이나 앱과 분리해, **실제 사진·텍스트로 설명한 단서·보존 조건**으로 위치 찾기부터 편집까지 반복 평가합니다. 분석 결과나 DB ID는 필요하지 않습니다. 이미 좌표나 마스크가 있으면 그 입력을 바로 사용할 수도 있습니다.

이 도구는 입력 사진을 EXIF 방향에 맞춰 정리하고 GPS 등 EXIF 메타데이터를 제외한 PNG를 만들어 API에 보냅니다. 원본과 결과물, 요청 조건은 저장소 바깥의 `~/Downloads/kkokkkok-edit-eval/`에만 저장합니다. 사진 파일명은 기록하지 않고 원본 SHA-256만 로컬 기록에 남깁니다. API 키와 오류 응답 본문은 출력하지 않습니다.

## 설치

```bash
python3 -m venv tools/protection_edit_eval/.venv
tools/protection_edit_eval/.venv/bin/pip install -r tools/protection_edit_eval/requirements.txt
```

## 브라우저에서 여러 모델 비교

같은 사진과 같은 mask를 두고 편집 모델만 바꿔 비교하는 로컬 페이지도 제공합니다. API 키는 기존 저장소 루트 `.env`의 `OPENAI_API_KEY`를 도구가 읽습니다.

```bash
tools/protection_edit_eval/.venv/bin/python tools/protection_edit_eval/web_app.py
```

출력된 `http://127.0.0.1:8765` 주소를 브라우저에서 엽니다. 사진과 수정 대상을 입력한 뒤 **수정 영역 찾기**를 누르면 mask가 생성됩니다. 붉은 mask 미리보기를 확인하고, 유지할 내용·편집 모델·품질·문맥 크기를 선택해 편집을 실행합니다. 한 mask에서 모델을 바꿔 여러 결과를 만들 수 있고, 저장된 이전 실행은 페이지를 새로 열어도 다시 볼 수 있습니다.

기본 비교 목록은 `gpt-image-2.5-sunburst`, `gpt-image-2.5-flare`, `gpt-image-2`, `gpt-image-1.5`, `gpt-image-1-mini`입니다. API 지원 여부는 계정과 시점에 따라 다를 수 있으며, 현재 지원 모델은 [Images Edit API 문서](https://developers.openai.com/api/reference/resources/images/methods/edit)에서 확인합니다. `gpt-image-2.5-sunburst`는 정밀 편집, `gpt-image-2.5-flare`는 빠른 편집의 비교 시작값입니다.

서버는 기본적으로 loopback 주소 `127.0.0.1`에만 바인딩하며 외부 접속을 열지 않습니다. 사진, mask와 실행 기록은 `~/Downloads/kkokkkok-edit-eval/web-runs/`에 저장됩니다. 사진과 요청은 선택한 모델 API로 전송되고 사용량 비용이 생길 수 있습니다. 모델 테스트를 다시 실행하기 전에 같은 mask의 붉은 미리보기를 확인하세요.

각 실행 기록에는 수정 대상, 보존 요청, mask 신뢰도, 선택한 모델과 품질, mask 생성 시간과 각 편집 요청의 수행 시간, 원본·mask 미리보기·수정 결과가 포함됩니다. mask 시간과 편집 시간은 따로 표시해 같은 mask로 여러 모델을 비교할 때 구분할 수 있습니다. 페이지는 자동 품질 점수를 매기지 않으므로 자연스러움과 사진 분위기는 결과를 나란히 보고 직접 기록합니다.

## 텍스트 단서에서 AI mask 만들기

분석 단계가 `"사진 속 컵 앞면의 스타벅스 로고"` 같은 텍스트만 넘긴다고 가정할 때 사용합니다. 시각 추론을 지원하는 `gpt-6.1-terra`가 사진에서 단서를 찾고 윤곽 다각형을 반환합니다. 도구가 그 윤곽을 PNG mask로 만들고 붉은 미리보기를 저장합니다.

```bash
tools/protection_edit_eval/.venv/bin/python tools/protection_edit_eval/mask_from_text.py \
  --image "$HOME/Pictures/test-photo.jpg" \
  --target "사진 속 컵 앞면에 인쇄된 스타벅스 로고"
```

생성된 `mask-preview.png`에서 표시 위치와 경계를 확인합니다. AI가 만든 윤곽은 실험값이며, 사진의 반사·가림·낮은 대비 때문에 틀릴 수 있습니다. 이 단계는 이미지 분석 API를 호출합니다.

미리보기를 확인한 뒤 만들어진 `mask.png`를 편집 단계에 전달합니다.

```bash
tools/protection_edit_eval/.venv/bin/python tools/protection_edit_eval/evaluate.py \
  --image "$HOME/Pictures/test-photo.jpg" \
  --mask-file "$HOME/Downloads/kkokkkok-edit-eval/masks/<실행 폴더>/mask.png" \
  --target "사진 속 컵 앞면에 인쇄된 스타벅스 로고" \
  --preserve "유리컵의 형태와 반사, 커피, 따뜻한 카페 분위기와 전체 구도" \
  --crop-context-scale 3
```

기존 분석에서 좌표 또는 mask가 제공되면 `mask_from_text.py` 단계는 건너뛰고, 외부 mask는 `--mask-file`로 전달할 수 있습니다.
`--crop-context-scale 3`을 주면 mask 주변 사진만 편집 모델에 보낸 뒤, 반환 이미지에서 mask 부분만 원본에 합성합니다. 원본 해상도와 mask 밖 픽셀을 유지하면서 편집 대상에 더 많은 해상도를 쓰는 시험 옵션입니다. raw crop 결과는 `edited-crop.png`, 전체 사진 결과는 `edited.png`에 저장합니다.

## 좌표를 직접 지정해 mask 확인

좌표는 EXIF 방향을 반영한 사진 크기에 대해 `x,y,width,height` 순서의 0~1 비율입니다. 예를 들어 `0.42,0.18,0.25,0.16`은 사진 너비의 42%, 높이의 18% 위치에서 너비 25%, 높이 16% 영역을 뜻합니다.

```bash
tools/protection_edit_eval/.venv/bin/python tools/protection_edit_eval/evaluate.py \
  --image "$HOME/Pictures/test-photo.jpg" \
  --region 0.42,0.18,0.25,0.16 \
  --target "창밖에 보이는 상점 간판" \
  --preserve "음식과 테이블, 따뜻한 카페 조명, 전체 구도" \
  --dry-run
```

생성된 `mask-preview.png`에서 붉은 영역이 실제로 수정할 부분과 맞는지 먼저 확인합니다. `mask.png`의 투명 영역이 수정 대상으로 API에 전달됩니다. 수동 사각형은 위치 데이터가 이미 정해진 상황의 간단한 대체 입력입니다.

## 실제 편집 실행

저장소 루트의 `.env` 파일에 아래 항목을 입력합니다. `.env`는 Git에서 제외되어 있어 키가 커밋되지 않습니다. 이미 셸의 `OPENAI_API_KEY` 환경 변수가 설정되어 있으면 그 값을 우선 사용합니다.

```bash
OPENAI_API_KEY=여기에_API_키
```

키가 저장된 뒤 `--dry-run`을 빼고 실행합니다. 수동 좌표는 `--region`, 준비된 mask는 `--mask-file` 중 하나를 전달합니다.

```bash
tools/protection_edit_eval/.venv/bin/python tools/protection_edit_eval/evaluate.py \
  --image "$HOME/Pictures/test-photo.jpg" \
  --region 0.42,0.18,0.25,0.16 \
  --target "창밖에 보이는 상점 간판" \
  --preserve "음식과 테이블, 따뜻한 카페 조명, 전체 구도"
```

기본 모델은 편집 정밀도 비교를 위한 시작값 `gpt-image-2.5-sunburst`, 품질은 `high`입니다. 모델과 품질은 옵션으로 바꿔 같은 사진·영역·보존 조건에서 비교할 수 있습니다. 이 기본값은 제품의 최종 모델 결정이 아닙니다.

실행마다 결과 폴더가 만들어지며 `source.png`, `mask.png`, `mask-preview.png`, `edited.png`, `run.json`, `review.md`를 담습니다. crop 모드에서는 `edit-source.png`, `edit-mask.png`, `edited-crop.png`도 함께 저장합니다. `review.md`에 단서 제거, 편집 경계의 자연스러움, 사진 분위기, 보존 대상, 비대상 영역을 각각 1~5점으로 기록합니다. 여러 장면에서 같은 기준을 반복 적용하고, 같은 장면에서 보존 요청이나 품질 설정을 바꿔 비교합니다.

## 기존 편집 결과를 원본과 합성

이전의 전체 사진 편집 결과를 추가 API 요청 없이 원본 및 mask와 합성해 볼 수도 있습니다.

```bash
tools/protection_edit_eval/.venv/bin/python tools/protection_edit_eval/preserve_outside.py \
  --source "$HOME/Downloads/kkokkkok-edit-eval/<실행 폴더>/source.png" \
  --edited "$HOME/Downloads/kkokkkok-edit-eval/<실행 폴더>/edited.png" \
  --mask "$HOME/Downloads/kkokkkok-edit-eval/<실행 폴더>/mask.png" \
  --output "$HOME/Downloads/kkokkkok-edit-eval/<실행 폴더>/preserved.png"
```

편집 결과 크기가 달라도 같은 가로·세로 비율이면 원본 크기에 맞춰 합성합니다. `--expand-px`는 부드러운 경계를 위해 실제 변경 범위를 넓히므로, 비대상 영역까지 바뀔 수 있습니다. 눈에 띄는 경계가 생기면 crop 모드와 결과를 비교하세요.

실행 등급은 1점(사용하기 어려울 정도로 부자연스럽거나 보존 대상 손상), 3점(사용 가능하지만 차이가 눈에 띔), 5점(자연스럽고 보존 조건을 충족)으로 통일합니다. 이는 비교를 위한 기록이며 자동 통과 판정이나 제품 출시 기준은 아닙니다.

초기 비교는 실내 간판, 건물 표식, 창밖 거리 풍경처럼 서로 다른 실제 장면을 소수 골라 진행합니다. 각 시도에서 표적 영역과 요구 프롬프트를 기록하고, 한 번에 모델·품질·보존 요청 중 한 조건만 바꿔 결과 차이의 원인을 구분합니다. 결과가 크게 달라지는 조건만 추가 실행해 비용을 아낍니다.

## 품질 판단 기준

- **단서 제거:** 간판·건물 번호 등 표적이 사라지고 읽을 수 있는 잔여물이 남지 않는가
- **자연스러움:** 채움 영역이 원근·조명·그림자·질감·초점과 맞물려 경계가 튀지 않는가
- **분위기 보존:** 사진 전체의 색감·밝기·장면 분위기·촬영 스타일이 이어지는가
- **보존 대상:** 사람, 음식, 제품 등 요청한 피사체의 형태와 중요한 세부가 유지되는가
- **비대상 변경:** 지정 영역 밖의 구도나 세부를 불필요하게 바꾸지 않는가
- **부작용:** 새 글자·로고·위치 단서를 만들거나 다른 식별 단서를 드러내지 않는가

GPT Image 편집 mask는 영역을 안내하는 용도이며 경계를 픽셀 단위로 보장하지 않습니다. 텍스트에서 윤곽을 찾는 비전 모델도 위치를 잘못 짚을 수 있습니다. 따라서 mask 미리보기와 수정본을 사람이 직접 비교해 품질을 평가합니다. 이 도구는 자동 점수나 제품 분석 파이프라인을 만들지 않습니다.

참고: [OpenAI Image generation and editing guide](https://developers.openai.com/api/docs/guides/image-generation), [Image edit API reference](https://developers.openai.com/api/reference/python/resources/images/methods/edit)

## 로컬 검증

```bash
tools/protection_edit_eval/.venv/bin/python -m unittest discover \
  -s tools/protection_edit_eval -p 'test_*.py'
```

단위 테스트와 `--dry-run`은 API를 호출하지 않습니다. 실제 실행은 선택한 사진을 OpenAI Images API에 전송하고 사용량 비용이 발생할 수 있습니다. 계정에 이미지 모델 사용 권한이 있는지 확인한 뒤 실행하세요. 조직 인증이 필요한 경우 [OpenAI API 조직 인증 안내](https://developers.openai.com/api/docs/guides/image-generation#choosing-the-right-api)를 확인하세요.
