"""建立新的 loopback 展示私有設定；不覆蓋既有檔案、不輸出憑證。"""
import os
import secrets
from pathlib import Path

root = Path(__file__).resolve().parent.parent
values = dict(line.split('=', 1) for line in (root / '.env.example').read_text().splitlines()
              if line and not line.startswith('#'))
for name in ('DB_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'DEMO_ADMIN_PASSWORD', 'DEMO_STAFF_PASSWORD', 'MOCK_PAYMENT_WEBHOOK_SECRET', 'SHOP_IMAGES_ACCESS_KEY', 'SHOP_IMAGES_SECRET_KEY'):
    values[name] = secrets.token_urlsafe(24)
values['JWT_SECRET'] = secrets.token_urlsafe(48)
private = root / 'work' / 'demo.env'
private.parent.mkdir(exist_ok=True)
with os.fdopen(os.open(private, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as file:
    file.write('# 私有 loopback demo 設定；不要提交或公開。\n')
    for name, value in values.items():
        file.write(f"{name}='{value}'\n")
print('已建立 work/demo.env；展示用戶 shop_admin／shop_staff 的密碼請在自己的私有檔查看。')
