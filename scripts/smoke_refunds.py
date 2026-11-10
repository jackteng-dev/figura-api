"""Real HTTP mock refund/review smoke. Synthetic fixtures, loopback disposable demo only."""
import argparse, concurrent.futures, copy, http.cookiejar, json, os, secrets, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/figura/api');p.add_argument('--output',default='work/refunds-20261007/http-refunds.json');args=p.parse_args()
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
    r=admin.req('/admin/products','POST',dict(title='HTTP payment '+uuid.uuid4().hex,brand='Figura',category='公仔',description='合成付款資料',expectedVersion=0,skus=[dict(code='HTTP-PAY-'+uuid.uuid4().hex,label='標準款',priceTwd=1280,enabled=True)]));assert r[0]==201
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
try:
    a=customer();b=customer();members.extend([a,b]);p,sku,inv=fixture(12);o,payment=paid(a,order(a,sku,2));path=sp(o);body=change(o);before=balance(inv)
    check('退款紀錄需要員工realm',Client().req(path+'/refunds')[0]==401 and a.req(path+'/refunds')[0]==401)
    check('普通員工可讀但不可退款',staff.req(path+'/refunds')[0]==200 and staff.req(path+'/mock-refund','POST',body)[0]==403)
    check('退款需要CSRF',admin.req(path+'/mock-refund','POST',body,csrf=False)[0]==403)
    check('金額與actor注入拒絕',admin.req(path+'/mock-refund','POST',body|dict(amountTwd=1))[0]==400 and admin.req(path+'/mock-refund','POST',body|dict(actorId=1))[0]==400)
    check('空說明與小數版本拒絕',admin.req(path+'/mock-refund','POST',body|dict(note=' '))[0]==400 and admin.req(path+'/mock-refund','POST',body|dict(expectedOrderVersion=2.5))[0]==400)
    r=admin.req(path+'/mock-refund','POST',body);assert r[0]==200;end=r[1]['data']['order']
    check('全額退款保持付款時間與快照',end['status']=='REFUNDED' and end['version']==3 and end['refund']['amountTwd']==2560 and end['paidAt']==o['paidAt'] and end['shipping']==o['shipping'] and end['items']==o['items'])
    check('回補實體不動保留庫存',balance(inv)['quantity']==12 and balance(inv)['reserved']==0 and balance(inv)['version']==before['version']+1)
    r=admin.req(path+'/mock-refund','POST',body);check('同ID重送不再回補',r[0]==200 and r[1]['data']['replayed'] and balance(inv)['quantity']==12 and len(admin.req(path+'/refunds')[1]['data']['events'])==1)
    check('改內容与新退款拒絕',admin.req(path+'/mock-refund','POST',body|dict(note='changed'))[0]==409 and admin.req(path+'/mock-refund','POST',change(end))[0]==409)
    check('顧客退款安全投影且隔離',current(a,o)['refund']==end['refund'] and 'actorUsername' not in json.dumps(current(a,o)) and b.req('/customer/orders/'+o['id'])[0]==404)
    check('已退款不可出貨與通知不復活',admin.req(path+'/mock-shipment','POST',change(end))[0]==409 and notify(notice(payment))[0]==200 and current(a,o)['status']=='REFUNDED' and balance(inv)['quantity']==12)
    check('退款紀錄禁止刪改',admin.req(path+'/refunds','DELETE')[0]==403 and admin.req(path+'/refunds','PUT',body)[0]==403)
    late=order(a,sku,1);lp,_=intent(a,late);assert cancel(a,late)[0]==200;assert notify(notice(lp))[0]==200;late=current(a,late);before=balance(inv);request=change(late);rp=sp(late)+'/mock-review-refund/'+lp['id']
    check('未處理review呈現',admin.req(sp(late)+'/refunds')[1]['data']['reviews'][0]['resolved']==False)
    check('普通員工不可核對退款',staff.req(rp,'POST',request)[0]==403)
    r=admin.req(rp,'POST',request);assert r[0]==200
    check('晚到成功核對退款不復活或動庫存',r[1]['data']['order']['status']=='CANCELLED' and r[1]['data']['current']['reviews'][0]['resolved'] and balance(inv)==before)
    check('核對重送保持一筆且原意圖保留',admin.req(rp,'POST',request)[1]['data']['replayed'] and len(admin.req(sp(late)+'/refunds')[1]['data']['events'])==1 and a.req('/customer/orders/'+late['id']+'/mock-payments')[1]['data']['items'][0]['status']=='REVIEW_REQUIRED')
    check('核對後新通知仍不動庫存',notify(notice(lp))[0]==200 and admin.req(sp(late)+'/refunds')[1]['data']['reviews'][0]['resolved'] and balance(inv)==before)
    extra=order(a,sku,2);first,_=intent(a,extra);assert notify(notice(first,'FAILURE'))[0]==200;primary,_=intent(a,extra);assert notify(notice(primary))[0]==200;assert notify(notice(first))[0]==200;extra=current(a,extra)
    check('矛盾付款暫停出貨與主訂單退款',admin.req(sp(extra)+'/mock-refund','POST',change(extra))[0]==409 and admin.req(sp(extra)+'/mock-shipment','POST',change(extra))[0]==409)
    request=change(extra);before=balance(inv);r=admin.req(sp(extra)+'/mock-review-refund/'+first['id'],'POST',request);assert r[0]==200;extra=r[1]['data']['order']
    check('核對额外付款不回補且解除凍結',balance(inv)==before and admin.req(sp(extra))[1]['data']['canShip'] and admin.req(sp(extra)+'/refunds')[1]['data']['canRefund'])
    request=change(extra)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:admin.req(sp(extra)+'/mock-refund','POST',request),range(2)))
    check('主付款同ID競爭只回補一次',all(r[0]==200 for r in results) and len(admin.req(sp(extra)+'/refunds')[1]['data']['events'])==2 and balance(inv)['quantity']==12)
    check('退款狀態可篩選',all(o['status']=='REFUNDED' for o in admin.req('/admin/orders?status=REFUNDED')[1]['data']['items']))
finally:
    for c in members:
        for o in c.req('/customer/orders')[1].get('data',{}).get('items',[]):
            if o['status']=='PENDING_PAYMENT':cancel(c,o)
    for p in products:
        pp='/admin/products/'+p['id'];v=admin.req(pp)[1].get('data')
        if v and v['status']=='PUBLISHED':v=admin.req(pp+'/publication','PATCH',dict(expectedVersion=v['version'],published=False))[1].get('data')
        if v and v['status']=='DRAFT':admin.req(pp+'?expectedVersion='+str(v['version']),'DELETE')
output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(dict(passed=len(checks),checks=checks),ensure_ascii=False,indent=2));print('員工訂單與模擬退款真實HTTP通過',len(checks),'項')
