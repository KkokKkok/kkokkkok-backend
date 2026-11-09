# AWS 비공개 파일럿 배포 준비

현재는 파일 작성과 로컬 검증 단계입니다. 이 문서의 AWS 생성·배포 명령은 실행하지 않았습니다. **스택 생성부터 EC2·RDS·EBS·공인 IPv4·S3·Secrets Manager 등의 비용이 발생합니다.** 계정 소유 팀원의 결제/지원 범위와 권한을 확인한 뒤 적용합니다.

## 구성

- 앱 서버: x86 Amazon Linux 2023 EC2, Spring + Caddy HTTPS. 외부에는 80/443만 엽니다. 80은 인증서 발급/HTTPS 전환용입니다.
- 분석 서버: 별도 x86 EC2, Node API/worker + Python OCR·객체 서버. 앱 서버에서 3000으로만 접근합니다. Python은 Docker 내부 통신만 사용합니다.
- DB: 외부 인터넷 경로가 없는 두 AZ의 subnet에 PostgreSQL RDS를 배치하고 앱 서버의 5432 접근만 허용합니다. 파일럿은 Single-AZ, 7일 자동 백업과 삭제 보호를 사용합니다.
- 사진: 공개 접근 차단·TLS 강제·암호화·버전 관리된 S3입니다. 앱 서버 역할에 `assets/*` 저장/읽기/삭제만 부여합니다. 앱은 인증 API로 사진을 받습니다.
- 분석 SQLite/작업 사진/모델 cache는 분석 호스트의 암호화 EBS에 남습니다. 현재 worker는 단일 호스트·단일 프로세스 구성입니다. 호스트 교체는 별도 백업 복원이 필요합니다.
- AWS 영구 키를 코드/GitHub에 넣지 않습니다. 사람은 팀원 계정의 IAM Identity Center 또는 제한된 개인 역할을, 서버는 EC2 역할을, CI는 GitHub OIDC 역할을 사용합니다. SSH 포트는 열지 않고 SSM을 사용합니다.

`infrastructure.yaml`은 CloudFormation 네트워크·저장소·서버 정의입니다. 직접 AWS에 업로드하기 전 `cfn-lint deploy/aws/infrastructure.yaml`로 정적 검증합니다. 정적 통과는 실제 리전의 인스턴스 가용성·IAM·용량·비용 검증이 아닙니다.

## 계정 소유자가 정할 것

1. 지원 결제 계정과 리전(우선 서울), 예산 알림/지원 상한, 작업자 IAM 권한. 예산 알림이 지출을 자동 중단하지는 않습니다.
2. 스택/ProjectName과 인스턴스 크기. 기본 app `t3.medium`, analysis `m7i.xlarge`, RDS `db.t4g.small`은 CPU 파일럿 초안입니다. OCR/객체 모델 실측 후 조정하며 GPU 설정은 준비되지 않았습니다.
3. 해당 리전에서 지원하는 PostgreSQL 버전. 빈 PostgresVersion은 AWS 기본 버전을 선택하므로 실제 생성 전 지원 버전을 확인해 명시하는 것을 권장합니다.
4. GitHub OIDC provider가 계정에 이미 있으면 ExistingGitHubOidcProviderArn을 전달합니다. 없을 때만 새로 만듭니다. CI 신뢰 범위는 BackendRepository의 main 브랜치로 제한합니다.
5. 앱 API용 도메인과 DNS 수정 권한. 스택 AppPublicIp로 A 레코드를 연결합니다. DNS·도메인 구매는 이번 단계에서 하지 않았습니다.

계정 소유자가 실제 생성 전 change set과 과금 대상을 검토합니다. 생성에는 IAM 자원 생성 권한이 필요합니다. 현재 자동으로 CloudFormation을 적용하는 workflow는 두지 않습니다.

## 생성 후 인계할 값

스택 Outputs의 Region, AppInstanceId, AnalysisInstanceId, AppPublicIp, AnalysisPrivateIp, DatabaseEndpoint, DatabaseSecretArn, RuntimeSecretArn, PhotoBucket, DeployRoleArn과 세 RepositoryUri가 필요합니다. 이 중 ARN/ID는 설정 값이며 비밀번호는 아닙니다. 실제 비밀값은 전달 파일·PR·로그에 넣지 않습니다.

RDS 비밀번호는 AWS가 관리합니다. RuntimeSecret에는 생성된 INTERNAL_API_KEY와 빈 OPENAI_API_KEY가 있습니다. 계정 소유자가 기존 내부 키를 유지한 채 OPENAI_API_KEY를 비공개로 채운 후 실제 분석을 활성화합니다. 원문 secret을 CLI 출력하거나 GitHub 변수에 복사하지 않습니다.

## 운영 및 복구 경계

- 실제 배포는 `postgres,pilot`을 고정 사용합니다. `local` profile과 공용 API 키 방식으로 외부 다인 사용을 허용하지 않습니다.
- RDS와 S3, 분석 SQLite/EBS는 각각 백업이 필요합니다. DB snapshot만으로 분석 결과·사진을 모두 복원할 수 없습니다. 실제 사진/기록 이관과 복구 검증이 필요합니다.
- S3 버전 관리 때문에 일반 삭제 이후 이전 버전이 남을 수 있습니다. 사용자 데이터 완전 삭제 정책을 적용할 때는 모든 버전과 분석 서버의 사본도 함께 처리해야 합니다. 자동 영구 삭제는 현재 준비에 포함하지 않았습니다.
- 스택 삭제 시 RDS는 삭제 보호로 중단될 수 있습니다. 의도적 정리 시 보호를 해제하고 snapshot/복구를 먼저 확인합니다. S3·ECR·RuntimeSecret과 EC2의 root EBS는 보존되며 별도 정리 전 비용이 남을 수 있습니다.
- EC2 재시작/컨테이너 교체와 EC2 교체는 다릅니다. 후자는 기존 EBS 데이터를 새 호스트로 자동 연결하지 않습니다. 분석 DB/사진/모델 cache를 백업·복원한 뒤 전환합니다.
- 이미지 rollback은 DB migration을 되돌리지 않습니다. 파괴적 schema 변경은 호환 기간과 별도 복구 계획이 필요합니다.
- 실제 OIDC/ECR/SSM, 역할/IMDSv2, 도메인/HTTPS, RDS 인증서, S3 권한, 모델 다운로드/추론 시간, 실기기 외부망 사용은 계정 연결 뒤 검증합니다.

기준 문서: [GitHub OIDC/AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws), [컨테이너 IMDSv2 hop limit](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-IMDS-new-instances.html).

## 수동 배포 자동화 연결 — 결제/계정 준비 후

`.github/workflows/aws-pilot-deploy.yml`은 push/PR에 자동 실행되지 않고 `workflow_dispatch`만 받습니다. 저장소 변수 `AWS_PILOT_DEPLOY_ENABLED=true`, main 브랜치, 입력 `confirmation=DEPLOY`가 모두 맞을 때만 실행합니다. 현재 변수는 설정하지 않았고 workflow도 실행하지 않았습니다. 수동 실행 자체에도 GitHub runner·ECR·AWS 비용이 발생할 수 있습니다.

다음 GitHub 저장소 Variables를 스택 Outputs로 채웁니다. AWS key나 OpenAI key를 GitHub에 복사하지 않습니다.

| 변수 | 값 |
|---|---|
| AWS_REGION | Region |
| AWS_DEPLOY_ROLE_ARN | DeployRoleArn |
| AWS_APP_INSTANCE_ID | AppInstanceId |
| AWS_ANALYSIS_INSTANCE_ID | AnalysisInstanceId |
| AWS_BACKEND_REPOSITORY_URI | BackendRepositoryUri |
| AWS_ANALYSIS_REPOSITORY_URI | AnalysisRepositoryUri |
| AWS_VISION_REPOSITORY_URI | VisionRepositoryUri |
| AWS_PILOT_CONFIG | config.example.json과 동일 키의 JSON. 도메인·private IP·DB endpoint·secret ARN·버킷·사용 가능한 모델명을 실제 값으로 변경 |

1. 소유자가 스택/예산·DNS·RuntimeSecret·SSM 접속과 호스트 bootstrap 완료를 확인합니다. DB 버전과 모델 접근 권한, 이미지 빌드에 필요한 GitHub runner 메모리/디스크를 확인합니다. 공개 PoC 저장소가 비공개로 바뀌면 저장소 간 읽기 권한을 별도로 구성해야 합니다.
2. 활성화 변수를 설정하고 main에서 workflow를 수동 실행합니다. 분석 저장소의 검토 완료된 전체 commit SHA를 입력합니다. 최초 도메인은 앱 EIP로 DNS가 전파되어야 하고 80/443 접근이 가능해야 합니다. 인증서가 발급되지 않으면 HTTPS 검증 실패로 배포가 중단됩니다.
3. Java/Node/Python Dockerfile의 기존 테스트를 실행하며 Linux amd64 CPU 이미지를 빌드합니다. 실행마다 고유 태그를 발행하고 실제 배포에는 ECR digest를 사용합니다. 현재 CI에서 직접 빌드·push하지 않았습니다.
4. 비밀값 없는 작은 release bundle을 SSM으로 보냅니다. 역할로 읽은 실제 secret은 EC2 release 폴더의 0600 raw env 파일에만 기록합니다. 내용을 shell에서 source하거나 Git/SSM 출력으로 내보내지 않습니다.
5. 분석 → 앱 순서로 시작하고 container health 및 앱의 실제 HTTPS health를 확인합니다. 실패하면 이전 release의 이미지·설정으로 복구합니다. 앱 실패 시 먼저 적용한 분석 release도 복구합니다. 첫 배포 실패에는 돌아갈 이미지가 없어 컨테이너를 내리되 volume을 삭제하지 않습니다.
6. 정상 배포 뒤에도 등록·실제 사진 분석/편집·기록 조회·누적 분석을 기기에서 검증해야 합니다. health는 모델 다운로드·실제 AI 품질·종단 기능 성공을 뜻하지 않습니다. 두 서버를 무중단으로 동시에 전환하는 구성도 아닙니다.

서버의 `/opt/kkokkkok/<app|analysis>/current`, `previous`가 적용 이력을 가리킵니다. 운영자는 SSM에서 직전 release의 `deploy.sh <role> rollback`을 명시적으로 실행해 이미지/설정을 되돌릴 수 있습니다. 실제 실행에는 `KKOKKKOK_ENABLE_AWS_DEPLOY=1`이 필요합니다. 새 DB migration·사용자 데이터·S3 변경은 이 명령으로 되돌리지 않습니다. 취소/시간 초과 때는 SSM의 실행 상태와 current/previous를 확인하고 진행 중인 배포 위에 다시 배포하지 않습니다. 오래된 release 폴더에는 비밀값이 있으므로 root 전용 권한을 유지하고 백업/보존 정책에 따라 정리합니다.

### 파일럿 참여자 배포

운영자는 SSM 포트 포워딩 등 허용된 접속으로 비공개 PostgreSQL에 접근해 [초대 발급 도구](../tools/create_invite.py)를 사용합니다. DB를 외부에 열지 않습니다. libpq/psql과 0600 PGPASSFILE을 준비하고 DB 비밀번호는 비공개 파일에만 기록합니다. 초대 코드 파일도 Git에서 제외하고 비공개로 전달합니다.

Android는 실제 `https://<도메인>`을 local.properties에 넣어 빌드합니다. 등록이 기본 활성화되며 APK에는 API key가 들어가지 않습니다. APK는 비공개 채널로 전달하고 최초 초대 등록 후 사용합니다. 업데이트에는 같은 applicationId·서명과 증가한 versionCode를 유지해야 합니다. 최종 배포용 서명 키는 별도 비공개 보관하며 저장소나 일반 빌드 산출물과 함께 올리지 않습니다. 현재 외부망 기기 테스트나 운영 APK 배포를 완료한 상태는 아닙니다.

## 결제 전 로컬 검증 명령

```sh
cfn-lint deploy/aws/infrastructure.yaml
actionlint .github/workflows/aws-pilot-deploy.yml
bash -n deploy/aws/deploy.sh
python3 -m unittest discover -s deploy/aws -p test_deployment.py
python3 deploy/aws/deploy_release.py
bash deploy/aws/deploy.sh app dry-run
```

마지막 두 명령은 AWS/Docker를 실행하지 않습니다. 오프라인 테스트는 합성 자격·가짜 AWS/Docker 명령으로 bundle 제한·0600 보관·미활성 차단·실패/복구 전환을 검증합니다. 실제 IAM·ECR·SSM·Linux 파일 잠금·HTTPS 배포 성공으로 해석하지 않습니다.
