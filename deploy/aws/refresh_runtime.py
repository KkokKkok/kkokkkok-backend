"""EC2 role only. Fetch secrets into mode-0600 runtime files without printing values."""
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request

KEYS = {'region', 'domain', 'analysisPrivateIp', 'databaseEndpoint', 'databaseSecretArn',
        'runtimeSecretArn', 'photoBucket', 'analysisModel', 'editModel'}


def validate(config):
    if set(config) != KEYS or any(not isinstance(v, str) or not v or any(ord(c) < 32 for c in v) for v in config.values()):
        raise ValueError('Invalid deployment configuration keys/values')
    if not re.fullmatch(r'[a-z]{2}(-[a-z]+)+-\d', config['region']):
        raise ValueError('Invalid AWS region')
    for name in ['domain', 'databaseEndpoint']:
        if not re.fullmatch(r'[a-z0-9]([a-z0-9.-]*[a-z0-9])?', config[name]) or '.' not in config[name]:
            raise ValueError('Invalid deployment hostname')
    if not config['databaseEndpoint'].endswith('.rds.amazonaws.com'):
        raise ValueError('Use the provisioned RDS hostname')
    if not ipaddress.ip_address(config['analysisPrivateIp']).is_private:
        raise ValueError('Analysis must use a private IP address')
    if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', config['photoBucket']):
        raise ValueError('Invalid bucket name')
    for name in ['databaseSecretArn', 'runtimeSecretArn']:
        if not re.fullmatch(r'arn:aws:secretsmanager:' + re.escape(config['region']) + r':\d{12}:secret:[A-Za-z0-9/_+=.@-]+', config[name]):
            raise ValueError('Invalid runtime secret ARN')
    return config


def secret(arn, region):
    result = subprocess.run(['aws', 'secretsmanager', 'get-secret-value', '--region', region,
                             '--secret-id', arn, '--query', 'SecretString', '--output', 'text'],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('Could not read runtime secret using the EC2 role')
    return json.loads(result.stdout)


def write_env(path, values):
    # Compose uses env_file format: raw. Never source this file in a shell.
    if any(not isinstance(v, str) or any(ord(c) < 32 for c in v) for v in values.values()):
        raise ValueError('Runtime values must be single-line strings')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as output:
        output.write(''.join(f'{key}={value}\n' for key, value in values.items()))
    os.chmod(path, 0o600)


def refresh(role, root):
    config = validate(json.loads((root / 'config.json').read_text()))
    runtime = secret(config['runtimeSecretArn'], config['region'])
    internal = runtime.get('INTERNAL_API_KEY', '')
    key = runtime.get('OPENAI_API_KEY', '')
    if not internal or not key:
        raise RuntimeError('Set both internal and OpenAI credentials before activating real providers')
    if role == 'app':
        database = secret(config['databaseSecretArn'], config['region'])
        # AWS trust bundle; TLS verifies the hostname and certificate on the download and DB connection.
        with urllib.request.urlopen('https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem', timeout=30) as response:
            bundle = response.read()
        if b'BEGIN CERTIFICATE' not in bundle:
            raise RuntimeError('Invalid RDS CA bundle')
        (root / 'rds-ca.pem').write_bytes(bundle)
        os.chmod(root / 'rds-ca.pem', 0o644)  # Public CA bundle must be readable by the non-root JVM.
        write_env(root / 'app.runtime.env', {
            'SPRING_PROFILES_ACTIVE': 'postgres,pilot', 'SERVER_ADDRESS': '0.0.0.0', 'PORT': '8080',
            'DB_URL': 'jdbc:postgresql://' + config['databaseEndpoint'] + ':5432/kkokkkok?sslmode=verify-full&sslrootcert=/run/rds-ca.pem',
            'DB_USERNAME': database['username'], 'DB_PASSWORD': database['password'],
            'ASSET_STORAGE_PROVIDER': 's3', 'ASSET_S3_BUCKET': config['photoBucket'], 'AWS_REGION': config['region'],
            'ANALYSIS_PROVIDER': 'node', 'NODE_ANALYSIS_BASE_URL': 'http://' + config['analysisPrivateIp'] + ':3000',
            'NODE_ANALYSIS_API_KEY': internal, 'NODE_ANALYSIS_EXTERNAL_IMAGE_CONSENT': 'true',
            'EDITING_PROVIDER': 'openai', 'OPENAI_API_KEY': key, 'OPENAI_IMAGE_MODEL': config['editModel'],
        })
    elif role == 'analysis':
        write_env(root / 'analysis.runtime.env', {
            'ANALYSIS_BIND_HOST': '0.0.0.0', 'PORT': '3000', 'ANALYSIS_API_KEY': internal,
            'ANALYSIS_API_HOSTS': config['analysisPrivateIp'] + ':3000', 'OPENAI_API_KEY': key,
            'OPENAI_MODEL': config['analysisModel'], 'SQLITE_PATH': '/data/analysis.sqlite', 'IMAGE_STORAGE_PATH': '/data/images',
            'ANALYSIS_VISION_STAGE_URL': 'http://vision:8091', 'ANALYSIS_VISION_STAGE_KEY': internal,
        })
        write_env(root / 'vision.runtime.env', {'ANALYSIS_VISION_STAGE_KEY': internal})
    else:
        raise ValueError('Unknown deployment role')


if __name__ == '__main__':
    try:
        refresh(sys.argv[1], Path(__file__).resolve().parent)
    except Exception:
        # Provider responses/exceptions may contain credentials; only a generic diagnostic reaches SSM logs.
        sys.exit('Runtime preparation failed. Check EC2 role, secret fields, config and RDS trust bundle access.')
