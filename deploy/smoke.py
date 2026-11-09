"""Local Compose smoke check using synthetic pixels and stub analysis only."""
import argparse
import json
import os
from pathlib import Path
import struct
import urllib.error
import urllib.request
import uuid
import zlib

parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=['prepare', 'verify'])
parser.add_argument('--state', type=Path, required=True)
args = parser.parse_args()
base = 'http://127.0.0.1:' + os.environ.get('BACKEND_PORT', '18081')
key = os.environ['APP_API_KEY']
installation = '11111111-1111-4111-8111-111111111111'


def request(path, body=None, method=None, headers=None):
    payload = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, payload, {
        'X-App-Api-Key': key, 'X-Installation-Id': installation,
        'Content-Type': 'application/json', **(headers or {}),
    }, method=method)
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


if args.mode == 'prepare':
    try:
        urllib.request.urlopen(base + '/api/assets')
        raise AssertionError('Unauthenticated request unexpectedly accepted')
    except urllib.error.HTTPError as failure:
        assert failure.code == 401

    def chunk(name, data):
        return struct.pack('!I', len(data)) + name + data + struct.pack('!I', zlib.crc32(name + data) & 0xffffffff)
    pixels = (b'\0' + b'\x24\x78\xa0' * 4) * 4
    image = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 4, 4, 8, 2, 0, 0, 0))
             + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b''))
    boundary = 'syntheticBoundary'
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="photo"; filename="synthetic.png"\r\n'
            'Content-Type: image/png\r\n\r\n').encode() + image + f'\r\n--{boundary}--\r\n'.encode()
    req = urllib.request.Request(base + '/api/assets', body, {
        'X-App-Api-Key': key, 'Content-Type': f'multipart/form-data; boundary={boundary}',
    })
    with urllib.request.urlopen(req, timeout=30) as response:
        asset = json.load(response)
    inspection = request('/api/inspections', {'assetId': asset['assetId']}, headers={'Idempotency-Key': str(uuid.uuid4())})
    result = request('/api/inspections/' + inspection['inspectionId'])
    assert result['status'] == 'completed' and result['mock'] is True
    place = request('/api/places', {'name': '합성 검증 장소', 'category': 'other', 'latitude': 37.5, 'longitude': 127.0, 'radiusMeters': 300})
    request('/api/assets/' + asset['assetId'] + '/capture-location', {'latitude': 37.5, 'longitude': 127.0, 'source': 'manual'}, 'PUT')
    request('/api/places/' + place['placeId'] + '/assets', {'assetId': asset['assetId']})
    run = request('/api/cumulative-runs', {'placeId': place['placeId'], 'assetIds': [asset['assetId']]})
    assert run['score'] is None and len(run['excluded']) == 1
    args.state.parent.mkdir(parents=True, exist_ok=True)
    args.state.write_text(json.dumps({'assetId': asset['assetId'], 'runId': run['runId']}))
    args.state.chmod(0o600)
    print('PASS: authentication, synthetic upload, explicit mock result, exclusion of mock from cumulative score')
else:
    state = json.loads(args.state.read_text())
    assert request('/api/assets/' + state['assetId'])['assetId'] == state['assetId']
    assert request('/api/cumulative-runs/' + state['runId'])['score'] is None
    req = urllib.request.Request(base + '/api/assets/' + state['assetId'] + '/content', headers={'X-App-Api-Key': key})
    with urllib.request.urlopen(req) as response:
        assert response.read().startswith(b'\x89PNG')
    print('PASS: photo bytes and cumulative snapshot persisted after container replacement')
