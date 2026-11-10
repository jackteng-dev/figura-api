"""Native bearer real HTTP workflow in an explicitly disposable loopback demo.
Private fixture output is optional; tokens/passwords never enter the safe evidence.
"""
import argparse,datetime as dt,http.cookiejar,json,os,secrets,urllib.request,urllib.error,urllib.parse,uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',required=True);p.add_argument('--output',required=True);p.add_argument('--fixture');a=p.parse_args()
assert urllib.parse.urlsplit(a.base_url).hostname in ('127.0.0.1','localhost')
checks=[]
def check(name,ok):
 assert ok,name
 checks.append(name)
def uid():return str(uuid.uuid4())
class Client:
 def __init__(self):
  self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.token=None;self.csrf=None
 def call(self,path,method='GET',body=None,form=False):
  headers={}
  if self.token:headers['Authorization']='Bearer '+self.token
  if self.csrf and method!='GET':headers['X-XSRF-TOKEN']=self.csrf
  if body is not None:headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
  raw=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode() if body is not None else None
  try:
   with self.opener.open(urllib.request.Request(a.base_url+path,data=raw,method=method,headers=headers),timeout=20) as r:return r.status,json.load(r)['data']
  except urllib.error.HTTPError as e:return e.code,json.load(e)
 def ok(self,path,method='GET',body=None):
  s,d=self.call(path,method,body);assert s in (200,201),(path,s);return d
admin=Client();admin.csrf=admin.ok('/auth/csrf')['token'];s,_=admin.call('/admin/login','POST',dict(username='shop_admin',password=os.environ['DEMO_ADMIN_PASSWORD'],deviceId=uid()),True);assert s==200
anon=Client()
def customer():
 c=Client();name='app_'+uuid.uuid4().hex[:20];pw=secrets.token_urlsafe(24);c.ok('/mobile/customer/auth/register','POST',dict(username=name,password=pw,displayName='App 合成會員'));t=c.ok('/mobile/customer/auth/login','POST',dict(username=name,password=pw,deviceId=uid(),rememberMe=False));c.token=t['accessToken'];return c,name,pw,t
c,name,pw,t=customer();other,_,_,_=customer();live,liveName,livePassword,_=customer()
pdt=admin.ok('/admin/products','POST',dict(title='ELPSY 限定收藏模型',brand='ELPSY',category='公仔',description='App 串接驗證用合成收藏品',expectedVersion=0,skus=[dict(code='APP-'+uuid.uuid4().hex,label='標準款',priceTwd=1280,enabled=True)]));pid=pdt['id'];sku=pdt['skus'][0]['id'];inv='/admin/products/'+pid+'/skus/'+sku+'/inventory'
admin.ok(inv+'/adjustments','POST',dict(requestId=uid(),expectedVersion=0,delta=30,reason='RESTOCK',note='Synthetic App fixture'));admin.ok('/admin/products/'+pid+'/publication','PATCH',dict(expectedVersion=pdt['version'],published=True))
now=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).replace(microsecond=0)
batch=admin.ok('/admin/preorders','POST',dict(productId=pid,skuId=sku,name='ELPSY 秋季預購',description='整筆預購驗證',priceTwd=1800,capacity=30,deadlineAt=(now+dt.timedelta(days=1)).isoformat(),arrivalFrom=(now+dt.timedelta(days=30)).date().isoformat(),arrivalTo=(now+dt.timedelta(days=40)).date().isoformat(),expectedVersion=0,requestId=uid(),note='Synthetic App fixture'))['current'];batch=admin.ok('/admin/preorders/'+batch['id']+'/open','POST',dict(expectedVersion=batch['version'],requestId=uid(),note='Synthetic App fixture'))['current']
check('public native catalog and batch',anon.ok('/catalog/products/'+pid)['id']==pid and anon.ok('/catalog/preorders/'+batch['id'])['orderingEnabled'])
check('native profile, cart and address ignore injected owner query',c.ok('/mobile/customer/me?customerId=other')['id']==t['customer']['id'] and c.ok('/mobile/customer/cart')['items']==[])
addr=dict(expectedVersion=0,recipientName='合成收件人',phone='0912345678',postalCode='100',city='測試市',district='測試區',street='合成路 1 號');aid=uid();address=c.ok('/mobile/customer/addresses/'+aid,'PUT',addr)
check('address isolation',other.call('/mobile/customer/addresses/'+aid,'PUT',addr)[0]==404 and other.ok('/mobile/customer/addresses')==[])
cart=c.ok('/mobile/customer/cart/items/'+sku,'PUT',dict(quantity=2,expectedVersion=0));check('native versioned cart rejects stale update',c.call('/mobile/customer/cart/items/'+sku,'PUT',dict(quantity=3,expectedVersion=0))[0]==409)
body=dict(requestId=uid(),cartVersion=cart['version'],addressId=aid,addressVersion=address['version'],expectedSubtotalTwd=2560);stock=c.ok('/mobile/customer/orders','POST',body)['order']
check('stock placement replay same order and cleared cart',c.ok('/mobile/customer/orders','POST',body)['order']['id']==stock['id'] and c.ok('/mobile/customer/cart')['items']==[])
check('own order isolation',other.call('/mobile/customer/orders/'+stock['id'])[0]==404)
def pay(order,pre=False):
 res='preorders' if pre else 'orders';path='/mobile/customer/'+res+'/'+order['id']
 options=c.ok(path+'/mock-payments');assert options['enabled'] and options['canStart'];cmd=dict(requestId=uid(),expectedOrderVersion=order['version']);pi=c.ok(path+'/mock-payments','POST',cmd)['payment'];assert c.ok(path+'/mock-payments','POST',cmd)['payment']['id']==pi['id'];event=dict(eventId=uid(),outcome='SUCCESS');receipt=c.ok('/mobile/customer/'+('preorder-payments' if pre else 'mock-payments')+'/'+pi['id']+'/simulate','POST',event);assert c.ok('/mobile/customer/'+('preorder-payments' if pre else 'mock-payments')+'/'+pi['id']+'/simulate','POST',event)['replayed'];check(res+' native mock payment replay',receipt['order']['status']=='PAID');return receipt['order']
stock=pay(stock);check('stock payment consumes only reserved inventory',admin.ok(inv)['quantity']==28 and admin.ok(inv)['reserved']==0)
for action in ['ship','deliver']:
 order=admin.ok('/admin/orders/'+stock['id'])['order'];admin.ok('/admin/orders/'+stock['id']+('/mock-shipment' if action=='ship' else '/mock-delivery'),'POST',dict(requestId=uid(),expectedOrderVersion=order['version'],note='Synthetic App fulfillment'))
check('native stock safe delivery progress',c.ok('/mobile/customer/orders/'+stock['id'])['fulfillment']['status']=='DELIVERED')
prebody=dict(requestId=uid(),batchId=batch['id'],quantity=2,addressId=aid,addressVersion=1,expectedSubtotalTwd=3600);pre=c.ok('/mobile/customer/preorders','POST',prebody)['order'];check('native preorder placement separate replay',c.ok('/mobile/customer/preorders','POST',prebody)['order']['id']==pre['id']);pre=pay(pre,True)
supply=admin.ok('/admin/preorder-arrivals/'+batch['id'])['supply'];admin.ok('/admin/preorder-arrivals/'+batch['id']+'/receive','POST',dict(requestId=uid(),expectedBatchVersion=supply['version'],quantity=2,note='Synthetic App arrival'));supply=admin.ok('/admin/preorder-arrivals/'+batch['id'])['supply'];pre=admin.ok('/admin/preorder-orders/'+pre['id'])['order'];admin.ok('/admin/preorder-orders/'+pre['id']+'/allocate','POST',dict(requestId=uid(),expectedOrderVersion=pre['version'],expectedBatchVersion=supply['version'],note='Synthetic App allocation'))
for action in ['ship','deliver']:
 pre=admin.ok('/admin/preorder-orders/'+pre['id'])['order'];admin.ok('/admin/preorder-orders/'+pre['id']+'/mock-'+action,'POST',dict(requestId=uid(),expectedOrderVersion=pre['version'],note='Synthetic App fulfillment'))
v=c.ok('/mobile/customer/preorders/'+pre['id']);check('native safe preorder allocated delivered projection',v['fulfillmentStatus']=='DELIVERED' and v['fulfillment']['allocatedQuantity']==2 and 'note' not in v['fulfillment']);check('preorder supply never decrements stock',admin.ok(inv)['quantity']==28)
prebody=prebody|dict(requestId=uid(),quantity=1,expectedSubtotalTwd=1800);cancel=c.ok('/mobile/customer/preorders','POST',prebody)['order'];cmd=dict(requestId=uid(),expectedVersion=cancel['version']);check('native preorder cancel replay terminal',c.ok('/mobile/customer/preorders/'+cancel['id']+'/cancel','POST',cmd)['order']['status']=='CANCELLED' and c.ok('/mobile/customer/preorders/'+cancel['id']+'/cancel','POST',cmd)['replayed'])
check('native refund and return options remain safe',c.ok('/mobile/customer/orders/'+stock['id']+'/mock-return')['enabled'])
check('website still needs csrf even with native bearer',c.call('/customer/orders','POST',body)[0]==403)
check('native token not accepted by employee endpoint',c.call('/admin/orders')[0]==401)
rotated=c.ok('/mobile/customer/auth/refresh','POST',{'refreshToken':t['refreshToken']});check('old access revoked after rotation',c.call('/mobile/customer/me')[0]==401);c.token=rotated['accessToken'];c.ok('/mobile/customer/auth/logout','POST',{'refreshToken':rotated['refreshToken']});check('native logout revokes access',c.call('/mobile/customer/me')[0]==401)
if a.fixture:
 path=Path(a.fixture);assert 'work' in path.parts;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(dict(API_BASE_URL=a.base_url+'/',APP_TEST_USERNAME=liveName,APP_TEST_PASSWORD=livePassword,APP_TEST_PRODUCT=pid,APP_TEST_SKU=sku,APP_TEST_BATCH=batch['id']),ensure_ascii=False));path.chmod(0o600)
admin.ok('/admin/logout','POST');Path(a.output).parent.mkdir(parents=True,exist_ok=True);Path(a.output).write_text(json.dumps({'passed':len(checks),'checks':checks,'synthetic':True,'transport':'loopback real socket'},ensure_ascii=False,indent=2));print('Native HTTP workflow:',len(checks),'passed')
