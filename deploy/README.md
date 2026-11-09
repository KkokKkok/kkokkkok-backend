# 로컬 배포 환경 준비

AWS 계정과 유료 AI 호출 없이 Docker 실행 환경을 확인하는 구성입니다. 기존 휴대폰용 서버/사진 폴더를 사용하지 않고 독립 Docker volume에 저장합니다. 기본 개발 DB는 H2이며, 아래 PostgreSQL 구성을 별도로 선택할 수 있습니다.

```sh
cp deploy/.env.example deploy/.env
# deploy/.env에서 로컬용 임의 인증 키와 분석 저장소 경로를 설정
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --build backend
```

기본 API는 localhost:18081이며 모든 /api 요청에 X-App-Api-Key가 필요합니다. 기본 analysis/editing Provider는 명시적으로 stub이고 OpenAI 키는 빈 값입니다. 실제 AI 검증 결과가 아닙니다. 분석 컨테이너는 `--profile analysis`, OCR·객체 컨테이너는 `--profile vision`을 지정할 때만 실행되며 외부 포트를 공개하지 않습니다. 모델 다운로드·실제 추론 성능은 별도 검증 대상입니다.

`docker compose ... up -d --force-recreate backend`로 컨테이너를 바꿔도 사진과 DB는 volume에 유지됩니다. `down -v`는 volume까지 삭제하므로 초기화 목적 외에는 사용하지 않습니다. 데이터 volume을 다른 서버로 옮기려면 백업/복원이 필요하며, 컨테이너 이미지에는 데이터가 포함되지 않습니다.

이 파일은 로컬 검증용입니다. 실제 배포는 PostgreSQL/S3 연결, 설치별 데이터 접근 제어, HTTPS, AWS 계정·권한 설정과 실제 환경 검증을 마친 뒤 진행합니다. 클라우드 자원 생성이나 CI 실행은 이 명령에 포함되지 않습니다.

## 합성 데이터 검증

설정 파일의 APP_API_KEY를 환경 변수로 주입한 상태에서 다음을 실행합니다. 이 스크립트는 모의 Provider를 사용하는 위 로컬 Compose 전용입니다. 실제 앱의 서버 주소를 지정하지 않습니다.

```sh
python3 deploy/smoke.py prepare --state private-data/container-smoke-state.json
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --no-build --force-recreate backend
# health가 정상으로 돌아온 뒤
python3 deploy/smoke.py verify --state private-data/container-smoke-state.json
```

인증, 합성 사진 업로드, 모의 결과 구분, 모의 결과의 누적 점수 제외와 사진/누적 스냅샷 보존을 확인합니다. stub 분석 드라이버의 원본 응답은 기존과 같이 메모리에 있어 서버 재시작 후 재조회할 수 없습니다. 실제 분석 드라이버의 저장은 분석 서버 영속 volume으로 별도 검증합니다.

## PostgreSQL과 스키마 버전 관리

실제 배포용 DB는 PostgreSQL을 사용합니다. 로컬에서도 AWS 없이 확인할 수 있습니다. `.env`의 `DB_PASSWORD`를 충분히 긴 임의 값으로 설정합니다.

```sh
docker build -t kkokkkok-backend:postgres .
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.postgres.yaml up -d backend
```

PostgreSQL 포트는 호스트에 공개하지 않습니다. DB와 사진은 각각 독립 volume에 보관하며, DB만 백업하면 사진까지 복원되는 구조는 아닙니다.

`postgres` Spring profile은 Flyway로 최초 스키마를 생성하고 Hibernate는 스키마 일치 여부만 검사합니다. 이후 변경은 기존 SQL 수정 대신 새 버전 migration으로 추가합니다. baseline과 clean은 자동 실행하지 않습니다. 개발·테스트의 H2 profile은 기존 자동 생성 방식을 유지합니다.

기존 H2 파일을 PostgreSQL로 자동 변환하지 않습니다. 현재 로컬 실제 사진·기록은 그대로 유지하며, 실제 배포에 옮길 데이터는 별도 내보내기/가져오기와 백업 검증이 필요합니다. RDS 연결·권한·백업 복구는 AWS 계정 연결 후 검증합니다.

위 합성 데이터 smoke 검증은 같은 방식으로 실행할 수 있습니다. PostgreSQL 컨테이너와 backend를 모두 교체한 뒤 `verify`로 사진·누적 스냅샷 보존을 확인합니다. 기본 모의 분석 원본 응답의 메모리 저장 제약은 동일합니다.
