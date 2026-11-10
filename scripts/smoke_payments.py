"""Real HTTP mock payment/callback smoke. Synthetic fixtures, loopback disposable demo only."""
import argparse, concurrent.futures, copy, http.cookiejar, json, os, secrets, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/figura/api');p.add_argument('--output',default='work/payments-20261007/http-payments.json');args=p.parse_args()
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
def body(version,qty):return dict(expectedVersion=version,quantity=qty)
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
    v=cart(c);assert c.req('/customer/cart/items/'+sku,'PUT',body(v['version'],qty))[0]==200
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
try:
    a=customer();b=customer();members.extend([a,b]);p,sku,inv=fixture(10);o=order(a,sku,2);payment,input=intent(a,o);path='/customer/orders/'+o['id']+'/mock-payments'
    check('付款建立只產生意圖不扣實體庫存',balance(inv)['quantity']==10 and balance(inv)['reserved']==2 and payment['amountTwd']==2560 and payment['provider']=='MOCK')
    check('匿名與員工不能讀本人付款',Client().req(path)[0]==401 and admin.req(path)[0]==401)
    check('外人已知訂單與付款ID仍不能讀或模擬',b.req(path)[0]==404 and b.req('/customer/mock-payments/'+payment['id']+'/simulate','POST',dict(eventId=str(uuid.uuid4()),outcome='SUCCESS'))[0]==404)
    check('顧客模擬仍需要CSRF',a.req('/customer/mock-payments/'+payment['id']+'/simulate','POST',dict(eventId=str(uuid.uuid4()),outcome='SUCCESS'),csrf=False)[0]==403)
    check('付款建立與模擬不接受客戶端價格',a.req(path,'POST',input|dict(amountTwd=1))[0]==400 and a.req('/customer/mock-payments/'+payment['id']+'/simulate','POST',dict(eventId=str(uuid.uuid4()),outcome='SUCCESS',amountTwd=1))[0]==400)
    r=a.req(path,'POST',input);check('同操作重送回同付款意圖',r[0]==200 and r[1]['data']['replayed'] and r[1]['data']['payment']['id']==payment['id'])
    check('同訂單不允許第二筆進行中付款',a.req(path,'POST',dict(requestId=str(uuid.uuid4()),expectedOrderVersion=1))[0]==409)
    event=notice(payment);check('錯誤簽章與過期時間不寫入',notify(event,sig='0'*64)[0]==401 and notify(event,ts='1000000000')[0]==401 and balance(inv)['quantity']==10)
    check('可信簽章仍核對金額與交易',notify(event|dict(amountTwd=1))[0]==409 and notify(event|dict(transactionId='mock_'+str(uuid.uuid4())))[0]==409)
    check('通知未知欄位與小數拒絕',notify(event|dict(owner=b.id))[0]==400 and notify(event|dict(amountTwd=1.5))[0]==400)
    r=notify(event);check('無Cookie無CSRF的真實簽章webhook完成付款',r[0]==200 and r[1]['data']['disposition']=='APPLIED')
    check('webhook回應不含收件與訂單內容','order' not in r[1]['data'] and 'shipping' not in r[1]['data'])
    check('付款一次扣實體與保留量',balance(inv)['quantity']==8 and balance(inv)['reserved']==0 and balance(inv)['available']==8)
    check('訂單已付款與paidAt保存',current(a,o)['status']=='PAID' and current(a,o)['paidAt'] is not None)
    r=notify(event);check('同通知重送不重複扣庫',r[0]==200 and r[1]['data']['replayed'] and balance(inv)['quantity']==8)
    check('同事件不同結果衝突',notify(event|dict(outcome='FAILURE'))[0]==409)
    check('新事件重複成功與晚到失敗不降級',notify(notice(payment))[1]['data']['disposition']=='ALREADY_SUCCEEDED' and notify(notice(payment,'FAILURE'))[1]['data']['disposition']=='ALREADY_SUCCEEDED' and current(a,o)['status']=='PAID')
    check('已付款不可直接取消',cancel(a,current(a,o))[0]==409 and balance(inv)['quantity']==8)
    second=order(a,sku,1);failed,_=intent(a,second);r=notify(notice(failed,'FAILURE'));check('失敗付款保留庫存且可建立新意圖',r[0]==200 and balance(inv)['quantity']==8 and balance(inv)['reserved']==1 and a.req('/customer/orders/'+second['id']+'/mock-payments')[1]['data']['canStart'])
    retry,_=intent(a,second);r=notify(notice(retry));check('失敗後新付款可成功且只扣一件',r[0]==200 and current(a,second)['status']=='PAID' and balance(inv)['quantity']==7 and balance(inv)['reserved']==0)
    late=order(b,sku,1);latePayment,_=intent(b,late);assert cancel(b,late)[0]==200;r=notify(notice(latePayment));check('取消後成功通知記待核對不復活',r[0]==200 and r[1]['data']['disposition']=='REVIEW_REQUIRED' and current(b,late)['status']=='CANCELLED' and balance(inv)['quantity']==7)
    raceOrder=order(b,sku,1);cp,_=intent(b,raceOrder);ce=notice(cp)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:notify(ce),range(2)))
    check('同通知競爭都成功且只扣一次',all(r[0]==200 for r in results) and balance(inv)['quantity']==6 and balance(inv)['reserved']==0)
    check('普通公開商品仍可售',Client().req('/catalog/products/'+p['id'])[1]['data']['skus'][0]['available'])
finally:
    for c in members:
        for o in c.req('/customer/orders')[1].get('data',{}).get('items',[]):
            if o['status']=='PENDING_PAYMENT':cancel(c,o)
    for p in products:
        pp='/admin/products/'+p['id'];v=admin.req(pp)[1].get('data')
        if v and v['status']=='PUBLISHED':v=admin.req(pp+'/publication','PATCH',dict(expectedVersion=v['version'],published=False))[1].get('data')
        if v and v['status']=='DRAFT':admin.req(pp+'?expectedVersion='+str(v['version']),'DELETE')
output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(dict(passed=len(checks),checks=checks),ensure_ascii=False,indent=2));print('Mock付款與通知真實HTTP通過',len(checks),'項')
