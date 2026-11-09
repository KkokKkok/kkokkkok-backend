"""Offline deployment checks. No AWS, registry, model or real photo access."""
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import base64
import tempfile
import unittest
from unittest.mock import patch
import deploy_release
import refresh_runtime

ROOT = Path(__file__).resolve().parent
CONFIG = json.loads((ROOT / 'config.example.json').read_text())
REGISTRY = '111111111111.dkr.ecr.ap-northeast-2.amazonaws.com'
IMAGES = {key: REGISTRY + '/synthetic@sha256:' + '0' * 64 for key in ['BACKEND_IMAGE', 'ANALYSIS_IMAGE', 'VISION_IMAGE']}


class DeploymentTest(unittest.TestCase):
    def test_bundle_has_only_code_and_validated_non_secret_configuration(self):
        payload = deploy_release.bundle(CONFIG, IMAGES)
        with tarfile.open(fileobj=io.BytesIO(base64.b64decode(payload))) as archive:
            self.assertEqual(set(archive.getnames()), set(deploy_release.FILES + ['config.json', 'release.env']))
        with self.assertRaises(ValueError):
            deploy_release.bundle({**CONFIG, 'OPENAI_API_KEY': 'synthetic'}, IMAGES)
        with self.assertRaises(ValueError):
            deploy_release.bundle(CONFIG, {**IMAGES, 'BACKEND_IMAGE': REGISTRY + '/synthetic:latest'})

    def test_role_secrets_stay_in_private_raw_env_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / 'config.json').write_text(json.dumps(CONFIG))
            def secret(arn, _):
                if arn == CONFIG['runtimeSecretArn']:
                    return {'INTERNAL_API_KEY': 'synthetic-internal', 'OPENAI_API_KEY': 'synthetic-provider'}
                return {'username': 'synthetic', 'password': 'synthetic$raw"password'}
            with patch.object(refresh_runtime, 'secret', side_effect=secret), patch.object(refresh_runtime.urllib.request, 'urlopen', return_value=io.BytesIO(b'-----BEGIN CERTIFICATE-----\nsynthetic\n')):
                refresh_runtime.refresh('app', root)
                refresh_runtime.refresh('analysis', root)
            body = (root / 'app.runtime.env').read_text()
            self.assertIn('SPRING_PROFILES_ACTIVE=postgres,pilot\n', body)
            self.assertIn('DB_PASSWORD=synthetic$raw"password\n', body)
            self.assertIn('sslmode=verify-full', body)
            self.assertEqual((root / 'app.runtime.env').stat().st_mode & 0o777, 0o600)
            self.assertEqual((root / 'rds-ca.pem').stat().st_mode & 0o777, 0o644)
            self.assertIn('http://vision:8091', (root / 'analysis.runtime.env').read_text())

    def test_app_failure_rolls_back_the_paired_analysis_release(self):
        calls = []
        def remote(instance, commands, _):
            calls.append(commands)
            if len(calls) == 2:
                raise RuntimeError('synthetic app failure')
        with patch.object(deploy_release, 'remote', side_effect=remote):
            with self.assertRaises(RuntimeError):
                deploy_release.deploy(CONFIG, IMAGES, '0' * 40 + '-1-1', 'i-' + '0' * 17, 'i-' + '1' * 17)
        self.assertEqual(len(calls), 3)
        self.assertIn('analysis rollback', calls[-1][0])

    def test_shell_is_disabled_by_default_and_restores_previous_release_on_health_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            binpath = root / 'bin'; binpath.mkdir()
            for name, body in {
                'aws': '#!/bin/sh\necho synthetic-short-lived-password\n',
                'curl': '#!/bin/sh\nexit 0\n',
                'docker': '''#!/bin/sh
if [ "$1" = login ]; then cat >/dev/null; exit 0; fi
case "$*" in *bad/release.env*up*) exit 1;; esac
exit 0
''',
                # macOS has no flock. This fixture only exercises the deploy state machine, not Linux file locking.
                'flock': '#!/bin/sh\nexit 0\n',
            }.items():
                path = binpath / name; path.write_text(body); path.chmod(0o755)
            releases = []
            for label in ['old', 'good', 'bad']:
                release = root / label; release.mkdir(); releases.append(release)
                (release / 'deploy.sh').write_bytes((ROOT / 'deploy.sh').read_bytes())
                (release / 'refresh_runtime.py').write_text('# synthetic fixture: no AWS calls\n')
                (release / 'config.json').write_text(json.dumps(CONFIG))
                (release / 'release.env').write_text('')
            old, good, bad = releases
            state = root / 'state'; (state / 'analysis').mkdir(parents=True)
            (state / 'analysis/current').symlink_to(old)
            env = {**os.environ, 'PATH': str(binpath) + ':' + os.environ['PATH'], 'REGISTRY': REGISTRY, 'KKOKKKOK_DEPLOY_ROOT': str(state)}
            env.pop('KKOKKKOK_ENABLE_AWS_DEPLOY', None)
            disabled = subprocess.run(['bash', str(good / 'deploy.sh'), 'analysis'], env=env, capture_output=True)
            self.assertEqual(disabled.returncode, 2)
            env['KKOKKKOK_ENABLE_AWS_DEPLOY'] = '1'
            success = subprocess.run(['bash', str(good / 'deploy.sh'), 'analysis'], env=env, capture_output=True)
            self.assertEqual(success.returncode, 0)
            self.assertEqual((state / 'analysis/current').resolve(), good)
            self.assertEqual((state / 'analysis/previous').resolve(), old)
            failure = subprocess.run(['bash', str(bad / 'deploy.sh'), 'analysis'], env=env, capture_output=True)
            self.assertNotEqual(failure.returncode, 0)
            self.assertEqual((state / 'analysis/current').resolve(), good)
            rollback = subprocess.run(['bash', str(good / 'deploy.sh'), 'analysis', 'rollback'], env=env, capture_output=True)
            self.assertEqual(rollback.returncode, 0)
            self.assertEqual((state / 'analysis/current').resolve(), old)


if __name__ == '__main__':
    unittest.main()
