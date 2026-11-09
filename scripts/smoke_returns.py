"""Real HTTP mock shipped return smoke. Synthetic fixtures, loopback disposable demo only."""
import argparse, concurrent.futures, copy, http.cookiejar, json, os, secrets, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/ELPSY/api');p.add_argument('--output',default='work/returns-20261007/http-returns.json');args=p.parse_args()
assert urllib.parse.urlsplit(args.base_url).hostname in ['127.0.0.1','localhost']
checks=[]
def check(name,ok):
    assert ok,name
    checks.append(name)
class Client:
    def __init__(self):
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.token=self.req('/auth/csrf')[1]['data']['token']
    def req(self,path,method='GET',body=None,csrf=True,form=False):
        headers={}
        if method!='GET' and csrf:headers['X-XSRF-TOKEN']=self.token
        if body is not None:headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
        raw=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode() if body is not None else None
        try:
            with self.opener.open(urllib.request.Request(args.base_url+path,data=raw,method=method,headers=headers),timeout=15) as r:return r.status,json.load(r)
        except urllib.error.HTTPError as e:return e.code,json.load(e)
def customer():
    c=Client();name='shop_'+uuid.uuid4().hex[:20];password=secrets.token_urlsafe(24)
    r=c.req('/customer/auth/register','POST',dict(username=name,password=password,displayName='購物測試'));assert r[0]==201
    c.id=r[1]['data']['id'];assert c.req('/customer/auth/login','POST',dict(username=name,password=password,deviceId=str(uuid.uuid4()),rememberMe=False))[0]==200
    return c
def quantityInput(version,qty):return dict(expectedVersion=version,quantity=qty)
def address(version):return dict(expectedVersion=version,recipientName='合成收件人',phone='0912345678',postalCode='100',city='測試市',district='測試區',street='合成路 1 號')
def cart(c):return c.req('/customer/cart')[1]['data']
import hashlib,hmac,time
admin=Client();assert admin.req('/admin/login','POST',dict(username='shop_admin',password=os.environ['DEMO_ADMIN_PASSWORD'],deviceId=str(uuid.uuid4())),form=True)[0]==200
members=[];products=[]
def fixture(stock):
    r=admin.req('/admin/products','POST',dict(title='HTTP payment '+uuid.uuid4().hex,brand='ELPSY',category='公仔',description='合成付款資料',expectedVersion=0,skus=[dict(code='HTTP-PAY-'+uuid.uuid4().hex,label='標準款',priceTwd=1280,enabled=True)]));assert r[0]==201
    p=r[1]['data'];products.append(p);sku=p['skus'][0]['id'];inv='/admin/products/'+p['id']+'/skus/'+sku+'/inventory'
    assert admin.req(inv+'/adjustments','POST',dict(requestId=str(uuid.uuid4()),expectedVersion=0,delta=stock,reason='RESTOCK',note='合成付款入庫'))[0]==201
    assert admin.req('/admin/products/'+p['id']+'/publication','PATCH',dict(expectedVersion=0,published=True))[0]==200
    return p,sku,inv
def order(c,sku,qty):
    v=cart(c);assert c.req('/customer/cart/items/'+sku,'PUT',quantityInput(v['version'],qty))[0]==200
    aid=str(uuid.uuid4());assert c.req('/customer/addresses/'+aid,'PUT',address(0))[0]==200
    v=cart(c);r=c.req('/customer/orders','POST',dict(requestId=str(uuid.uuid4()),cartVersion=v['version'],addressId=aid,addressVersion=1,expectedSubtotalTwd=v['subtotalTwd']));assert r[0]==200;return r[1]['data']['order']
def intent(c,o):
    request=dict(requestId=str(uuid.uuid4()),expectedOrderVersion=o['version']);r=c.req('/customer/orders/'+o['id']+'/mock-payments','POST',request);assert r[0]==200;return r[1]['data']['payment'],request
def notice(p,outcome='SUCCESS'):return dict(eventId=str(uuid.uuid4()),paymentId=p['id'],transactionId='mock_'+p['id'],amountTwd=p['amountTwd'],currency='TWD',outcome=outcome)
def notify(input,ts=None,sig=None,raw=None):
    raw=json.dumps(input,separators=(',',':')) if raw is None else raw;ts=str(int(time.time())) if ts is None else ts
    signature=hmac.new(os.environ['MOCK_PAYMENT_WEBHOOK_SECRET'].encode(),(ts+'.'+raw).encode(),hashlib.sha256).hexdigest() if sig is None else sig
    req=urllib.request.Request(args.base_url+'/payments/mock/callback',data=raw.encode(),method='POST',headers={'Content-Type':'application/json','X-Mock-Timestamp':ts,'X-Mock-Signature':signature})
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req,timeout=15) as r:return r.status,json.load(r)
    except urllib.error.HTTPError as e:return e.code,json.load(e)
def balance(inv):return admin.req(inv)[1]['data']
def current(c,o):return c.req('/customer/orders/'+o['id'])[1]['data']
def cancel(c,o):return c.req('/customer/orders/'+o['id']+'/cancel','POST',dict(expectedVersion=o['version']))
staff=Client();assert staff.req('/admin/login','POST',dict(username='shop_staff',password=os.environ['DEMO_STAFF_PASSWORD'],deviceId=str(uuid.uuid4())),form=True)[0]==200
def sp(o):return '/admin/orders/'+o['id']
def change(o):return dict(requestId=str(uuid.uuid4()),expectedOrderVersion=o['version'],note='合成履約 <script>不應執行</script>')
def paid(c,o):
    payment,_=intent(c,o);assert notify(notice(payment))[0]==200
    return current(c,o),payment

def cp(o):return '/customer/orders/'+o['id']+'/mock-return'
def apply(o):return dict(requestId=str(uuid.uuid4()),expectedOrderVersion=o['version'],reason='合成整筆退貨 <img src=x onerror=alert(1)>')
def shipped(c,sku,qty,delivered=False):
    o,pay=paid(c,order(c,sku,qty));r=admin.req(sp(o)+'/mock-shipment','POST',change(o));assert r[0]==200;o=r[1]['data']['current']['order']
    if delivered:
        r=admin.req(sp(o)+'/mock-delivery','POST',change(o));assert r[0]==200;o=r[1]['data']['current']['order']
    return o,pay
def request(c,o,body=None):return c.req(cp(o),'POST',body or apply(o))
def action(o,stage,body):return admin.req(sp(o)+'/mock-return-'+stage,'POST',body)
try:
    a=customer();b=customer();members.extend([a,b]);p,sku,inv=fixture(12);o,pay=shipped(a,sku,2,True);before=balance(inv);initial=copy.deepcopy(o);body=apply(o)
    check('顧客只讀本人退貨',a.req(cp(o))[1]['data']['canRequest'] and b.req(cp(o))[0]==404 and b.req(cp(o),'POST',body)[0]==404)
    check('顧客與員工realm隔離',admin.req(cp(o),'POST',body)[0]==401 and a.req(sp(o)+'/returns')[0]==401)
    check('申請與驗收需要CSRF',a.req(cp(o),'POST',body,csrf=False)[0]==403 and admin.req(sp(o)+'/mock-return-receipt','POST',change(o)|dict(disposition='RESTOCK'),csrf=False)[0]==403)
    check('申請拒絕owner價格驗收注入',all(a.req(cp(o),'POST',body|{k:'forged'})[0]==400 for k in ['owner','amountTwd','disposition','actorId']))
    r=request(a,o,body);assert r[0]==200;o=r[1]['data']['order']
    check('整筆申請版本遞增不動庫存',o['version']==5 and o['returnProgress']['status']=='REQUESTED' and balance(inv)==before)
    check('申請安全投影純文字原因',o['returnProgress']['reason']==body['reason'] and 'actorUsername' not in json.dumps(o) and 'note' not in o['returnProgress'])
    check('申請重送同筆目前狀態',request(a,o,body)[1]['data']['replayed'] and len(admin.req(sp(o)+'/returns')[1]['data']['events'])==0)
    check('申請同ID改內容及新ID拒絕',request(a,o,body|dict(reason='different'))[0]==409 and request(a,o)[0]==409)
    receipt=change(o)|dict(disposition='RESTOCK')
    check('普通員工讀退貨而禁止全部處理',staff.req(sp(o)+'/returns')[0]==200 and all(staff.req(sp(o)+'/mock-return-'+stage,'POST',receipt if stage=='receipt' else change(o))[0]==403 for stage in ['receipt','rejection','refund']))
    check('未驗收不得退款',action(o,'refund',change(o))[0]==409)
    check('必須明確選擇驗收處置',action(o,'receipt',change(o))[0]==400 and action(o,'receipt',receipt|dict(disposition='FORGED'))[0]==400)
    check('舊版本驗收拒絕且不新增稽核',action(o,'receipt',receipt|dict(expectedOrderVersion=3))[0]==409 and len(admin.req(sp(o)+'/returns')[1]['data']['events'])==0)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:action(o,'receipt',receipt),range(2)))
    assert all(r[0]==200 for r in results);o=results[0][1]['data']['order']
    check('同驗收競爭一筆且不動庫存',o['version']==6 and balance(inv)==before and len(admin.req(sp(o)+'/returns')[1]['data']['events'])==1)
    check('驗收重送改處置拒絕',action(o,'receipt',receipt|dict(disposition='NO_RESTOCK'))[0]==409)
    refund=change(o)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:action(o,'refund',refund),range(2)))
    assert all(r[0]==200 for r in results);o=current(a,o)
    check('退款原配送付款與訂單快照保留',o['status']=='REFUNDED' and o['version']==7 and o['fulfillment']==initial['fulfillment'] and o['shipping']==initial['shipping'] and o['paidAt']==initial['paidAt'] and o['items']==initial['items'])
    check('可入庫退款只回補一次',balance(inv)['quantity']==12 and balance(inv)['reserved']==0 and balance(inv)['version']==before['version']+1 and len(admin.req(sp(o)+'/refunds')[1]['data']['events'])==1)
    check('兩種稽核同退款UUID',admin.req(sp(o)+'/returns')[1]['data']['events'][-1]['requestId']==refund['requestId'] and admin.req(sp(o)+'/refunds')[1]['data']['events'][0]['action']=='RETURN_REFUND')
    check('退款安全金額時間與驗收進度',o['refund']['amountTwd']==2560 and o['returnProgress']['status']=='REFUNDED' and o['returnProgress']['disposition']=='RESTOCK' and 'actorUsername' not in json.dumps(o))
    check('历史申請驗收重送不倒退',request(a,o,body)[1]['data']['order']['status']=='REFUNDED' and action(o,'receipt',receipt)[1]['data']['order']['returnProgress']['status']=='REFUNDED')
    check('已退款新操作拒絕且晚到通知不復活',action(o,'refund',change(o))[0]==409 and admin.req(sp(o)+'/mock-delivery','POST',change(o))[0]==409 and notify(notice(pay))[0]==200 and current(a,o)['status']=='REFUNDED' and balance(inv)['quantity']==12)
    check('退貨稽核禁止刪改',admin.req(sp(o)+'/returns','DELETE')[0]==403 and admin.req(sp(o)+'/returns','PUT',refund)[0]==403)
    no,_=shipped(a,sku,2);r=request(a,no);assert r[0]==200;no=r[1]['data']['order'];r=action(no,'receipt',change(no)|dict(disposition='NO_RESTOCK'));assert r[0]==200;no=r[1]['data']['order'];beforeNo=balance(inv);r=action(no,'refund',change(no));assert r[0]==200;no=r[1]['data']['order']
    check('不回補退款仍全額且實體保留版本完全不變',no['refund']['amountTwd']==2560 and balance(inv)==beforeNo and no['returnProgress']['disposition']=='NO_RESTOCK')
    rejected,_=shipped(a,sku,1);r=request(a,rejected);assert r[0]==200;rejected=r[1]['data']['order'];beforeReject=balance(inv)
    check('退貨處理中停止配送',not admin.req(sp(rejected))[1]['data']['canDeliver'] and admin.req(sp(rejected)+'/mock-delivery','POST',change(rejected))[0]==409)
    rejection=change(rejected);r=action(rejected,'rejection',rejection);assert r[0]==200;rejected=r[1]['data']['order']
    check('拒絕不退款不回補且恢復配送',rejected['returnProgress']['status']=='REJECTED' and balance(inv)==beforeReject and admin.req(sp(rejected))[1]['data']['canDeliver'] and len(admin.req(sp(rejected)+'/refunds')[1]['data']['events'])==0)
    check('拒絕可重送但演練不得再申請',action(rejected,'rejection',rejection)[1]['data']['replayed'] and request(a,rejected)[0]==409)
    r=admin.req(sp(rejected)+'/mock-delivery','POST',change(rejected));check('拒絕後正常模擬送達',r[0]==200 and r[1]['data']['current']['order']['fulfillment']['status']=='DELIVERED' and balance(inv)==beforeReject)
    unshipped,_=paid(a,order(a,sku,1));check('未出貨無退貨資格',not a.req(cp(unshipped))[1]['data']['canRequest'] and request(a,unshipped)[0]==409)
    pending=order(b,sku,1);check('未付款無退貨資格',not b.req(cp(pending))[1]['data']['canRequest'] and request(b,pending)[0]==409);assert cancel(b,pending)[0]==200

finally:
    for c in members:
        for o in c.req('/customer/orders')[1].get('data',{}).get('items',[]):
            if o['status']=='PENDING_PAYMENT':cancel(c,o)
    for p in products:
        pp='/admin/products/'+p['id'];v=admin.req(pp)[1].get('data')
        if v and v['status']=='PUBLISHED':v=admin.req(pp+'/publication','PATCH',dict(expectedVersion=v['version'],published=False))[1].get('data')
        if v and v['status']=='DRAFT':admin.req(pp+'?expectedVersion='+str(v['version']),'DELETE')
output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(dict(passed=len(checks),checks=checks),ensure_ascii=False,indent=2));print('已出貨退貨真實HTTP通過',len(checks),'項')
