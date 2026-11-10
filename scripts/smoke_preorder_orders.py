"""Real HTTP preorder-order checks; disposable loopback demo, synthetic data only."""
import argparse, copy, datetime as dt, http.cookiejar, json, os, secrets, time, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/figura/api');p.add_argument('--output',default='work/preorder-orders-20261007/http-preorder_orders.json');args=p.parse_args()
assert urllib.parse.urlsplit(args.base_url).hostname in ('127.0.0.1','localhost')
checks=[]
def check(name,ok):
    assert ok,name
    checks.append(name)
class Client:
    def __init__(self):
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf=self.req('/auth/csrf')[1]['data']['token']
    def req(self,path,method='GET',body=None,csrf=True,form=False):
        headers={}
        if method!='GET' and csrf:headers['X-XSRF-TOKEN']=self.csrf
        if body is not None:headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
        raw=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode() if body is not None else None
        try:
            with self.opener.open(urllib.request.Request(args.base_url+path,data=raw,method=method,headers=headers),timeout=15) as r:return r.status,json.load(r)
        except urllib.error.HTTPError as e:return e.code,json.load(e)
def login(admin):
    c=Client();assert c.req('/admin/login','POST',dict(username='shop_admin' if admin else 'shop_staff',password=os.environ['DEMO_ADMIN_PASSWORD' if admin else 'DEMO_STAFF_PASSWORD'],deviceId=str(uuid.uuid4())),form=True)[0]==200
    return c

import concurrent.futures
admin=login(True);staff=login(False);anon=Client();products=[];batches=[];orders=[]
def customer():
    c=Client();name='pre_'+uuid.uuid4().hex[:20];password=secrets.token_urlsafe(24)
    assert c.req('/customer/auth/register','POST',dict(username=name,password=password,displayName='合成預購會員'))[0]==201
    assert c.req('/customer/auth/login','POST',dict(username=name,password=password,deviceId=str(uuid.uuid4()),rememberMe=False))[0]==200
    return c

def fixture(capacity=3,seconds=86400):
    r=admin.req('/admin/products','POST',dict(title='Preorder order HTTP '+uuid.uuid4().hex,brand='Figura',category='公仔',description='Synthetic',expectedVersion=0,skus=[dict(code='PO-'+uuid.uuid4().hex,label='限定款',priceTwd=1280,enabled=True)]));assert r[0]==201;p=r[1]['data'];products.append(p['id'])
    p=admin.req('/admin/products/'+p['id']+'/publication','PATCH',dict(expectedVersion=p['version'],published=True))[1]['data']
    now=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).replace(microsecond=0)
    r=admin.req('/admin/preorders','POST',dict(productId=p['id'],skuId=p['skus'][0]['id'],name='Order batch '+uuid.uuid4().hex,description='<script>plain text</script>',priceTwd=1800,capacity=capacity,deadlineAt=(now+dt.timedelta(seconds=seconds)).isoformat(),arrivalFrom=(now+dt.timedelta(days=30)).date().isoformat(),arrivalTo=(now+dt.timedelta(days=40)).date().isoformat(),expectedVersion=0,requestId=str(uuid.uuid4()),note='Synthetic order fixture'));assert r[0]==201;b=r[1]['data']['current'];batches.append(b['id'])
    r=admin.req('/admin/preorders/'+b['id']+'/open','POST',dict(expectedVersion=b['version'],requestId=str(uuid.uuid4()),note='Synthetic order fixture'));assert r[0]==201
    return p,r[1]['data']['current']
def current(b):return admin.req('/admin/preorders/'+b['id'])[1]['data']['batch']
def ready(c,b,qty=1):
    aid=str(uuid.uuid4());r=c.req('/customer/addresses/'+aid,'PUT',dict(expectedVersion=0,recipientName='合成收件人',phone='0912345678',postalCode='100',city='測試市',district='測試區',street='合成路 1 號'));assert r[0]==200
    return dict(requestId=str(uuid.uuid4()),batchId=b['id'],quantity=qty,addressId=aid,addressVersion=1,expectedSubtotalTwd=1800*qty)
def op(o):return '/customer/preorders/'+o['id']
def place(c,w):
    r=c.req('/customer/preorders','POST',w);assert r[0]==201,r; o=r[1]['data']['order'];orders.append((c,o));return o
def cancel(c,o,body=None):return c.req(op(o)+'/cancel','POST',body or dict(requestId=str(uuid.uuid4()),expectedVersion=o['version']))
def stock(p):return admin.req('/admin/products/'+p['id']+'/skus/'+p['skus'][0]['id']+'/inventory')[1]['data']
try:
    c=customer();d=customer();p,b=fixture();w=ready(c,b,2);before=stock(p);cart=c.req('/customer/cart')[1]['data']
    check('匿名與員工不能查顧客預購',anon.req('/customer/preorders')[0]==401 and admin.req('/customer/preorders')[0]==401)
    check('顧客预購寫入需CSRF',c.req('/customer/preorders','POST',w,csrf=False)[0]==403)
    check('拒絕owner注入',c.req('/customer/preorders','POST',w|{'customerId':'spoof'})[0]==400)
    check('拒絕浮點數量',c.req('/customer/preorders','POST',w|{'quantity':1.5})[0]==400)
    check('拒絕錯誤金額',c.req('/customer/preorders','POST',w|{'expectedSubtotalTwd':1})[0]==409)
    check('核對地址版本',c.req('/customer/preorders','POST',w|{'addressVersion':2})[0]==409)
    check('不能指定別人的地址',d.req('/customer/preorders','POST',w)[0]==404)
    o=place(c,w);check('伺服器報價及本人地址快照',o['unitPriceTwd']==1800 and o['totalTwd']==3600 and o['shipping']['recipientName']=='合成收件人')
    check('獨立配額不改現貨或購物車',current(b)['heldQuantity']==2 and stock(p)==before and c.req('/customer/cart')[1]['data']==cart)
    check('三十分鐘保留精確且台北offset',dt.datetime.fromisoformat(o['expiresAt'])-dt.datetime.fromisoformat(o['createdAt'])==dt.timedelta(minutes=30) and o['expiresAt'].endswith('+08:00'))
    check('公開剩餘配額正確',anon.req('/catalog/preorders/'+b['id'])[1]['data']['availableQuota']==1)
    check('本人清單與詳情隔離',c.req('/customer/preorders')[1]['data']['total']==1 and d.req(op(o))[0]==404 and cancel(d,o)[0]==404)
    check('不能走現貨訂單或付款',c.req('/customer/orders/'+o['id'])[0]==404 and c.req('/customer/orders/'+o['id']+'/mock-payments')[0]==404 and admin.req('/admin/orders/'+o['id'])[0]==404)
    r=c.req('/customer/preorders','POST',w);check('同UUID下單重播同訂單',r[0]==200 and r[1]['data']['replayed'] and r[1]['data']['order']['id']==o['id'] and current(b)['heldQuantity']==2)
    check('同UUID改內容拒絕',c.req('/customer/preorders','POST',w|{'quantity':1,'expectedSubtotalTwd':1800})[0]==409)
    check('地址更新不改訂單快照',c.req('/customer/addresses/'+w['addressId']+'?expectedVersion=1','DELETE')[0]==200 and c.req(op(o))[1]['data']==o)
    r=admin.req('/admin/preorders/'+b['id']+'/close','POST',dict(expectedVersion=current(b)['version'],requestId=str(uuid.uuid4()),note='Synthetic closure'));assert r[0]==201
    check('批次截單保留既有訂單而拒絕新單',c.req(op(o))[1]['data']['status']=='PENDING' and c.req('/customer/preorders','POST',ready(c,b))[0]==409)
    cmd=dict(requestId=str(uuid.uuid4()),expectedVersion=o['version']);r=cancel(c,o,cmd);check('取消釋放獨立配額',r[0]==200 and r[1]['data']['order']['status']=='CANCELLED' and current(b)['heldQuantity']==0 and stock(p)==before)
    check('同UUID取消不重複釋放',cancel(c,o,cmd)[1]['data']['replayed'] and current(b)['heldQuantity']==0)
    check('終態下單重播不復活',c.req('/customer/preorders','POST',w)[1]['data']['order']['status']=='CANCELLED')
    m=admin.req('/admin/notifications?orderId='+o['id'])[1]['data']['items'];check('下單取消均有outbox且沒有重複',len(m)==2 and {x['eventKind'] for x in m}=={'PREORDER_PLACED','PREORDER_CANCELLED'})
    n=m[0];snap=json.loads(admin.req('/admin/notifications/'+n['id'])[1]['data']['snapshot']);check('通知僅六欄安全快照',set(snap)=={'orderId','eventKind','orderVersion','orderStatus','fulfillmentStatus','totalTwd'} and '0912345678' not in json.dumps(snap))
    r=admin.req('/admin/notifications/'+n['id']+'/mock-dispatch','POST',dict(requestId=str(uuid.uuid4()),expectedVersion=n['version'],note='Synthetic preorder local receipt',mode='SUCCESS'));check('預購通知可由既有MOCK佇列接受',r[0]==200 and r[1]['data']['current']['status']=='SENT')
    p,last=fixture(1);x=ready(c,last);y=ready(d,last)
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        results=list(pool.map(lambda z:z[0].req('/customer/preorders','POST',z[1]),[(c,x),(d,y)]))
    for client,r in zip([c,d],results):
        if r[0]==201:orders.append((client,r[1]['data']['order']))
    check('兩顧客搶最後配額只有一筆成功',sorted(r[0] for r in results)==[201,409] and current(last)['heldQuantity']==1)
    check('配額滿停止公開下單入口',not anon.req('/catalog/preorders/'+last['id'])[1]['data']['orderingEnabled'])
    p,short=fixture(2,5);s=place(c,ready(c,short));check('批次較早截止就是保留期限',s['expiresAt']==s['deadlineAt'])
    until=time.monotonic()+45
    while time.monotonic()<until and c.req(op(s))[1]['data']['status']=='PENDING':time.sleep(.25)
    check('真排程逾時釋放且保留稽核',c.req(op(s))[1]['data']['status']=='EXPIRED' and current(short)['heldQuantity']==0)
    m=admin.req('/admin/notifications?orderId='+s['id'])[1]['data']['items'];check('逾時通知只一筆',len(m)==2 and sum(x['eventKind']=='PREORDER_EXPIRED' for x in m)==1)
    check('分頁與UUID守門',c.req('/customer/preorders?pageSize=51')[0]==400 and c.req('/customer/preorders/bad')[0]==400)
finally:
    for c,o in orders:
        v=c.req(op(o))[1].get('data')
        if v and v['status']=='PENDING':cancel(c,v)
    for bid in batches:
        b=current({'id':bid})
        if b['status']!='CLOSED':admin.req('/admin/preorders/'+bid+'/close','POST',dict(expectedVersion=b['version'],requestId=str(uuid.uuid4()),note='Synthetic cleanup'))
    for pid in products:
        p=admin.req('/admin/products/'+pid)[1]['data']
        if p['status']=='PUBLISHED':p=admin.req('/admin/products/'+pid+'/publication','PATCH',dict(expectedVersion=p['version'],published=False))[1]['data']
        if p['status']=='DRAFT':admin.req('/admin/products/'+pid+'?expectedVersion='+str(p['version']),'DELETE')
    admin.req('/admin/logout','POST');staff.req('/admin/logout','POST')
Path(args.output).parent.mkdir(parents=True,exist_ok=True);Path(args.output).write_text(json.dumps(dict(passed=len(checks),checks=checks,synthetic=True),ensure_ascii=False,indent=2))
print('Preorder orders HTTP:',len(checks),'passed')
