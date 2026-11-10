"""Real HTTP preorder money checks; disposable loopback demo, synthetic data only."""
import argparse, copy, datetime as dt, http.cookiejar, json, os, secrets, time, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/figura/api');p.add_argument('--output',default='work/preorder-fulfillment-20261007/http-preorder_fulfillment.json');args=p.parse_args()
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

import hashlib,hmac

def fresh(c,o):return c.req(op(o))[1]['data']
def intent(c,o,body=None):
    r=c.req(op(o)+'/mock-payments','POST',body or dict(requestId=str(uuid.uuid4()),expectedOrderVersion=o['version']));assert r[0]==200,r;return r[1]['data']['payment']
def notice(p,outcome='SUCCESS'):return dict(eventId=str(uuid.uuid4()),paymentId=p['id'],transactionId='mock_'+p['id'],amountTwd=p['amountTwd'],currency='TWD',outcome=outcome)
def callback(n,ts=None,sig=None,raw=None,path='/preorder-payments/mock/callback'):
    raw=json.dumps(n,separators=(',',':')) if raw is None else raw;ts=str(int(time.time())) if ts is None else ts
    sig=hmac.new(os.environ['MOCK_PAYMENT_WEBHOOK_SECRET'].encode(),(ts+'.'+raw).encode(),hashlib.sha256).hexdigest() if sig is None else sig
    req=urllib.request.Request(args.base_url+path,data=raw.encode(),method='POST',headers={'Content-Type':'application/json','X-Mock-Timestamp':ts,'X-Mock-Signature':sig})
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=15) as r:return r.status,json.load(r)
    except urllib.error.HTTPError as e:return e.code,json.load(e)
def sp(o):return '/admin/preorder-orders/'+o['id']
def details(o):return admin.req(sp(o))[1]['data']
def refundbody(o):return dict(requestId=str(uuid.uuid4()),expectedOrderVersion=o['version'],note='Synthetic refund <script>plain text</script>')
def refund(o,body=None,payment=None):return admin.req(sp(o)+('/mock-review-refund/'+payment if payment else '/mock-refund'),'POST',body or refundbody(o))
def paid(c,o):
    p=intent(c,o);n=notice(p);assert callback(n)[0]==200;return fresh(c,o),p,n

def bp(b):return '/admin/preorder-arrivals/'+b['id']
def supply(b):return admin.req(bp(b))[1]['data']['supply']
def receipt(b,q):return dict(requestId=str(uuid.uuid4()),expectedBatchVersion=supply(b)['version'],quantity=q,note='Synthetic arrival <script>plain text</script>')
def allocation(o,b):return dict(requestId=str(uuid.uuid4()),expectedOrderVersion=details(o)['order']['version'],expectedBatchVersion=supply(b)['version'],note='Synthetic allocation')
def command(o):return dict(requestId=str(uuid.uuid4()),expectedOrderVersion=details(o)['order']['version'],note='Synthetic fulfillment')
def receive(b,q):
 r=admin.req(bp(b)+'/receive','POST',receipt(b,q));assert r[0]==200,r;return r

def allocate(o,b,body=None):return admin.req(sp(o)+'/allocate','POST',body or allocation(o,b))
def step(o,action,body=None):return admin.req(sp(o)+'/mock-'+action,'POST',body or command(o))
try:
 c=customer();d=customer();p,b=fixture(4);o=place(c,ready(c,b,2));before=stock(p)
 check('staff到貨清單及安全詳情',staff.req('/admin/preorder-arrivals')[0]==200 and staff.req(bp(b))[0]==200 and 'customerId' not in supply(b))
 check('顧客不能登記staff到貨',c.req(bp(b)+'/receive','POST',receipt(b,1))[0]==401)
 check('到貨POST需CSRF',admin.req(bp(b)+'/receive','POST',receipt(b,1),csrf=False)[0]==403)
 check('普通員工唯讀不能到貨',staff.req(bp(b)+'/receive','POST',receipt(b,1))[0]==403)
 cmd=receipt(b,1);check('未知欄位及小數拒絕',admin.req(bp(b)+'/receive','POST',cmd|{'actorId':1})[0]==400 and admin.req(bp(b)+'/receive','POST',cmd|{'quantity':1.5})[0]==400)
 assert admin.req(bp(b)+'/receive','POST',cmd)[0]==200
 check('到貨原UUID重播一次',admin.req(bp(b)+'/receive','POST',cmd)[1]['data']['replayed'] and supply(b)['receivedQuantity']==1)
 check('到貨同UUID改數量409',admin.req(bp(b)+'/receive','POST',cmd|{'quantity':2})[0]==409)
 check('未付款不能分配',allocate(o,b)[0]==409)
 o,pi,n=paid(c,o);check('付款後未分配不能出貨',step(o,'ship')[0]==409)
 check('不足整筆到貨不能分配',allocate(o,b)[0]==409)
 assert receive(b,1)[0]==200
 cmd=allocation(o,b);r=allocate(o,b,cmd);o=fresh(c,o)
 check('整筆分配狀態與獨立供應數量',r[0]==200 and o['arrivalStatus']=='ARRIVED' and o['fulfillment']['allocatedQuantity']==2 and supply(b)['unallocatedQuantity']==0)
 check('分配原UUID重播不再占用',allocate(o,b,cmd)[1]['data']['replayed'] and supply(b)['allocatedQuantity']==2)
 check('分配後不允許未到貨退款',refund(o)[0]==409)
 check('普通員工不能出貨',staff.req(sp(o)+'/mock-ship','POST',command(o))[0]==403)
 check('未出貨不能送達',step(o,'deliver')[0]==409)
 cmd=command(o);r=step(o,'ship',cmd);o=fresh(c,o)
 check('模擬出貨一次減獨立在庫而保持paid',r[0]==200 and o['status']=='PAID' and o['fulfillmentStatus']=='SHIPPED' and supply(b)['onHandQuantity']==0 and current(b)['paidQuantity']==2 and o['fulfillment']['trackingNumber'].startswith('mock_pre_'))
 tracking=o['fulfillment']['trackingNumber'];check('出貨原UUID重播不重扣',step(o,'ship',cmd)[1]['data']['replayed'] and supply(b)['shippedQuantity']==2)
 r=step(o,'deliver');o=fresh(c,o);check('送達只推進配送保留貨件及付款',r[0]==200 and o['fulfillmentStatus']=='DELIVERED' and o['fulfillment']['trackingNumber']==tracking and o['paidAt'] is not None)
 check('舊出貨重播返回目前送達狀態',step(o,'ship',cmd)[1]['data']['order']['fulfillmentStatus']=='DELIVERED')
 check('顧客履約safe projection及本人隔離','Synthetic' not in json.dumps(o) and 'actorUsername' not in o['fulfillment'] and d.req(op(o))[0]==404)
 check('到貨分配出貨不動現貨inventory',stock(p)==before)
 messages=admin.req('/admin/notifications?orderId='+o['id'])[1]['data']['items'];check('履約三類通知同transaction安全快照',{'PREORDER_ALLOCATED','PREORDER_SHIPPED','PREORDER_DELIVERED'}<= {m['eventKind'] for m in messages} and all(len(json.loads(admin.req('/admin/notifications/'+m['id'])[1]['data']['snapshot']))==6 for m in messages))
 check('累計到貨容量上限',admin.req(bp(b)+'/receive','POST',receipt(b,3))[0]==409)
 p,b2=fixture(2);x=place(c,ready(c,b2));failed=intent(c,x);callback(notice(failed,'FAILURE'));x,px,nx=paid(c,x);receive(b2,1);callback(notice(failed));x=fresh(c,x)
 check('晚到成功阻擋分配',allocate(x,b2)[0]==409 and details(x)['fulfillment']['canAllocate']==False)
 assert refund(x,payment=failed['id'])[0]==200;x=fresh(c,x);assert allocate(x,b2)[0]==200;assert step(x,'ship')[0]==200;assert step(x,'deliver')[0]==200
 check('核對後才能安全分配履約',details(x)['order']['fulfillmentStatus']=='DELIVERED')
 p,b3=fixture(2);x,px,nx=paid(c,place(c,ready(c,b3)));y,py,ny=paid(d,place(d,ready(d,b3)));receive(b3,1);cx=allocation(x,b3);cy=allocation(y,b3)
 with concurrent.futures.ThreadPoolExecutor(2) as pool:
  a=pool.submit(allocate,x,b3,cx);z=pool.submit(allocate,y,b3,cy);rs=[a.result()[0],z.result()[0]]
 check('最後到貨並行只分配一張單',sorted(rs)==[200,409] and supply(b3)['allocatedQuantity']==1)
 check('無憑空現貨訂單或付款snapshot更動',stock(p)['quantity']==0 and details(x)['order']['totalTwd']==1800)
 check('非法分頁參數拒絕',admin.req('/admin/preorder-arrivals?pageSize=51')[0]==400)
finally:
 for c,o in orders:
  v=details(o)
  for review in v['reviews']:
   if not review['refunded']:refund(v['order'],payment=review['paymentId']);v=details(o)
  if v['order']['arrivalStatus']=='ARRIVED':
   if v['order']['fulfillmentStatus']=='UNFULFILLED':step(v['order'],'ship');v=details(o)
   if v['order']['fulfillmentStatus']=='SHIPPED':step(v['order'],'deliver');v=details(o)
  elif v['order']['status']=='PAID':refund(v['order']);v=details(o)
  if v['order']['status']=='PENDING':cancel(c,v['order'])
 for bid in batches:
  b=current({'id':bid})
  if b['status']!='CLOSED':admin.req('/admin/preorders/'+bid+'/close','POST',dict(expectedVersion=b['version'],requestId=str(uuid.uuid4()),note='Synthetic cleanup'))
 for pid in products:
  p=admin.req('/admin/products/'+pid)[1]['data']
  if p['status']=='PUBLISHED':p=admin.req('/admin/products/'+pid+'/publication','PATCH',dict(expectedVersion=p['version'],published=False))[1]['data']
  if p['status']=='DRAFT':admin.req('/admin/products/'+pid+'?expectedVersion='+str(p['version']),'DELETE')
 admin.req('/admin/logout','POST');staff.req('/admin/logout','POST')
Path(args.output).parent.mkdir(parents=True,exist_ok=True);Path(args.output).write_text(json.dumps(dict(passed=len(checks),checks=checks,synthetic=True),ensure_ascii=False,indent=2))
print('Preorder fulfillment HTTP:',len(checks),'passed')
