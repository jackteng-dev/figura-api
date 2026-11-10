"""Real HTTP preorder money checks; disposable loopback demo, synthetic data only."""
import argparse, copy, datetime as dt, http.cookiejar, json, os, secrets, time, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/figura/api');p.add_argument('--output',default='work/preorder-money-20261007/http-preorder_money.json');args=p.parse_args()
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
try:
    c=customer();d=customer();p,b=fixture(4);o=place(c,ready(c,b,2));before=stock(p)
    check('獨立預購訂單員工清單與詳情',staff.req('/admin/preorder-orders')[0]==200 and staff.req(sp(o))[0]==200)
    row=next(x for x in admin.req('/admin/preorder-orders')[1]['data']['items'] if x['id']==o['id']);check('員工清單不暴露shipping或owner', 'shipping' not in row and 'customerId' not in row)
    check('顧客不能使用員工退款與查詢',c.req(sp(o))[0]==401 and c.req(sp(o)+'/mock-refund','POST',refundbody(o))[0]==401)
    start=dict(requestId=str(uuid.uuid4()),expectedOrderVersion=o['version']);check('本人付款需CSRF',c.req(op(o)+'/mock-payments','POST',start,csrf=False)[0]==403)
    check('別人付款與金額注入拒絕',d.req(op(o)+'/mock-payments','POST',start)[0]==404 and c.req(op(o)+'/mock-payments','POST',start|{'amountTwd':1})[0]==400)
    pay=intent(c,o,start);r=c.req(op(o)+'/mock-payments','POST',start);check('同UUID建立付款重播且server快照',r[0]==200 and r[1]['data']['replayed'] and r[1]['data']['payment']['id']==pay['id'] and pay['amountTwd']==3600 and 'transactionId' not in pay)
    check('單一有效意圖與過期版本',c.req(op(o)+'/mock-payments','POST',dict(requestId=str(uuid.uuid4()),expectedOrderVersion=1))[0]==409 and c.req(op(o)+'/mock-payments','POST',start|{'expectedOrderVersion':2})[0]==409)
    n=notice(pay);check('HMAC簽章與五分鐘窗口',callback(n,sig='0'*64)[0]==401 and callback(n,ts=str(int(time.time())-301))[0]==401)
    check('callback核對交易金額及未知欄位',callback(n|{'amountTwd':1})[0]==409 and callback(n|{'customerId':'spoof'})[0]==400 and callback(n|{'amountTwd':3600.5})[0]==400)
    check('現貨callback無法處理預購付款',callback(n,path='/payments/mock/callback')[0]==404)
    r=callback(n);o=fresh(c,o);check('成功held移到paid而不扣現貨',r[0]==200 and o['status']=='PAID' and current(b)['heldQuantity']==0 and current(b)['paidQuantity']==2 and stock(p)==before)
    check('付款後不允許未付款取消',cancel(c,o)[0]==409)
    check('同event重播不重扣或通知',callback(n)[1]['data']['replayed'] and len(details(o)['payments']['items'])==1 and current(b)['paidQuantity']==2)
    check('同event改outcome拒絕',callback(n|{'outcome':'FAILURE'})[0]==409)
    check('普通員工只能讀不能退款',staff.req(sp(o)+'/mock-refund','POST',refundbody(o))[0]==403)
    cmd=refundbody(o);r=refund(o,cmd);o=fresh(c,o);check('全額退款只釋放paid且保留付款',r[0]==200 and o['status']=='REFUNDED' and o['refund']['amountTwd']==3600 and current(b)['paidQuantity']==0 and stock(p)==before and details(o)['payments']['items'][0]['status']=='SUCCEEDED')
    check('原UUID退款重播一次',refund(o,cmd)[1]['data']['replayed'] and len(details(o)['refunds'])==1)
    check('退款UUID改note衝突',refund(o,cmd|{'note':'changed'})[0]==409)
    check('顧客退款投影無staff note actor', 'Synthetic refund' not in json.dumps(o) and 'actorUsername' not in json.dumps(c.req(op(o)+'/mock-payments')[1]['data']))
    check('付款callback退款後重播不復活',callback(n)[1]['data']['replayed'] and callback(notice(pay))[1]['data']['disposition']=='ALREADY_SUCCEEDED' and fresh(c,o)['status']=='REFUNDED' and current(b)['paidQuantity']==0)
    p,b2=fixture(1);x=place(c,ready(c,b2));px=intent(c,x);assert cancel(c,x)[0]==200;other=place(d,ready(d,b2));nx=notice(px);r=callback(nx);x=fresh(c,x)
    check('晚到成功待核對不回收已賣配額',r[1]['data']['disposition']=='REVIEW_REQUIRED' and x['status']=='CANCELLED' and x['paymentReviewRequired'] and current(b2)['heldQuantity']==1 and current(b2)['paidQuantity']==0)
    check('待核對保留原成功證據',any(e['outcome']=='SUCCESS' and e['disposition']=='REVIEW_REQUIRED' for e in details(x)['reviews'][0]['evidence']))
    cmd=refundbody(x);r=refund(x,cmd,px['id']);x=fresh(c,x);check('核對退款不改order終態或held',r[0]==200 and x['status']=='CANCELLED' and not x['paymentReviewRequired'] and current(b2)['heldQuantity']==1)
    check('核對退款UUID重播與callback保留',refund(x,cmd,px['id'])[1]['data']['replayed'] and callback(nx)[1]['data']['replayed'] and details(x)['reviews'][0]['refunded'])
    p,b3=fixture(2);f=place(c,ready(c,b3));pf=intent(c,f);assert callback(notice(pf,'FAILURE'))[0]==200;check('失敗釋放付款意圖但保留quota',c.req(op(f)+'/mock-payments')[1]['data']['canStart'] and current(b3)['heldQuantity']==1)
    second=intent(c,f);assert callback(notice(second))[0]==200;assert callback(notice(pf))[0]==200;f=fresh(c,f);check('已付款後異常成功阻擋整筆退款',f['status']=='PAID' and f['paymentReviewRequired'] and refund(f)[0]==409 and current(b3)['paidQuantity']==1)
    assert refund(f,payment=pf['id'])[0]==200;f=fresh(c,f);check('核對完才能退款原paid且只釋放一次',refund(f)[0]==200 and current(b3)['paidQuantity']==0)
    p,b4=fixture(1);raced=place(c,ready(c,b4));pr=intent(c,raced);nr=notice(pr)
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        one=pool.submit(callback,nr);two=pool.submit(cancel,c,raced);results=[one.result(),two.result()]
    raced=fresh(c,raced);check('付款取消並行只有一方結算',results[0][0]==200 and results[1][0] in [200,409] and current(b4)['heldQuantity']==0 and current(b4)['paidQuantity']==(1 if raced['status']=='PAID' else 0))
    messages=admin.req('/admin/notifications?orderId='+o['id'])[1]['data']['items'];check('付款退款outbox事件齊全且safe', {m['eventKind'] for m in messages}=={'PREORDER_PLACED','PREORDER_PAYMENT_SUCCEEDED','PREORDER_REFUNDED'} and all(len(json.loads(admin.req('/admin/notifications/'+m['id'])[1]['data']['snapshot']))==6 for m in messages))
    check('未知callback方法與simulate注入受保護',anon.req('/preorder-payments/mock/callback')[0]==401 and c.req('/customer/preorder-payments/'+pay['id']+'/simulate','POST',dict(eventId=str(uuid.uuid4()),outcome='SUCCESS',amountTwd=1))[0]==400)
    check('分頁與狀態上限',admin.req('/admin/preorder-orders?status=BAD')[0]==400 and admin.req('/admin/preorder-orders?pageSize=51')[0]==400)
finally:
    for c,o in orders:
        v=details(o)
        for review in v['reviews']:
            if not review['refunded']:
                refund(v['order'],payment=review['paymentId']);v=details(o)
        if v['order']['status']=='PAID':refund(v['order']);v=details(o)
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
print('Preorder money HTTP:',len(checks),'passed')
