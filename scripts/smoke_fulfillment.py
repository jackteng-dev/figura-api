"""Real HTTP staff orders/mock fulfillment smoke. Synthetic fixtures, loopback disposable demo only."""
import argparse, concurrent.futures, copy, http.cookiejar, json, os, secrets, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/ELPSY/api');p.add_argument('--output',default='work/fulfillment-20261007/http-fulfillment.json');args=p.parse_args()
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
try:
    a=customer();b=customer();members.extend([a,b]);p,sku,inv=fixture(12);o,payment=paid(a,order(a,sku,2));path=sp(o);before=balance(inv);body=change(o)
    check('匿名與顧客不能讀員工訂單',Client().req('/admin/orders')[0]==401 and a.req(path)[0]==401)
    r=admin.req('/admin/orders?status=PAID&fulfillmentStatus=UNFULFILLED');check('員工摘要分頁不回收件內容',r[0]==200 and all('shipping' not in x and 'customerId' not in x and 'items' not in x for x in r[1]['data']['items']))
    check('一般員工可讀詳情與稽核但無寫入權限',staff.req(path)[0]==200 and staff.req(path+'/fulfillment-events')[0]==200 and staff.req(path+'/mock-shipment','POST',body)[0]==403 and staff.req(path+'/mock-delivery','POST',body)[0]==403)
    check('出貨仍需要CSRF',admin.req(path+'/mock-shipment','POST',body,csrf=False)[0]==403)
    check('顧客不能直接模擬出貨',a.req(path+'/mock-shipment','POST',body)[0]==401)
    check('伺服器拒絕actor與貨件編號注入',admin.req(path+'/mock-shipment','POST',body|dict(actorId=1))[0]==400 and admin.req(path+'/mock-shipment','POST',body|dict(trackingId='forged'))[0]==400)
    check('空說明與小數版次拒絕',admin.req(path+'/mock-shipment','POST',body|dict(note=' '))[0]==400 and admin.req(path+'/mock-shipment','POST',body|dict(expectedOrderVersion=2.5))[0]==400)
    check('未出貨不可先送達',admin.req(path+'/mock-delivery','POST',body)[0]==409)
    r=admin.req(path+'/mock-shipment','POST',body);assert r[0]==200;shipped=r[1]['data']['current']['order'];tracking=shipped['fulfillment']['trackingId']
    check('實際出貨保持付款與快照並產生模擬貨件',shipped['status']=='PAID' and shipped['paidAt']==o['paidAt'] and shipped['version']==3 and shipped['shipping']==o['shipping'] and shipped['fulfillment']['status']=='SHIPPED' and tracking.startswith('MOCK-') and shipped['fulfillment']['shippedAt'] is not None)
    check('出貨不再扣庫或改庫存版本',balance(inv)==before)
    r=admin.req(path+'/mock-shipment','POST',body);check('同出貨操作重送一筆紀錄',r[0]==200 and r[1]['data']['replayed'] and len(admin.req(path+'/fulfillment-events')[1]['data'])==1)
    check('改說明與舊版次新操作衝突',admin.req(path+'/mock-shipment','POST',body|dict(note='changed'))[0]==409 and admin.req(path+'/mock-shipment','POST',body|dict(requestId=str(uuid.uuid4())))[0]==409)
    delivery=change(shipped);r=admin.req(path+'/mock-delivery','POST',delivery);assert r[0]==200;end=r[1]['data']['current']['order']
    check('送達保持同貨件付款與出貨時間',end['fulfillment']['status']=='DELIVERED' and end['version']==4 and end['fulfillment']['trackingId']==tracking and end['fulfillment']['shippedAt']==shipped['fulfillment']['shippedAt'] and end['fulfillment']['deliveredAt'] is not None and end['paidAt']==o['paidAt'] and balance(inv)==before)
    r=admin.req(path+'/mock-delivery','POST',delivery);check('送達重送不新增稽核',r[0]==200 and r[1]['data']['replayed'] and len(admin.req(path+'/fulfillment-events')[1]['data'])==2)
    r=admin.req(path+'/mock-shipment','POST',body);check('歷史出貨重送回原操作與目前送達狀態',r[0]==200 and r[1]['data']['event']['afterStatus']=='SHIPPED' and r[1]['data']['current']['order']['fulfillment']['status']=='DELIVERED')
    audit=staff.req(path+'/fulfillment-events')[1]['data'];check('稽核由認證員工與前後版本保存',audit[0]['actorUsername']=='shop_admin' and audit[0]['beforeVersion']==2 and audit[1]['beforeVersion']==3 and audit[1]['afterVersion']==4 and audit[0]['note']==body['note'])
    customerOrder=current(a,o);check('顧客配送投影不含內部說明或員工身份',customerOrder['fulfillment']==end['fulfillment'] and 'actorUsername' not in json.dumps(customerOrder) and body['note'] not in json.dumps(customerOrder,ensure_ascii=False))
    check('別人不能讀本人配送與付款',b.req('/customer/orders/'+o['id'])[0]==404 and b.req('/customer/orders/'+o['id']+'/mock-payments')[0]==404)
    check('履約稽核不能API刪改',admin.req(path+'/fulfillment-events','DELETE')[0]==403 and admin.req(path+'/fulfillment-events','PUT',body)[0]==403)
    check('非法篩選與分頁拒絕',admin.req('/admin/orders?pageSize=21')[0]==400 and admin.req('/admin/orders?fulfillmentStatus=FORGED')[0]==400)
    unpaid=order(a,sku,1);check('待付款不可出貨',admin.req(sp(unpaid)+'/mock-shipment','POST',change(unpaid))[0]==409);assert cancel(a,unpaid)[0]==200;closed=current(a,unpaid);check('取消不可出貨',admin.req(sp(closed)+'/mock-shipment','POST',change(closed))[0]==409)
    second,secondPayment=paid(a,order(a,sku,1));check('同ID不能套另一訂單且不留部分更新',admin.req(sp(second)+'/mock-shipment','POST',change(second)|dict(requestId=body['requestId']))[0]==409 and current(a,second)['fulfillment']['status']=='UNFULFILLED')
    one=change(second)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:admin.req(sp(second)+'/mock-shipment','POST',one),range(2)))
    check('相同出貨競爭只一次',all(r[0]==200 for r in results) and len(admin.req(sp(second)+'/fulfillment-events')[1]['data'])==1 and balance(inv)['quantity']==9)
    check('重複付款通知不重設配送',notify(notice(payment))[0]==200 and current(a,o)['fulfillment']['status']=='DELIVERED' and balance(inv)['quantity']==9)
finally:
    for c in members:
        for o in c.req('/customer/orders')[1].get('data',{}).get('items',[]):
            if o['status']=='PENDING_PAYMENT':cancel(c,o)
    for p in products:
        pp='/admin/products/'+p['id'];v=admin.req(pp)[1].get('data')
        if v and v['status']=='PUBLISHED':v=admin.req(pp+'/publication','PATCH',dict(expectedVersion=v['version'],published=False))[1].get('data')
        if v and v['status']=='DRAFT':admin.req(pp+'?expectedVersion='+str(v['version']),'DELETE')
output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(dict(passed=len(checks),checks=checks),ensure_ascii=False,indent=2));print('員工訂單與模擬履約真實HTTP通過',len(checks),'項')
