"""Create a pilot invite through an existing PostgreSQL connection. Writes the raw code only to a private file."""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import secrets
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--uses', type=int, default=10)
parser.add_argument('--days', type=int, default=7)
args = parser.parse_args()
if not 1 <= args.uses <= 100 or not 1 <= args.days <= 30:
    parser.error('uses must be 1..100, days must be 1..30')
# libpq PGHOST/PGPORT/PGDATABASE/PGUSER and .pgpass or PGPASSFILE are configured by the operator.
# No password, invite code, or token is placed in command arguments or printed.
code = base64.b32encode(secrets.token_bytes(13)).decode()[:20]
digest = hashlib.sha256(code.encode()).hexdigest()
args.output.parent.mkdir(parents=True, exist_ok=True)
fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
try:
    sql = ("insert into pilot_invites (code_hash, expires_at, remaining_uses) values "
           f"('{digest}', current_timestamp + interval '{args.days} days', {args.uses});\n")
    result = subprocess.run(['psql', '-X', '--set=ON_ERROR_STOP=1', '--quiet'], input=sql.encode(),
                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('Invite creation failed. Check PostgreSQL connectivity and schema; no code was issued.')
    with os.fdopen(fd, 'w') as output:
        fd = None
        output.write('-'.join(code[i:i+4] for i in range(0,20,4))+'\n')
except Exception:
    if fd is not None:
        os.close(fd)
    args.output.unlink(missing_ok=True)
    raise
print('Invite created. Read the chosen private output file and share through a private channel.')
