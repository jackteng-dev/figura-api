"""Real HTTP preorder-batch checks; disposable loopback demo, synthetic data only."""
import argparse, copy, datetime as dt, http.cookiejar, json, os, secrets, time, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/figura/api');p.add_argument('--output',default='work/preorders-20261007/http-preorders.json');args=p.parse_args()
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
admin=login(True);staff=login(False);anon=Client();products=[];batches=[]
def product():
    b=dict(title='Preorder HTTP '+uuid.uuid4().hex,brand='Figura',category='公仔',description='合成預購商品',expectedVersion=0,skus=[dict(code='PRE-'+uuid.uuid4().hex,label='限定款',priceTwd=1280,enabled=True),dict(code='OFF-'+uuid.uuid4().hex,label='停用款',priceTwd=1800,enabled=False)])
    r=admin.req('/admin/products','POST',b);assert r[0]==201;v=r[1]['data'];products.append(v['id']);return v
def write(p,seconds=86400):
    now=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).replace(microsecond=0)
    return dict(productId=p['id'],skuId=next(s['id'] for s in p['skus'] if s['enabled']),name='Batch '+uuid.uuid4().hex,description='<img src=x onerror=alert(1)>',priceTwd=1800,capacity=12,deadlineAt=(now+dt.timedelta(seconds=seconds)).isoformat(),arrivalFrom=(now+dt.timedelta(days=30)).date().isoformat(),arrivalTo=(now+dt.timedelta(days=40)).date().isoformat(),expectedVersion=0,requestId=str(uuid.uuid4()),note='HTTP 合成批次')
def path(b):return '/admin/preorders/'+b['id']
def public(b):return '/catalog/preorders/'+b['id']
def current(b):return admin.req(path(b))[1]['data']['batch']
def command(b):return dict(expectedVersion=b['version'],requestId=str(uuid.uuid4()),note='HTTP 批次操作')
def create(p,seconds=86400):
    w=write(p,seconds);r=admin.req('/admin/preorders','POST',w);assert r[0]==201;b=r[1]['data']['current'];batches.append(b);return b,w
try:
    check('匿名可查預購列表',anon.req('/catalog/preorders')[0]==200)
    check('匿名不能查員工批次',anon.req('/admin/preorders')[0]==401)
    p=product();w=write(p)
    check('普通員工唯讀',staff.req('/admin/preorders')[0]==200 and staff.req('/admin/preorders','POST',w)[0]==403)
    check('CSRF 拒絕未驗證寫入',admin.req('/admin/preorders','POST',w,csrf=False)[0]==403)
    check('不能注入配額使用量',admin.req('/admin/preorders','POST',w|{'heldQuantity':1})[0]==400)
    check('拒絕浮點價格',admin.req('/admin/preorders','POST',w|{'priceTwd':1800.5})[0]==400)
    check('拒絕錯誤到貨區間',admin.req('/admin/preorders','POST',w|{'arrivalTo':'2000-01-01'})[0]==400)
    check('不能使用停用 SKU',admin.req('/admin/preorders','POST',w|{'skuId':next(s['id'] for s in p['skus'] if not s['enabled'])})[0]==409)
    q=product();check('SKU 不能跨商品',admin.req('/admin/preorders','POST',w|{'skuId':next(s['id'] for s in q['skus'] if s['enabled'])})[0]==409)
    b,w=create(p);r=admin.req('/admin/preorders','POST',w)
    check('同 UUID 重試同批次不重複',r[0]==200 and r[1]['data']['replayed'] and r[1]['data']['current']['id']==b['id'])
    check('同 UUID 改內容拒絕',admin.req('/admin/preorders','POST',w|{'capacity':13})[0]==409)
    check('草稿公開查詢404',anon.req(public(b))[0]==404)
    check('未上架商品不能開放',admin.req(path(b)+'/open','POST',command(b))[0]==409)
    r=admin.req('/admin/products/'+p['id']+'/publication','PATCH',dict(expectedVersion=p['version'],published=True));assert r[0]==200;p=r[1]['data']
    inv='/admin/products/'+p['id']+'/skus/'+b['skuId']+'/inventory';stock=admin.req(inv)[1]['data']
    check('普通員工不能開放',staff.req(path(b)+'/open','POST',command(b))[0]==403)
    cmd=command(b);r=admin.req(path(b)+'/open','POST',cmd);b=r[1]['data']['current'];v=anon.req(public(b))[1]['data']
    check('開放真實批次且配額独立現貨',r[0]==201 and b['status']=='OPEN' and v['availableQuota']==12 and stock['quantity']==0 and v['orderingEnabled'])
    check('公開安全投影',set(v)=={'id','productId','name','description','productTitle','skuLabel','priceTwd','availableQuota','deadlineAt','arrivalFrom','arrivalTo','status','orderingEnabled'})
    check('截止時間有台北 offset',v['deadlineAt'].endswith('+08:00'))
    check('開放後禁止改價配額',admin.req(path(b),'PUT',w|{'expectedVersion':b['version'],'requestId':str(uuid.uuid4()),'capacity':13})[0]==409)
    check('過期版本截單409',admin.req(path(b)+'/close','POST',command(b)|{'expectedVersion':0})[0]==409)
    p=admin.req('/admin/products/'+p['id']+'/publication','PATCH',dict(expectedVersion=p['version'],published=False))[1]['data']
    check('下架立即隱藏預購',anon.req(public(b))[0]==404)
    pb={k:copy.deepcopy(p[k]) for k in ['title','brand','category','description','skus']};pb['expectedVersion']=p['version'];pb['title']='Renamed '+uuid.uuid4().hex
    p=admin.req('/admin/products/'+p['id'],'PUT',pb)[1]['data'];p=admin.req('/admin/products/'+p['id']+'/publication','PATCH',dict(expectedVersion=p['version'],published=True))[1]['data']
    check('商品改名不改開放批次快照',anon.req(public(b))[1]['data']['productTitle']==v['productTitle'])
    r=admin.req(path(b)+'/close','POST',command(b));b=r[1]['data']['current']
    check('截單保留公開條件與歷史',r[0]==201 and anon.req(public(b))[1]['data']['status']=='CLOSED')
    check('截單不能重開',admin.req(path(b)+'/open','POST',command(b))[0]==409)
    r=admin.req(path(b)+'/open','POST',cmd);check('原開放重送回目前截單狀態',r[0]==200 and r[1]['data']['current']['status']=='CLOSED')
    d=staff.req(path(b))[1]['data'];check('稽核唯讀且無fingerprint',len(d['events'])==3 and all('fingerprint' not in e for e in d['events']) and admin.req(path(b),'DELETE')[0]==403)
    check('整個批次流程不改現貨庫存',admin.req(inv)[1]['data']==stock)
    g=Client();password=secrets.token_urlsafe(24);username='pre_'+uuid.uuid4().hex[:20];assert g.req('/customer/auth/register','POST',dict(username=username,password=password,displayName='合成預購會員'))[0]==201;assert g.req('/customer/auth/login','POST',dict(username=username,password=password,deviceId=str(uuid.uuid4()),rememberMe=False))[0]==200
    check('顧客不能使用員工 API',g.req('/admin/preorders')[0]==401 and g.req('/admin/preorders','POST',write(p))[0]==401)
    private,_=create(p);private=admin.req(path(private)+'/close','POST',command(private))[1]['data']['current'];check('取消草稿不變公開批次',anon.req(public(private))[0]==404)
    exp,_=create(p,4);r=admin.req(path(exp)+'/open','POST',command(exp));assert r[0]==201
    until=time.monotonic()+7
    while time.monotonic()<until and anon.req(public(exp))[1]['data']['status']!='EXPIRED':time.sleep(.15)
    check('真實時間到期公開EXPIRED且保留OPEN資料',anon.req(public(exp))[1]['data']['status']=='EXPIRED' and current(exp)['status']=='OPEN')
    check('公開分頁上限與UUID驗證',anon.req('/catalog/preorders?pageSize=51')[0]==400 and anon.req('/catalog/preorders/bad')[0]==400)
finally:
    for b in batches:
        v=current(b)
        if v['status']!='CLOSED':admin.req(path(v)+'/close','POST',command(v))
    for id in products:
        v=admin.req('/admin/products/'+id)[1].get('data')
        if v and v['status']=='PUBLISHED':v=admin.req('/admin/products/'+id+'/publication','PATCH',dict(expectedVersion=v['version'],published=False))[1]['data']
        if v and v['status']=='DRAFT':admin.req('/admin/products/'+id+'?expectedVersion='+str(v['version']),'DELETE')
    admin.req('/admin/logout','POST');staff.req('/admin/logout','POST')
Path(args.output).parent.mkdir(parents=True,exist_ok=True);Path(args.output).write_text(json.dumps(dict(passed=len(checks),checks=checks,transport='real loopback socket'),ensure_ascii=False,indent=2)+'\n')
print('預購批次 HTTP 通過',len(checks),'項')
