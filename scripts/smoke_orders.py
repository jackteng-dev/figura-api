"""Real HTTP order/reservation smoke. Synthetic fixtures, loopback disposable demo only."""
import argparse, concurrent.futures, copy, http.cookiejar, json, os, secrets, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/figura/api');p.add_argument('--output',default='work/orders-20261007/http-orders.json');args=p.parse_args()
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
admin=Client();assert admin.req('/admin/login','POST',dict(username='shop_admin',password=os.environ['DEMO_ADMIN_PASSWORD'],deviceId=str(uuid.uuid4())),form=True)[0]==200
products=[];members=[]
def fixture(stock):
    r=admin.req('/admin/products','POST',dict(title='HTTP order '+uuid.uuid4().hex,brand='ELPSY',category='公仔',description='合成訂單資料',expectedVersion=0,skus=[dict(code='HTTP-ORDER-'+uuid.uuid4().hex,label='標準款',priceTwd=1280,enabled=True)]));assert r[0]==201
    product=r[1]['data'];products.append(product);pp='/admin/products/'+product['id'];sku=product['skus'][0]['id'];inv=pp+'/skus/'+sku+'/inventory'
    assert admin.req(inv+'/adjustments','POST',dict(requestId=str(uuid.uuid4()),expectedVersion=0,delta=stock,reason='RESTOCK',note='合成訂單入庫'))[0]==201
    assert admin.req(pp+'/publication','PATCH',dict(expectedVersion=0,published=True))[0]==200
    return product,sku,inv
def ready(c,sku,qty):
    assert c.req('/customer/cart/items/'+sku,'PUT',body(cart(c)['version'],qty))[0]==200
    aid=str(uuid.uuid4());assert c.req('/customer/addresses/'+aid,'PUT',address(0))[0]==200
    return dict(requestId=str(uuid.uuid4()),cartVersion=cart(c)['version'],addressId=aid,addressVersion=1,expectedSubtotalTwd=cart(c)['subtotalTwd'])
def balance(inv):return admin.req(inv)[1]['data']
def cancel(c,o):return c.req('/customer/orders/'+o['id']+'/cancel','POST',dict(expectedVersion=o['version']))
try:
    a=customer();b=customer();members.extend([a,b]);anon=Client();product,sku,inv=fixture(5);input=ready(a,sku,2)
    check('匿名與員工不能取得顧客訂單',anon.req('/customer/orders')[0]==401 and admin.req('/customer/orders')[0]==401)
    check('下單缺CSRF拒絕',a.req('/customer/orders','POST',input,csrf=False)[0]==403)
    check('owner與價格欄位注入拒絕',a.req('/customer/orders','POST',input|dict(customerId=b.id))[0]==400 and a.req('/customer/orders','POST',input|dict(shippingTwd=999))[0]==400)
    check('未核對的價格拒絕',a.req('/customer/orders','POST',input|dict(expectedSubtotalTwd=1))[0]==409 and balance(inv)['reserved']==0)
    check('外人地址不可下單',b.req('/customer/cart/items/'+sku,'PUT',body(0,1))[0]==200 and b.req('/customer/orders','POST',input|dict(cartVersion=1,expectedSubtotalTwd=1280))[0]==404)
    r=a.req('/customer/orders','POST',input);check('下單實際保存與伺服器價格快照',r[0]==200 and r[1]['data']['order']['totalTwd']==2560 and r[1]['data']['order']['shippingTwd']==0);order=r[1]['data']['order']
    check('保留量與可售量分離且不扣實體庫存',balance(inv)['quantity']==5 and balance(inv)['reserved']==2 and balance(inv)['available']==3)
    check('成功下單清空本人購物車並增加版本',cart(a)['items']==[] and cart(a)['version']==2)
    check('第二顧客訂單列表隔離',b.req('/customer/orders?customerId='+a.id)[1]['data']['items']==[])
    check('外人明確ID讀取取消皆404',b.req('/customer/orders/'+order['id'])[0]==404 and cancel(b,order)[0]==404)
    r=a.req('/customer/orders','POST',input);check('遺失回應可用相同操作重送不再保留',r[0]==200 and r[1]['data']['replayed'] and r[1]['data']['order']['id']==order['id'] and balance(inv)['reserved']==2)
    check('相同編號不同内容拒絕',a.req('/customer/orders','POST',input|dict(expectedSubtotalTwd=100))[0]==409)
    check('後台不能把庫存調低到保留量以下',admin.req(inv+'/adjustments','POST',dict(requestId=str(uuid.uuid4()),expectedVersion=balance(inv)['version'],delta=-4,reason='DAMAGE',note='合成保留保護'))[0]==409)
    check('取消舊版本先拒絕不釋放',a.req('/customer/orders/'+order['id']+'/cancel','POST',dict(expectedVersion=2))[0]==409 and balance(inv)['reserved']==2)
    check('刪除本人地址後訂單收件快照仍保存',a.req('/customer/addresses/'+input['addressId']+'?expectedVersion=1','DELETE')[0]==200 and a.req('/customer/orders/'+order['id'])[1]['data']['shipping']==order['shipping'])
    check('本人取消釋放保留且實體不變',cancel(a,order)[0]==200 and balance(inv)['reserved']==0 and balance(inv)['quantity']==5)
    check('取消重送不重複釋放',cancel(a,order)[0]==200 and balance(inv)['reserved']==0)
    r=a.req('/customer/orders','POST',input);check('取消後下單重送回目前終態不復活',r[0]==200 and r[1]['data']['order']['status']=='CANCELLED' and balance(inv)['reserved']==0)
    check('分頁界限與非法ID拒絕',a.req('/customer/orders?pageSize=21')[0]==400 and a.req('/customer/orders/bad')[0]==400)
    product2,sku2,inv2=fixture(1);c=customer();d=customer();members.extend([c,d]);one=ready(c,sku2,1);two=ready(d,sku2,1)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(client.req,'/customer/orders','POST',payload) for client,payload in [(c,one),(d,two)]];results=[f.result() for f in futures]
    check('最後一件庫存競爭只能一人下單',sorted(r[0] for r in results)==[200,409] and balance(inv2)['reserved']==1)
    loser=d if results[0][0]==200 else c;check('失敗者車保留但顯示缺貨',cart(loser)['items'][0]['issue']=='SOLD_OUT')
    public=anon.req('/catalog/products/'+product2['id'])[1]['data'];check('公開商品依可售庫存顯示缺貨',not public['skus'][0]['available'])
    for client,result in zip([c,d],results):
        if result[0]==200:assert cancel(client,result[1]['data']['order'])[0]==200
    check('釋放後公開商品恢復可售',anon.req('/catalog/products/'+product2['id'])[1]['data']['skus'][0]['available'])
    same=ready(c,sku2,1)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:c.req('/customer/orders','POST',same),range(2)))
    check('同操作競爭回同一訂單且只保留一次',all(r[0]==200 for r in results) and results[0][1]['data']['order']['id']==results[1][1]['data']['order']['id'] and balance(inv2)['reserved']==1)
finally:
    for c in members:
        for o in c.req('/customer/orders')[1].get('data',{}).get('items',[]):
            if o['status']=='PENDING_PAYMENT':cancel(c,o)
    for p in products:
        current=admin.req('/admin/products/'+p['id'])[1].get('data')
        if current and current['status']=='PUBLISHED':current=admin.req('/admin/products/'+p['id']+'/publication','PATCH',dict(expectedVersion=current['version'],published=False))[1].get('data')
        if current and current['status']=='DRAFT':admin.req('/admin/products/'+p['id']+'?expectedVersion='+str(current['version']),'DELETE')
output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(dict(passed=len(checks),checks=checks),ensure_ascii=False,indent=2));print('訂單真實HTTP通過',len(checks),'項')
