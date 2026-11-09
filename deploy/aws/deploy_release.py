"""Explicitly enabled CI coordinator. Publishes no credentials; EC2 roles load runtime secrets."""
import argparse
import base64
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import time
from refresh_runtime import validate

class InvocationPending(RuntimeError):
    pass


FILES = ['app.compose.yaml', 'analysis.compose.yaml', 'Caddyfile', 'deploy.sh', 'refresh_runtime.py']


def validate_images(images):
    registry = None
    for value in images.values():
        match = re.fullmatch(r'(\d{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com)/[a-z0-9][a-z0-9/_.-]*@sha256:[0-9a-f]{64}', value)
        if not match or registry not in (None, match[1]):
            raise ValueError('Use immutable images in one ECR registry')
        registry = match[1]
    return registry


def bundle(config, images):
    validate(config)
    validate_images(images)
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w:gz') as archive:
        for name in FILES:
            archive.add(Path(__file__).resolve().parent / name, arcname=name)
        generated = {
            'config.json': json.dumps(config).encode(),
            'release.env': (''.join(f'{key}={value}\n' for key, value in images.items()) + 'API_DOMAIN=' + config['domain'] + '\n').encode(),
        }
        for name, data in generated.items():
            info = tarfile.TarInfo(name); info.size = len(data); info.mode = 0o600
            archive.addfile(info, io.BytesIO(data))
    result = base64.b64encode(stream.getvalue()).decode()
    if len(result) > 20_000:
        raise ValueError('Release bundle exceeds SSM inline size budget; use a dedicated artifact channel')
    return result


def aws(arguments, region):
    result = subprocess.run(['aws', '--region', region, *arguments, '--output', 'json'],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        if b'InvocationDoesNotExist' in result.stderr:
            raise InvocationPending('Invocation not registered yet')
        raise RuntimeError('AWS command failed; inspect instance status and scoped IAM permissions')
    return json.loads(result.stdout)


def remote(instance, commands, region):
    request = {'InstanceIds': [instance], 'DocumentName': 'AWS-RunShellScript',
               'TimeoutSeconds': 60, 'Parameters': {'commands': commands, 'executionTimeout': ['1800']}}
    with tempfile.NamedTemporaryFile(mode='w', suffix='.json') as output:
        json.dump(request, output); output.flush()
        command = aws(['ssm', 'send-command', '--cli-input-json', 'file://' + output.name], region)['Command']['CommandId']
    deadline = time.monotonic() + 1900
    while time.monotonic() < deadline:
        try:
            result = aws(['ssm', 'get-command-invocation', '--command-id', command, '--instance-id', instance], region)
        except InvocationPending:
            time.sleep(3); continue  # Initial invocation registration can lag send-command.
        if result['Status'] == 'Success':
            return
        if result['Status'] in {'Cancelled', 'TimedOut', 'Failed', 'Cancelling'}:
            raise RuntimeError('Remote deployment failed; inspect SSM using the instance ID (runtime values are not printed here)')
        time.sleep(5)
    raise RuntimeError('Deployment status timed out. Check the in-flight SSM command before retrying.')


def deploy(config, images, release, app, analysis):
    if not re.fullmatch(r'[0-9a-f]{40}-\d+-\d+', release):
        raise ValueError('Invalid release identifier')
    if any(not re.fullmatch(r'i-[0-9a-f]{8,17}', value) for value in [app, analysis]) or app == analysis:
        raise ValueError('Provide separate app and analysis instance IDs')
    payload = bundle(config, images)
    registry = validate_images(images)
    path = '/opt/kkokkkok/releases/' + release
    for role, instance in [('analysis', analysis), ('app', app)]:
        commands = ['set -eu', 'umask 077', 'install -d -m 0700 ' + path,
                    "printf '%s' '" + payload + "' | base64 -d | tar -xz -C " + path,
                    'KKOKKKOK_ENABLE_AWS_DEPLOY=1 REGISTRY=' + registry + ' bash ' + path + '/deploy.sh ' + role]
        try:
            remote(instance, commands, config['region'])
        except RuntimeError:
            if role == 'app':
                # Restore the paired analysis release when the app failed after analysis succeeded.
                remote(analysis, ['KKOKKKOK_ENABLE_AWS_DEPLOY=1 bash ' + path + '/deploy.sh analysis rollback'], config['region'])
            raise
        print(role + ': deployment health verified')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    if not args.execute:
        print('Dry run: no AWS/Docker commands. Set the repository enable variable and manually confirm DEPLOY in GitHub before execution.')
    elif os.environ.get('KKOKKKOK_ENABLE_AWS_DEPLOY') != '1':
        parser.error('Actual deployment is disabled')
    else:
        try:
            config = json.loads(os.environ['AWS_PILOT_CONFIG'])
            images = {key: os.environ[key] for key in ['BACKEND_IMAGE', 'ANALYSIS_IMAGE', 'VISION_IMAGE']}
            deploy(config, images, os.environ['RELEASE_ID'], os.environ['APP_INSTANCE_ID'], os.environ['ANALYSIS_INSTANCE_ID'])
        except Exception:
            raise SystemExit('Deployment did not complete. Inspect SSM and retained releases before retrying; no secrets are included in this diagnostic.')
