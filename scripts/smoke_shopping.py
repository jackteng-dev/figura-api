"""Real HTTP cart/address smoke. Synthetic fixtures, loopback disposable demo only."""
import argparse, concurrent.futures, copy, http.cookiejar, json, os, secrets, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/figura/api');p.add_argument('--output',default='work/shopping-20261007/http-shopping.json');args=p.parse_args()
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
a=customer();b=customer();anon=Client();product=None
try:
    r=admin.req('/admin/products','POST',dict(title='HTTP cart '+uuid.uuid4().hex,brand='Figura',category='公仔',description='合成購物車資料',expectedVersion=0,skus=[dict(code='HTTP-CART-'+uuid.uuid4().hex,label='標準款',priceTwd=1280,enabled=True)]));assert r[0]==201;product=r[1]['data'];path='/admin/products/'+product['id'];sku=product['skus'][0]['id'];item='/customer/cart/items/'+sku;inventory=path+'/skus/'+sku+'/inventory'
    assert admin.req(inventory+'/adjustments','POST',dict(requestId=str(uuid.uuid4()),expectedVersion=0,delta=10,reason='RESTOCK',note='合成入庫'))[0]==201
    product=admin.req(path+'/publication','PATCH',dict(expectedVersion=0,published=True))[1]['data']
    check('匿名與員工不能取得顧客購物車',anon.req('/customer/cart')[0]==401 and admin.req('/customer/cart')[0]==401)
    check('本人空購物車版本0',cart(a)['version']==0 and cart(a)['items']==[])
    check('加入缺CSRF拒絕',a.req(item,'PUT',body(0,2),csrf=False)[0]==403)
    r=a.req(item,'PUT',body(0,2));check('真實加入與伺服器計價',r[0]==200 and r[1]['data']['subtotalTwd']==2560)
    check('購物車不保留庫存或新增稽核',admin.req(inventory)[1]['data']['quantity']==10 and admin.req(inventory+'/adjustments')[1]['data']['total']==1)
    check('第二顧客與query owner不能取得第一人的購物車',b.req('/customer/cart?customerId='+a.id)[1]['data']['items']==[])
    check('第二顧客不能移除第一人款式',b.req(item+'?expectedVersion=0','DELETE')[0]==404 and cart(a)['items'][0]['quantity']==2)
    check('偽造價格與owner欄位400',a.req(item,'PUT',body(1,3)|dict(unitPriceTwd=1))[0]==400 and a.req(item,'PUT',body(1,3)|dict(customerId=b.id))[0]==400)
    check('數量0小數與超上限400',all(a.req(item,'PUT',body(1,q))[0]==400 for q in [0,1.5,100]))
    check('大於當下庫存409且不改版本',a.req(item,'PUT',body(1,11))[0]==409 and cart(a)['version']==1)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:statuses=list(pool.map(lambda q:a.req(item,'PUT',body(1,q))[0],[3,4]))
    check('同版本競爭只有一筆成功',sorted(statuses)==[200,409] and cart(a)['version']==2)
    qty=cart(a)['items'][0]['quantity'];check('重送舊版本不累加',a.req(item,'PUT',body(1,qty))[0]==409 and cart(a)['items'][0]['quantity']==qty)
    product=admin.req(path+'/publication','PATCH',dict(expectedVersion=product['version'],published=False))[1]['data']
    write={k:copy.deepcopy(product[k]) for k in ['title','brand','category','description','skus']};write['expectedVersion']=product['version'];write['title']='PRIVATE DRAFT';write['skus'][0]['priceTwd']=2500
    product=admin.req(path,'PUT',write)[1]['data'];v=cart(a);check('下架保留可移除快照不洩漏草稿且不計價',v['items'][0]['issue']=='UNAVAILABLE' and v['items'][0]['unitPriceTwd'] is None and 'PRIVATE DRAFT' not in json.dumps(v) and v['subtotalTwd']==0)
    check('下架禁止新增或更新數量',a.req(item,'PUT',body(2,1))[0]==409)
    product=admin.req(path+'/publication','PATCH',dict(expectedVersion=product['version'],published=True))[1]['data'];check('重上架按最新價格重算不採前端舊價格',cart(a)['subtotalTwd']==qty*2500)
    assert admin.req(inventory+'/adjustments','POST',dict(requestId=str(uuid.uuid4()),expectedVersion=1,delta=-10,reason='DAMAGE',note='合成缺貨測試'))[0]==201
    check('缺貨標示與可用小計重新核對',cart(a)['items'][0]['issue']=='SOLD_OUT' and cart(a)['subtotalTwd']==0)
    check('舊版本移除拒絕',a.req(item+'?expectedVersion=1','DELETE')[0]==409)
    check('缺貨仍可本人移除',a.req(item+'?expectedVersion=2','DELETE')[0]==200 and cart(a)['items']==[])
    aid=str(uuid.uuid4());ap='/customer/addresses/'+aid
    check('匿名與員工不能讀顧客地址',anon.req('/customer/addresses')[0]==401 and admin.req('/customer/addresses')[0]==401)
    r=a.req(ap,'PUT',address(0));check('真實保存地址安全投影',r[0]==200 and r[1]['data']['version']==1 and 'customerId' not in r[1]['data'])
    check('第二人query不能取得地址',b.req('/customer/addresses?customerId='+a.id)[1]['data']==[])
    check('外人地址PUT與DELETE都是404',b.req(ap,'PUT',address(0))[0]==404 and b.req(ap+'?expectedVersion=1','DELETE')[0]==404)
    check('地址owner注入與無效郵遞區號400',a.req(ap,'PUT',address(1)|dict(customerId=b.id))[0]==400 and a.req(ap,'PUT',address(1)|dict(postalCode='abcd'))[0]==400)
    check('地址更新持久保存',a.req(ap,'PUT',address(1)|dict(recipientName='更新收件人'))[0]==200 and a.req('/customer/addresses')[1]['data'][0]['recipientName']=='更新收件人')
    check('地址過期更新與刪除409',a.req(ap,'PUT',address(1))[0]==409 and a.req(ap+'?expectedVersion=1','DELETE')[0]==409)
    check('地址刪除缺CSRF403',a.req(ap+'?expectedVersion=2','DELETE',csrf=False)[0]==403)
    check('本人刪除後不再顯示',a.req(ap+'?expectedVersion=2','DELETE')[0]==200 and a.req('/customer/addresses')[1]['data']==[])
    check('刪除後不能重用ID繞過舊版本保護',a.req(ap,'PUT',address(0))[0]==409)
finally:
    if product:
        current=admin.req('/admin/products/'+product['id'])[1].get('data')
        if current and current['status']=='PUBLISHED':current=admin.req('/admin/products/'+product['id']+'/publication','PATCH',dict(expectedVersion=current['version'],published=False))[1].get('data')
        if current and current['status']=='DRAFT':admin.req('/admin/products/'+product['id']+'?expectedVersion='+str(current['version']),'DELETE')
    for c,path in [(a,'/customer/auth/logout'),(b,'/customer/auth/logout'),(admin,'/admin/logout')]:c.req(path,'POST')
out=Path(args.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(dict(passed=len(checks),checks=checks,transport='real loopback socket',syntheticAccountsCreated=2),ensure_ascii=False,indent=2)+'\n');print('購物車／地址 HTTP 驗證通過',len(checks),'項')
