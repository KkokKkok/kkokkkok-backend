# 마이 페이지

`GET /api/profile`, `X-Installation-Id: <UUID>` 필수. 파일럿 모드는 Bearer 인증 기기 식별자로 대체한다.

```json
{
  "displayName": "테스터",
  "photoChecks": 2,
  "protectionEdits": 1,
  "cumulativeChecks": 0,
  "sensitivePlaces": [{"name":"집", "category":"home"}],
  "storagePolicy": {"autoDeleteOriginals":false,"retention":"until_user_deletes"}
}
```

사진 검사는 원본 사진의 완료된 검사 작업 수(다량 검사 각 항목 포함, 편집 후 재검사 제외), 보호 편집은 원본 사진의 완료된 편집 작업 수, 누적 분석은 해당 기기에 등록된 장소의 실행 기록 수이다. 검사 결과의 위험도나 AI 품질을 나타내는 통계가 아니며 개발용 모의 실행 기록도 작업 수에 포함한다. 파일럿 모드에서는 해당 기기 소유 사진만 집계한다. 로컬 단독 개발 모드에서는 사진은 기존 공용 저장소 범위, 장소와 누적 분석은 요청 기기 범위다.

원본과 편집 이미지는 직접 삭제하기 전까지 보관한다. 자동 삭제는 꺼져 있고 기간 변경 API는 제공하지 않는다. 로그인 계정 정보가 없어 이름은 테스터다. 사진이나 GPS 좌표는 응답하지 않는다. 기록이 없으면 0과 빈 목록을 반환한다. 인증 오류는 기존 공통 오류 형식을 따른다.
