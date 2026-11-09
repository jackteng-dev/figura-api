"""Actual socket RBAC verification; disposable loopback demo, no credentials in report."""
import argparse,http.cookiejar,urllib.request as u,urllib.parse,urllib.error,json,os,uuid,copy,concurrent.futures
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/ELPSY/api');p.add_argument('--output',default='work/rbac-20261007/http-access.json');args=p.parse_args();assert urllib.parse.urlsplit(args.base_url).hostname in ('localhost','127.0.0.1')
checks=[]
def check(name,ok):assert ok,name;checks.append(name)
class Client:
 def __init__(self):self.jar=http.cookiejar.CookieJar();self.op=u.build_opener(u.ProxyHandler({}),u.HTTPCookieProcessor(self.jar));self.csrf=None
 def req(self,path,method='GET',body=None,form=False,csrf=True):
  headers={'X-XSRF-TOKEN':self.csrf} if csrf and self.csrf and method!='GET' else {}
  if body is not None:headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
  raw=(urllib.parse.urlencode(body).encode() if form else json.dumps(body).encode()) if body is not None else None
  try:
   with self.op.open(u.Request(args.base_url+path,data=raw,method=method,headers=headers),timeout=25) as r:return r.status,json.load(r)
  except urllib.error.HTTPError as e:return e.code,json.load(e)
 def login(self,name,pw):
  self.csrf=self.req('/auth/csrf')[1]['data']['token'];r=self.req('/admin/login','POST',dict(username=name,password=pw,deviceId=str(uuid.uuid4())),True);assert r[0]==200;return r[1]['data']
def ident():return str(uuid.uuid4())
a=Client();a.login('shop_admin',os.environ['DEMO_ADMIN_PASSWORD']);s=Client();s.login('shop_staff',os.environ['DEMO_STAFF_PASSWORD']);anon=Client();owned_users=[];owned_roles=[]
def roster():
 out=[];page=1
 while True:
  r=a.req('/admin/access/staff?page='+str(page))[1]['data'];out+=r['items'];page+=1
  if len(out)>=r['total']:return out
def staff(id):return next(x for x in roster() if x['id']==id)
def write(row,enabled=None,roles=None):return dict(requestId=ident(),expectedVersion=row['version'],enabled=row['enabled'] if enabled is None else enabled,roleIds=row['roleIds'] if roles is None else roles,note='HTTP RBAC 安全驗證')
try:
 check('匿名管理列表401',anon.req('/admin/access/staff')[0]==401)
 check('普通員工管理API403',s.req('/admin/access/staff')[0]==403)
 check('實際固定權限目錄不暴露URL',all('url' not in c for c in a.req('/admin/access/permissions')[1]['data']))
 body=dict(username='http_rbac_'+uuid.uuid4().hex,password=os.environ['DEMO_STAFF_PASSWORD'],enabled=True,roleIds=[2],requestId=ident(),note='HTTP 建立員工')
 check('新增員工必須CSRF',a.req('/admin/access/staff','POST',body,csrf=False)[0]==403)
 check('禁止偽造actor與mass assignment',a.req('/admin/access/staff','POST',body|dict(actorId=999))[0]==400)
 status,result=a.req('/admin/access/staff','POST',body);check('建立員工安全投影與稽核',status==201 and 'password' not in result['data']['event']['afterState'] and 'fingerprint' not in result['data']['event']);id=result['data']['event']['targetId'];owned_users.append(id)
 check('重送建立操作只一筆',a.req('/admin/access/staff','POST',body)[1]['data']['replayed'])
 check('同UUID改密碼拒絕',a.req('/admin/access/staff','POST',body|dict(password=body['password']+'x'))[0]==409)
 new=Client();new.login(body['username'],body['password']);row=staff(id);change=write(row,False);r=a.req('/admin/access/staff/'+str(id),'PUT',change);check('明確停用且版本增加',r[0]==201 and staff(id)['version']==row['version']+1)
 check('停用既有Cookie立即401',new.req('/auth/me')[0]==401)
 check('相同操作重送只回既有event',a.req('/admin/access/staff/'+str(id),'PUT',change)[1]['data']['replayed'])
 check('過期版本不覆寫',a.req('/admin/access/staff/'+str(id),'PUT',change|dict(requestId=ident()))[0]==409)
 a.req('/admin/access/staff/'+str(id),'PUT',write(staff(id),True))
 check('重新啟用不復活舊access或refresh',new.req('/auth/me')[0]==401 and new.req('/auth/refresh','POST')[0]==401)
 new.login(body['username'],body['password']);check('重新登入可用',new.req('/auth/me')[0]==200)
 rb=dict(name='HTTP 可讀商品角色 '+uuid.uuid4().hex[:6],description='不含管理權限',enabled=True,permissionIds=[1,10,11],expectedVersion=0,requestId=ident(),note='HTTP 建立固定授權')
 rr=a.req('/admin/access/roles','POST',rb);check('建立角色僅指派固定權限',rr[0]==201);rid=rr[1]['data']['event']['targetId'];owned_roles.append(rid)
 check('未知API路徑不能注入角色',a.req('/admin/access/roles','POST',rb|dict(url='/api/**',requestId=ident()))[0]==400)
 check('目錄外權限拒絕',a.req('/admin/access/roles','POST',rb|dict(permissionIds=[999999],requestId=ident()))[0]==400)
 a.req('/admin/access/staff/'+str(id),'PUT',write(staff(id),roles=[rid]));check('角色指派撤銷舊session',new.req('/auth/me')[0]==401);new.login(body['username'],body['password'])
 check('新權限可讀商品且不能寫入',new.req('/admin/products')[0]==200 and new.req('/admin/products','POST',{})[0]==403)
 role=json.loads(rr[1]['data']['event']['afterState']);change=rb|dict(expectedVersion=role['version'],requestId=ident(),enabled=False)
 check('角色停用成功',a.req('/admin/access/roles/'+str(rid),'PUT',change)[0]==201)
 check('停用角色立即撤銷session',new.req('/auth/me')[0]==401)
 adminId=a.req('/auth/me')[1]['data']['id'];self=staff(adminId);check('最後管理員不能自我停用',a.req('/admin/access/staff/'+str(adminId),'PUT',write(self,False))[0]==409)
 check('最後管理員不能自我降權',a.req('/admin/access/staff/'+str(adminId),'PUT',write(self,roles=[2]))[0]==409)
 check('舊旁路寫入停用410',all(a.req(path,'POST',{})[0]==410 for path in ['/admin/register','/admin/stopAccount','/admin/user/update','/role/assignUser','/role/assignPermission']))
 audit=a.req('/admin/access/audit')[1]['data']['items'];check('audit保留actor與版本但不含私有密碼',any(x['actorId']==adminId and x['targetId']==id for x in audit) and body['password'] not in json.dumps(audit))
 check('audit沒有可刪改API',a.req('/admin/access/audit','DELETE',{})[0]==403)
finally:
 for id in owned_users:
  row=staff(id)
  if row['enabled']:a.req('/admin/access/staff/'+str(id),'PUT',write(row,False))
 # Retain synthetic ledger and disabled role; disposable DB teardown handles physical removal.
output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(dict(passed=len(checks),checks=checks),ensure_ascii=False,indent=2));print('RBAC 真實HTTP通過',len(checks),'項')
