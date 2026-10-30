# 보호 편집 품질 테스트

분석 파이프라인이나 앱과 분리해, **실제 사진·직접 지정한 수정 영역·보존 조건**으로 이미지 편집 결과를 반복 평가합니다. 분석 결과나 DB ID는 필요하지 않습니다.

이 도구는 입력 사진을 EXIF 방향에 맞춰 정리하고 GPS 등 EXIF 메타데이터를 제외한 PNG를 만들어 API에 보냅니다. 원본과 결과물, 요청 조건은 저장소 바깥의 `~/Downloads/kkokkkok-edit-eval/`에만 저장합니다. 사진 파일명은 기록하지 않고 원본 SHA-256만 로컬 기록에 남깁니다. API 키와 오류 응답 본문은 출력하지 않습니다.

## 설치

```bash
python3 -m venv tools/protection_edit_eval/.venv
tools/protection_edit_eval/.venv/bin/pip install -r tools/protection_edit_eval/requirements.txt
```

## 먼저 mask 확인

좌표는 EXIF 방향을 반영한 사진 크기에 대해 `x,y,width,height` 순서의 0~1 비율입니다. 예를 들어 `0.42,0.18,0.25,0.16`은 사진 너비의 42%, 높이의 18% 위치에서 너비 25%, 높이 16% 영역을 뜻합니다.

```bash
tools/protection_edit_eval/.venv/bin/python tools/protection_edit_eval/evaluate.py \
  --image "$HOME/Pictures/test-photo.jpg" \
  --region 0.42,0.18,0.25,0.16 \
  --target "창밖에 보이는 상점 간판" \
  --preserve "음식과 테이블, 따뜻한 카페 조명, 전체 구도" \
  --dry-run
```

생성된 `mask-preview.png`에서 붉은 영역이 실제로 수정할 부분과 맞는지 먼저 확인합니다. `mask.png`의 투명 영역이 수정 대상으로 API에 전달됩니다.

## 실제 편집 실행

zsh에서 API 키를 조용히 입력해 환경 변수로 설정한 뒤 `--dry-run`을 빼고 실행합니다. API 키를 명령 인자나 파일에 넣지 않습니다.

```bash
read -s "OPENAI_API_KEY?OpenAI API key: "
export OPENAI_API_KEY
echo
tools/protection_edit_eval/.venv/bin/python tools/protection_edit_eval/evaluate.py \
  --image "$HOME/Pictures/test-photo.jpg" \
  --region 0.42,0.18,0.25,0.16 \
  --target "창밖에 보이는 상점 간판" \
  --preserve "음식과 테이블, 따뜻한 카페 조명, 전체 구도"
```

기본 모델은 편집 정밀도 비교를 위한 시작값 `gpt-image-2.5-sunburst`, 품질은 `high`입니다. 모델과 품질은 옵션으로 바꿔 같은 사진·영역·보존 조건에서 비교할 수 있습니다. 이 기본값은 제품의 최종 모델 결정이 아닙니다.

실행마다 결과 폴더가 만들어지며 `source.png`, `mask.png`, `mask-preview.png`, `edited.png`, `run.json`, `review.md`를 담습니다. `review.md`에 단서 제거, 편집 경계의 자연스러움, 사진 분위기, 보존 대상, 비대상 영역을 각각 1~5점으로 기록합니다. 여러 장면에서 같은 기준을 반복 적용하고, 같은 장면에서 보존 요청이나 품질 설정을 바꿔 비교합니다.

실행 등급은 1점(사용하기 어려울 정도로 부자연스럽거나 보존 대상 손상), 3점(사용 가능하지만 차이가 눈에 띔), 5점(자연스럽고 보존 조건을 충족)으로 통일합니다. 이는 비교를 위한 기록이며 자동 통과 판정이나 제품 출시 기준은 아닙니다.

초기 비교는 실내 간판, 건물 표식, 창밖 거리 풍경처럼 서로 다른 실제 장면을 소수 골라 진행합니다. 각 시도에서 표적 영역과 요구 프롬프트를 기록하고, 한 번에 모델·품질·보존 요청 중 한 조건만 바꿔 결과 차이의 원인을 구분합니다. 결과가 크게 달라지는 조건만 추가 실행해 비용을 아낍니다.

## 품질 판단 기준

- **단서 제거:** 간판·건물 번호 등 표적이 사라지고 읽을 수 있는 잔여물이 남지 않는가
- **자연스러움:** 채움 영역이 원근·조명·그림자·질감·초점과 맞물려 경계가 튀지 않는가
- **분위기 보존:** 사진 전체의 색감·밝기·장면 분위기·촬영 스타일이 이어지는가
- **보존 대상:** 사람, 음식, 제품 등 요청한 피사체의 형태와 중요한 세부가 유지되는가
- **비대상 변경:** 지정 영역 밖의 구도나 세부를 불필요하게 바꾸지 않는가
- **부작용:** 새 글자·로고·위치 단서를 만들거나 다른 식별 단서를 드러내지 않는가

GPT Image 편집 mask는 영역을 안내하는 용도이며 경계를 픽셀 단위로 보장하지 않습니다. 따라서 mask 미리보기와 수정본을 사람이 직접 비교해 품질을 평가합니다. 이 도구는 자동 점수나 분석 파이프라인을 만들지 않습니다.

참고: [OpenAI Image generation and editing guide](https://developers.openai.com/api/docs/guides/image-generation), [Image edit API reference](https://developers.openai.com/api/reference/python/resources/images/methods/edit)

## 로컬 검증

```bash
tools/protection_edit_eval/.venv/bin/python -m unittest discover \
  -s tools/protection_edit_eval -p 'test_*.py'
```

단위 테스트와 `--dry-run`은 API를 호출하지 않습니다. 실제 실행은 선택한 사진을 OpenAI Images API에 전송하고 사용량 비용이 발생할 수 있습니다. 계정에 이미지 모델 사용 권한이 있는지 확인한 뒤 실행하세요. 조직 인증이 필요한 경우 [OpenAI API 조직 인증 안내](https://developers.openai.com/api/docs/guides/image-generation#choosing-the-right-api)를 확인하세요.
