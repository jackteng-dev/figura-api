"""Real HTTP notification outbox smoke. Synthetic fixtures, loopback disposable demo only."""
import argparse, concurrent.futures, copy, http.cookiejar, json, os, secrets, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/figura/api');p.add_argument('--output',default='work/outbox-20261007/http-notifications.json');args=p.parse_args()
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
def messages(o):return admin.req('/admin/notifications?orderId='+o['id'])[1]['data']['items']
def mid(o,kind):return next(n['id'] for n in messages(o) if n['eventKind']==kind)
def detail(id):return admin.req('/admin/notifications/'+id)[1]['data']
def command(id,mode='SUCCESS'):return dict(requestId=str(uuid.uuid4()),expectedVersion=detail(id)['message']['version'],note='合成通知 <img src=x onerror=alert(1)>',mode=mode)
def dispatch(id,b):return admin.req('/admin/notifications/'+id+'/mock-dispatch','POST',b)
def retry(id):
    b=command(id);del b['mode'];return admin.req('/admin/notifications/'+id+'/retry','POST',b),b
try:
    a=customer();members.append(a);p,sku,inv=fixture(15);o=order(a,sku,1);id=mid(o,'ORDER_PLACED');d=detail(id)
    check('下單佇列與私有個資最小化',d['message']['status']=='PENDING' and set(json.loads(d['snapshot']))=={'orderId','eventKind','orderVersion','orderStatus','fulfillmentStatus','totalTwd'} and not any(x in json.dumps(d) for x in ['recipientRef','fingerprint','0912345678']))
    check('顧客與員工realm隔離',a.req('/admin/notifications')[0]==401)
    check('普通員工可讀不得派送重排',staff.req('/admin/notifications/'+id)[0]==200 and staff.req('/admin/notifications/'+id+'/mock-dispatch','POST',command(id))[0]==403 and staff.req('/admin/notifications/'+id+'/retry','POST',command(id))[0]==403)
    check('通知寫入CSRF保護',admin.req('/admin/notifications/'+id+'/mock-dispatch','POST',command(id),csrf=False)[0]==403)
    check('不接受收件人注入或外部模式',dispatch(id,command(id)|dict(recipient='forged'))[0]==400 and dispatch(id,command(id,'EMAIL'))[0]==400)
    check('版本冲突沒有派送',dispatch(id,command(id)|dict(expectedVersion=99))[0]==409 and not detail(id)['message']['mockAccepted'])
    b=command(id,'FAIL_AFTER_ACCEPT');r=dispatch(id,b);d=detail(id)
    check('未知結果保留独立mock接受及重試佇列',r[0]==200 and d['message']['status']=='RETRY' and d['message']['mockAccepted'] and d['message']['lastError']=='MOCK_RESULT_UNKNOWN' and d['events'][0]['result']=='MOCK_RESULT_UNKNOWN')
    check('原UUID重播不增加嘗試',dispatch(id,b)[1]['data']['replayed'] and detail(id)['message']['attempts']==1)
    check('同UUID改原因拒絕',dispatch(id,b|dict(note='different'))[0]==409)
    check('退避中拒絕新派送',dispatch(id,command(id))[0]==409)
    r,rb=retry(id);check('人工重排稽核及原UUID重播',r[0]==200 and r[1]['data']['event']['result']=='REQUEUED' and admin.req('/admin/notifications/'+id+'/retry','POST',rb)[1]['data']['replayed'])
    r=dispatch(id,command(id));d=detail(id);check('provider去重接受後才確認SENT',r[0]==200 and d['message']['status']=='SENT' and d['message']['attempts']==2 and d['events'][-1]['result']=='ACCEPTED_REPLAY')
    check('mock通知不改訂單與庫存',current(a,o)['status']=='PENDING_PAYMENT' and balance(inv)['reserved']==1)
    check('SENT不得再發送或重排',dispatch(id,command(id))[0]==409 and retry(id)[0][0]==409)
    other=order(a,sku,1);oid=mid(other,'ORDER_PLACED');check('全域命令ID跨通知拒絕且未接受',dispatch(oid,b)[0]==409 and not detail(oid)['message']['mockAccepted'])
    # Same original command is shared intentionally; both replies have one committed attempt.
    once=command(oid)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:rs=list(pool.map(lambda _:dispatch(oid,once),range(2)))
    check('併行同UUID一筆稽核接受',all(r[0]==200 for r in rs) and detail(oid)['message']['attempts']==1 and len(detail(oid)['events'])==1)
    third=order(a,sku,1);tid=mid(third,'ORDER_PLACED');r=dispatch(tid,command(tid,'FAIL_BEFORE'));check('接受前故障保留可重試且無receipt',r[0]==200 and detail(tid)['message']['status']=='RETRY' and not detail(tid)['message']['mockAccepted'])
    check('分頁／狀態／訂單ID驗證',all(admin.req('/admin/notifications'+q)[0]==400 for q in ['?page=0','?pageSize=51','?status=BAD','?orderId=bad']))
    check('唯讀API不產生派送',len(detail(id)['events'])==3 and admin.req('/admin/notifications/'+id,'DELETE')[0]==403 and admin.req('/admin/notifications/'+id,'PUT',rb)[0]==403)
    o,payment=paid(a,o);n=notice(payment);notify(n);notify(n);check('付款回調重送不重複業務通知',len(messages(o))==2 and json.loads(detail(mid(o,'PAYMENT_SUCCEEDED'))['snapshot'])['orderStatus']=='PAID')
    o2,payment2=shipped(a,sku,1,True);r=request(a,o2);o2=r[1]['data']['order'];r=action(o2,'receipt',change(o2)|dict(disposition='RESTOCK'));o2=r[1]['data']['order'];r=action(o2,'refund',change(o2));o2=r[1]['data']['order'];check('出貨送達退貨退款七種事件保留',len(messages(o2))==7 and set(x['eventKind'] for x in messages(o2))=={'ORDER_PLACED','PAYMENT_SUCCEEDED','ORDER_SHIPPED','ORDER_DELIVERED','RETURN_REQUESTED','RETURN_RECEIVED','ORDER_REFUNDED'})
    r=admin.req(sp(o)+'/mock-refund','POST',change(o));check('未出貨退款通知',r[0]==200 and bool(mid(o,'ORDER_REFUNDED')))
    check('篩選只含本人指定訂單與狀態',all(x['orderId']==o['id'] for x in messages(o)) and all(x['status']=='RETRY' for x in admin.req('/admin/notifications?status=RETRY')[1]['data']['items']))
    out=Path(args.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(dict(passed=len(checks),checks=checks),ensure_ascii=False,indent=2));print('通知 HTTP 通過',len(checks),'項')
finally:
    for c in members:
        for item in c.req('/customer/orders?pageSize=20')[1]['data']['items']:
            if item['status']=='PENDING_PAYMENT':cancel(c,item)
    for p in products:
        v=admin.req('/admin/products/'+p['id'])[1]['data']
        if v['status']=='PUBLISHED':v=admin.req('/admin/products/'+p['id']+'/publication','PATCH',dict(expectedVersion=v['version'],published=False))[1]['data']
        if v['status']=='DRAFT':admin.req('/admin/products/'+p['id']+'?expectedVersion='+str(v['version']),'DELETE')
