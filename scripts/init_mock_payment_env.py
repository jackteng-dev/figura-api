"""Add only missing mock settings to an existing private loopback demo env; never rewrite credentials."""
import os,secrets
from pathlib import Path
p=Path(__file__).resolve().parent.parent/'work/demo.env'
if not p.is_file():raise SystemExit('First run scripts/init_demo_env.py')
names={line.split('=',1)[0] for line in p.read_text().splitlines() if '=' in line and not line.startswith('#')}
new=[]
if 'MOCK_PAYMENTS_ENABLED' not in names:new.append("MOCK_PAYMENTS_ENABLED='true'")
if 'MOCK_PAYMENT_WEBHOOK_SECRET' not in names:new.append("MOCK_PAYMENT_WEBHOOK_SECRET='"+secrets.token_urlsafe(48)+"'")
if new:
 with p.open('a') as out:out.write('\n'+'\n'.join(new)+'\n')
 os.chmod(p,0o600)
print('Missing mock settings added if needed; existing credentials unchanged; no values printed')
