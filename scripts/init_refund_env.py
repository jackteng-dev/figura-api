"""Add only a missing mock refund switch to an existing private demo env."""
import os
from pathlib import Path
p=Path(__file__).resolve().parent.parent/'work/demo.env'
if not p.is_file():raise SystemExit('First run scripts/init_demo_env.py')
names={line.split('=',1)[0] for line in p.read_text().splitlines() if '=' in line and not line.startswith('#')}
if 'MOCK_REFUNDS_ENABLED' not in names:
 with p.open('a') as out:out.write("\nMOCK_REFUNDS_ENABLED='true'\n")
 os.chmod(p,0o600)
print('Missing mock refund switch added if needed; existing private settings unchanged')
