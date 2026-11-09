"""Add only missing private SeaweedFS settings; keep existing keys/false values."""
import json,os,secrets,shlex
from pathlib import Path
root=Path(__file__).resolve().parent.parent;p=root/'work/demo.env'
if not p.is_file():raise SystemExit('First run scripts/init_demo_env.py')
values={}
for line in p.read_text().splitlines():
 if line and not line.startswith('#') and '=' in line:
  k,v=line.split('=',1);values[k]=shlex.split(v)[0] if shlex.split(v) else ''
defaults={'SHOP_IMAGES_ENABLED':'true','SHOP_IMAGES_ENDPOINT':'http://127.0.0.1:18333','SHOP_IMAGES_ACCESS_KEY':secrets.token_urlsafe(18),'SHOP_IMAGES_SECRET_KEY':secrets.token_urlsafe(32),'SHOP_IMAGES_BUCKET':'elpsy-shop-images-demo','SHOP_IMAGES_INIT_BUCKET':'true'}
with p.open('a') as f:
 for k,v in defaults.items():
  if k not in values:f.write('\n'+k+'='+shlex.quote(v)+'\n');values[k]=v
os.chmod(p,0o600)
if values['SHOP_IMAGES_BUCKET']!='elpsy-shop-images-demo':raise SystemExit('Dedicated demo bucket must be elpsy-shop-images-demo')
config=root/'work/seaweed-s3.json'
if not config.exists():
 with os.fdopen(os.open(config,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as f:json.dump({'identities':[{'name':'elpsy-shop-demo','credentials':[{'accessKey':values['SHOP_IMAGES_ACCESS_KEY'],'secretKey':values['SHOP_IMAGES_SECRET_KEY']}],'actions':[a+':elpsy-shop-images-demo' for a in ['Admin','Read','Write','List']]}]},f)
print('Missing private image settings/config added if needed; existing values preserved')
