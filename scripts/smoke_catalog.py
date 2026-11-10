"""Real socket catalog checks against disposable loopback demo only; no credentials in report."""
import argparse, copy, http.cookiejar, json, os, urllib.request, urllib.error, urllib.parse, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/figura/api');p.add_argument('--output',default='work/catalog-20261007/http-catalog.json');args=p.parse_args()
assert urllib.parse.urlsplit(args.base_url).hostname in ['127.0.0.1','localhost']
checks=[]
def check(name,ok):
    assert ok,name
    checks.append(name)
def client():
    return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def req(c,path,method='GET',body=None,csrf=None,form=False):
    headers={}
    if csrf:headers['X-XSRF-TOKEN']=csrf
    if body is not None:headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
    raw=(urllib.parse.urlencode(body).encode() if form else json.dumps(body).encode()) if body is not None else None
    try:
        with c.open(urllib.request.Request(args.base_url+path,data=raw,method=method,headers=headers),timeout=15) as r:return r.status,json.loads(r.read())
    except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
def token(c):return req(c,'/auth/csrf')[1]['data']['token']
def login(admin):
    c=client();t=token(c)
    r=req(c,'/admin/login','POST',dict(username='shop_admin' if admin else 'shop_staff',password=os.environ['DEMO_ADMIN_PASSWORD' if admin else 'DEMO_STAFF_PASSWORD'],deviceId=str(uuid.uuid4())),t,True)
    assert r[0]==200
    return c,token(c)
def write(product):return {k:copy.deepcopy(product[k]) for k in ['title','brand','category','description','skus']} | {'expectedVersion':product['version']}
admin,at=login(True);staff,st=login(False);anon=client();suffix=uuid.uuid4().hex[:16]
body=dict(title='HTTP 收藏品 '+suffix,brand='Figura',category='公仔',description='真實 HTTP 驗證資料',expectedVersion=0,skus=[dict(code='HTTP-'+suffix,label='標準款',priceTwd=1280,enabled=True),dict(code='HTTP-OFF-'+suffix,label='停用款',priceTwd=1880,enabled=False)])
product_id=None
try:
    check('匿名可讀公開列表且不導向登入',req(anon,'/catalog/products')[0]==200)
    check('匿名不能讀商品後台',req(anon,'/admin/products')[0]==401)
    check('缺少 CSRF 的建立被拒絕',req(staff,'/admin/products','POST',body)[0]==403)
    status,result=req(staff,'/admin/products','POST',body,st);check('員工建立草稿與兩款 SKU',status==201 and result['data']['status']=='DRAFT' and len(result['data']['skus'])==2);product=result['data'];product_id=product['id'];path='/admin/products/'+product_id
    check('草稿不出現在公開詳情',req(anon,'/catalog/products/'+product_id)[0]==404)
    check('草稿不出現在公開列表',all(x['id']!=product_id for x in req(anon,'/catalog/products')[1]['data']['items']))
    check('穩定 permission code 隨身分 API 回傳',any(x.get('code')=='catalog.write.create' for x in req(staff,'/auth/me')[1]['data']['permissions']))
    check('員工直接呼叫上架 API 403',req(staff,path+'/publication','PATCH',dict(expectedVersion=product['version'],published=True),st)[0]==403)
    bad=copy.deepcopy(body);bad['skus'][0]['priceTwd']=1280.5;check('小數金額拒絕而非截斷',req(staff,'/admin/products','POST',bad,st)[0]==400)
    bad=copy.deepcopy(body);bad['status']='PUBLISHED';check('不能由建立 body 指定上架狀態',req(staff,'/admin/products','POST',bad,st)[0]==400)
    check('全域 SKU 編碼不可重用',req(staff,'/admin/products','POST',body,st)[0]==409)
    status,result=req(admin,path+'/publication','PATCH',dict(expectedVersion=0,published=True),at);check('店長成功上架且版本遞增',status==200 and result['data']['version']==1);product=result['data']
    status,result=req(anon,'/catalog/products/'+product_id);visible=result['data'];check('公開只回啟用 SKU 與整數價格',status==200 and len(visible['skus'])==1 and visible['skus'][0]['priceTwd']==1280)
    check('公開投影不洩漏狀態版本或 SKU 編碼',set(visible)=={'id','title','brand','category','description','skus','image','work'} and set(visible['skus'][0])=={'id','label','priceTwd','available'})
    check('上架後禁止直接編輯',req(staff,path,'PUT',write(product),st)[0]==409)
    status,result=req(admin,path+'/publication','PATCH',dict(expectedVersion=1,published=False),at);check('店長下架後公開立即404',status==200 and req(anon,'/catalog/products/'+product_id)[0]==404);product=result['data']
    check('舊版本更新409不覆蓋新狀態',req(staff,path,'PUT',body,st)[0]==409)
    status,result=req(staff,path,'PUT',write(product),st);check('下架後員工能儲存草稿',status==200);product=result['data']
    check('員工封存草稿成功',req(staff,path+'?expectedVersion='+str(product['version']),'DELETE',csrf=st)[0]==200)
    product=req(admin,path)[1]['data'];check('封存後保留 SKU 且不可上架',product['status']=='ARCHIVED' and len(product['skus'])==2 and req(admin,path+'/publication','PATCH',dict(expectedVersion=product['version'],published=True),at)[0]==409)
    check('公開分頁有上限',req(anon,'/catalog/products?pageSize=51')[0]==400)
finally:
    # No production deletion; best-effort unpublish/archive only this synthetic product.
    if product_id:
        current=req(admin,'/admin/products/'+product_id)[1].get('data')
        if current and current['status']=='PUBLISHED':current=req(admin,'/admin/products/'+product_id+'/publication','PATCH',dict(expectedVersion=current['version'],published=False),at)[1].get('data')
        if current and current['status']=='DRAFT':req(admin,'/admin/products/'+product_id+'?expectedVersion='+str(current['version']),'DELETE',csrf=at)
    req(admin,'/admin/logout','POST',csrf=at);req(staff,'/admin/logout','POST',csrf=st)
Path(args.output).parent.mkdir(parents=True,exist_ok=True);Path(args.output).write_text(json.dumps(dict(passed=len(checks),checks=checks,transport='real loopback socket',syntheticProductsCreated=1),ensure_ascii=False,indent=2)+'\n')
print('商品 HTTP 驗證通過',len(checks),'項')
