"""Real inventory socket checks on disposable loopback demo only; no credentials in report."""
import argparse, copy, http.cookiejar, json, os, urllib.request, urllib.error, urllib.parse, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/ELPSY/api');p.add_argument('--output',default='work/inventory-20261007/http-inventory.json');args=p.parse_args()
assert urllib.parse.urlsplit(args.base_url).hostname in ['127.0.0.1','localhost']
checks=[]
def check(name,ok):
    assert ok,name
    checks.append(name)
def client():return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def req(c,path,method='GET',body=None,csrf=None,form=False):
    headers={} if csrf is None else {'X-XSRF-TOKEN':csrf}
    if body is not None:headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
    raw=(urllib.parse.urlencode(body).encode() if form else json.dumps(body).encode()) if body is not None else None
    try:
        with c.open(urllib.request.Request(args.base_url+path,data=raw,method=method,headers=headers),timeout=15) as r:return r.status,json.loads(r.read())
    except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
def token(c):return req(c,'/auth/csrf')[1]['data']['token']
def login(admin):
    c=client();t=token(c);r=req(c,'/admin/login','POST',dict(username='shop_admin' if admin else 'shop_staff',password=os.environ['DEMO_ADMIN_PASSWORD' if admin else 'DEMO_STAFF_PASSWORD'],deviceId=str(uuid.uuid4())),t,True);assert r[0]==200;return c,token(c)
admin,at=login(True);staff,st=login(False);anon=client();suffix=uuid.uuid4().hex[:16];product_id=None
def operation(version,delta,reason):return dict(requestId=str(uuid.uuid4()),expectedVersion=version,delta=delta,reason=reason,note='HTTP 庫存演練')
try:
    body=dict(title='HTTP 庫存商品 '+suffix,brand='ELPSY',category='公仔',description='庫存合成資料',expectedVersion=0,skus=[dict(code='STOCK-'+suffix,label='標準款',priceTwd=1280,enabled=True),dict(code='STOCK-OFF-'+suffix,label='停用款',priceTwd=1880,enabled=False)])
    status,result=req(staff,'/admin/products','POST',body,st);assert status==201;product=result['data'];product_id=product['id'];sku=next(s for s in product['skus'] if s['enabled']);other=next(s for s in product['skus'] if not s['enabled']);base='/admin/products/'+product_id;path=base+'/skus/'+sku['id']+'/inventory';adjust=path+'/adjustments'
    check('新 SKU 真實零庫存與零版本',req(staff,path)[1]['data']['quantity']==0 and req(staff,path)[1]['data']['version']==0)
    check('員工可讀空異動紀錄',req(staff,adjust)[1]['data']['total']==0)
    check('匿名不能讀庫存明細',req(anon,path)[0]==401)
    incoming=operation(0,5,'RESTOCK');check('員工直接調整庫存403',req(staff,adjust,'POST',incoming,st)[0]==403)
    check('調整缺少 CSRF 被拒絕',req(admin,adjust,'POST',incoming)[0]==403)
    bad=copy.deepcopy(incoming);bad['actorId']=999;check('操作者不能由 body 指定',req(admin,adjust,'POST',bad,at)[0]==400)
    bad=copy.deepcopy(incoming);bad['delta']=1.5;check('小數庫存拒絕而非截斷',req(admin,adjust,'POST',bad,at)[0]==400)
    check('負庫存操作409且沒有異動',req(admin,adjust,'POST',operation(0,-1,'DAMAGE'),at)[0]==409 and req(admin,adjust)[1]['data']['total']==0)
    check('店長可上架零庫存商品供瀏覽',req(admin,base+'/publication','PATCH',dict(expectedVersion=0,published=True),at)[0]==200)
    check('零庫存公開 available=false',req(anon,'/catalog/products/'+product_id)[1]['data']['skus'][0]['available'] is False)
    status,result=req(admin,adjust,'POST',incoming,at);a=result['data']['adjustment'];check('店長入庫201且前後數量版本完整',status==201 and a['beforeQuantity']==0 and a['afterQuantity']==5 and a['beforeVersion']==0 and a['afterVersion']==1)
    me=req(admin,'/auth/me')[1]['data'];check('稽核操作者來自認證本人',a['actorId']==me['id'] and a['actorUsername']=='shop_admin')
    status,result=req(admin,adjust,'POST',incoming,at);check('相同操作重送200且僅一筆異動',status==200 and result['data']['replayed'] and req(admin,path)[1]['data']['quantity']==5 and req(admin,adjust)[1]['data']['total']==1)
    bad=copy.deepcopy(incoming);bad['delta']=6;check('同操作編號不同內容409',req(admin,adjust,'POST',bad,at)[0]==409)
    otherpath=base+'/skus/'+other['id']+'/inventory';check('同操作編號不可套別的SKU',req(admin,otherpath+'/adjustments','POST',incoming,at)[0]==409 and req(admin,otherpath)[1]['data']['quantity']==0)
    public=req(anon,'/catalog/products/'+product_id)[1]['data']['skus'];check('公開有庫存但不洩漏數量版本與停用款',len(public)==1 and public[0]['available'] is True and set(public[0])=={'id','label','priceTwd','available'})
    check('舊庫存版本不能覆寫',req(admin,adjust,'POST',operation(0,1,'RESTOCK'),at)[0]==409)
    check('損耗扣至零成功',req(admin,adjust,'POST',operation(1,-5,'DAMAGE'),at)[0]==201 and req(admin,path)[1]['data']['quantity']==0)
    check('扣至零後公開立即無庫存',req(anon,'/catalog/products/'+product_id)[1]['data']['skus'][0]['available'] is False)
    status,result=req(admin,adjust,'POST',incoming,at);check('歷史重送不還原舊庫存',status==200 and result['data']['adjustment']['afterQuantity']==5 and req(admin,path)[1]['data']['quantity']==0)
    check('異動紀錄不可透過API編輯',req(admin,adjust,'PUT',incoming,at)[0]==403)
    check('異動紀錄不可透過API刪除',req(admin,adjust+'/'+incoming['requestId'],'DELETE',csrf=at)[0]==403)
    check('異動分頁有上限',req(admin,adjust+'?pageSize=51')[0]==400)
    check('款式路徑不接受跨商品ID',req(admin,'/admin/products/'+str(uuid.uuid4())+'/skus/'+sku['id']+'/inventory')[0]==404)
    req(admin,base+'/publication','PATCH',dict(expectedVersion=1,published=False),at);check('商品封存成功',req(admin,base+'?expectedVersion=2','DELETE',csrf=at)[0]==200)
    check('封存商品異動紀錄保留且禁止新調整',req(admin,adjust)[1]['data']['total']==2 and req(admin,adjust,'POST',operation(2,1,'RESTOCK'),at)[0]==409)
    check('封存後仍可確認既有操作',req(admin,adjust,'POST',incoming,at)[0]==200)
finally:
    if product_id:
        current=req(admin,'/admin/products/'+product_id)[1].get('data')
        if current and current['status']=='PUBLISHED':current=req(admin,'/admin/products/'+product_id+'/publication','PATCH',dict(expectedVersion=current['version'],published=False),at)[1].get('data')
        if current and current['status']=='DRAFT':req(admin,'/admin/products/'+product_id+'?expectedVersion='+str(current['version']),'DELETE',csrf=at)
    req(admin,'/admin/logout','POST',csrf=at);req(staff,'/admin/logout','POST',csrf=st)
Path(args.output).parent.mkdir(parents=True,exist_ok=True);Path(args.output).write_text(json.dumps(dict(passed=len(checks),checks=checks,transport='real loopback socket',syntheticProductsCreated=1),ensure_ascii=False,indent=2)+'\n');print('庫存 HTTP 驗證通過',len(checks),'項')
