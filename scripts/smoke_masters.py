"""Real HTTP mock payment/callback smoke. Synthetic fixtures, loopback disposable demo only."""
import argparse, concurrent.futures, copy, http.cookiejar, json, os, secrets, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/figura/api');p.add_argument('--output',default='work/masters-20261007/http-masters.json');args=p.parse_args()
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

def uid():return str(uuid.uuid4())
def masterbody(name,version=0,enabled=True):return dict(name=name,description='合成主檔驗證',enabled=enabled,expectedVersion=version,requestId=uid(),note='HTTP 驗證原因')
admin=Client();check('店長登入',admin.req('/admin/login','POST',dict(username='shop_admin',password=os.environ['DEMO_ADMIN_PASSWORD'],deviceId=uid()),form=True)[0]==200)
staff=Client();check('員工登入',staff.req('/admin/login','POST',dict(username='shop_staff',password=os.environ['DEMO_STAFF_PASSWORD'],deviceId=uid()),form=True)[0]==200)
masters=[];products=[]
def create(kind):
 body=masterbody('HTTP '+uid());r=admin.req('/admin/masters/'+kind,'POST',body);check(kind+' 新增',r[0]==201);m=r[1]['data']['current'];masters.append(m);check(kind+' UUID 重播',admin.req('/admin/masters/'+kind,'POST',body)[1]['data']['replayed']);check(kind+' 安全稽核投影','fingerprint' not in r[1]['data']['event']);return m,body
def path(m):return '/admin/masters/'+m['kind']+'/'+m['id']
def edit(m,enabled=True,name=None):return masterbody(name or m['name'],m['version'],enabled)
try:
 b,original=create('BRAND');c,_=create('CATEGORY');w,_=create('WORK')
 check('員工可讀主檔與選項',staff.req('/admin/masters?kind=WORK')[0]==200 and staff.req('/admin/masters/options?kind=BRAND')[0]==200)
 check('員工不得新增或編輯',staff.req('/admin/masters/BRAND','POST',masterbody('Denied'))[0]==403 and staff.req(path(b),'PUT',edit(b))[0]==403)
 check('匿名不得讀主檔',Client().req('/admin/masters')[0]==401)
 check('CSRF 缺失拒絕',admin.req('/admin/masters/WORK','POST',masterbody('Denied'),csrf=False)[0]==403)
 check('未知欄位拒絕',admin.req('/admin/masters/WORK','POST',masterbody('Denied')|dict(actorId=1))[0]==400)
 check('非法類型與分頁拒絕',admin.req('/admin/masters?kind=BAD')[0]==400 and admin.req('/admin/masters?pageSize=51')[0]==400)
 check('同類型名稱不可重複',admin.req('/admin/masters/BRAND','POST',masterbody(b['name']))[0]==409)
 check('requestId 跨類型拒絕',admin.req('/admin/masters/WORK','POST',original)[0]==409)
 body=dict(title='HTTP associated '+uid(),brandId=b['id'],categoryId=c['id'],workId=w['id'],description='合成關聯商品',expectedVersion=0,skus=[dict(code='MASTER-'+uid(),label='標準款',priceTwd=1280,enabled=True)])
 r=admin.req('/admin/products','POST',body);check('商品以 ID 指派三類主檔',r[0]==201 and r[1]['data']['work']==w['name']);p=r[1]['data'];products.append(p['id']);pp='/admin/products/'+p['id']
 check('錯類型 ID 拒絕',admin.req('/admin/products','POST',body|dict(brandId=c['id']))[0]==404)
 check('不完整 ID 配對拒絕',admin.req('/admin/products','POST',body|dict(categoryId=None))[0]==400)
 check('上架關聯商品',admin.req(pp+'/publication','PATCH',dict(expectedVersion=p['version'],published=True))[0]==200)
 for m in [b,c,w]:check(m['kind']+' 上架引用不能停用',admin.req(path(m),'PUT',edit(m,False))[0]==409)
 renamed=edit(b,name='Renamed '+uid());r=admin.req(path(b),'PUT',renamed);check('主檔改名保存 ID',r[0]==201 and r[1]['data']['current']['id']==b['id']);b=r[1]['data']['current'];masters[0]=b
 visible=Client().req('/catalog/products/'+p['id'])[1]['data'];check('公開讀取新名稱與作品且无內部 ID',visible['brand']==b['name'] and visible['work']==w['name'] and not any(k in visible for k in ['brandId','workId','version','enabled','actorId']))
 check('過期版本拒絕',admin.req(path(b),'PUT',renamed|dict(requestId=uid()))[0]==409)
 p=admin.req(pp)[1]['data'];check('商品版本不受主檔改名影響',p['version']==1)
 p=admin.req(pp+'/publication','PATCH',dict(expectedVersion=p['version'],published=False))[1]['data']
 disabled=admin.req(path(w),'PUT',edit(w,False));check('下架後可以停用作品',disabled[0]==201);masters[2]=disabled[1]['data']['current']
 check('停用作品不能新增指派',admin.req('/admin/products','POST',body|dict(skus=[dict(code='MASTER-'+uid(),label='標準款',priceTwd=1280,enabled=True)]))[0]==409)
 write={k:p[k] for k in ['title','brandId','categoryId','workId','description','skus']};write['expectedVersion']=p['version'];p=admin.req(pp,'PUT',write)[1]['data'];check('草稿保留已停用原作品',p['workId']==w['id'])
 check('保留停用作品仍不能上架',admin.req(pp+'/publication','PATCH',dict(expectedVersion=p['version'],published=True))[0]==409)
 write['expectedVersion']=p['version'];write['workId']=None;p=admin.req(pp,'PUT',write)[1]['data'];check('可明確清除選填作品',p['workId'] is None)
 audit=admin.req('/admin/masters/audit')[1]['data'];check('稽核唯讀且可按操作核對',any(e['requestId']==renamed['requestId'] for e in audit['items']) and admin.req('/admin/masters/audit','DELETE')[0]==403)
finally:
 for id in products:
  pp='/admin/products/'+id;r=admin.req(pp)[1].get('data')
  if r and r['status']=='PUBLISHED':r=admin.req(pp+'/publication','PATCH',dict(expectedVersion=r['version'],published=False))[1].get('data')
  if r and r['status']=='DRAFT':admin.req(pp+'?expectedVersion='+str(r['version']),'DELETE')
 for m in masters:admin.req(path(m),'PUT',edit(m,False))
output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(dict(passed=len(checks),checks=checks),ensure_ascii=False,indent=2));print('主檔真實 HTTP 通過',len(checks),'項')
