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
