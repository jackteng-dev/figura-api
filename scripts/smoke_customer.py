"""顧客身分真實 HTTP 驗證；僅 loopback 隔離 demo，建立合成顧客，不輸出帳密/token。"""
import argparse, concurrent.futures, copy, http.cookiejar, json, secrets, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/figura/api');p.add_argument('--output',default='work/customer-20261007/http-customer.json');args=p.parse_args()
assert urllib.parse.urlsplit(args.base_url).hostname in ['127.0.0.1','localhost'], '只允許 loopback'
checks=[]
def check(name,ok):
    assert ok,name
    checks.append(name)
class Client:
    def __init__(self,source=None):
        self.jar=http.cookiejar.CookieJar()
        for c in source or []:self.jar.set_cookie(copy.copy(c))
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(self.jar))
    def request(self,path,method='GET',body=None,csrf=True):
        headers={}
        if method!='GET' and csrf:headers['X-XSRF-TOKEN']=self.token
        if body is not None:headers['Content-Type']='application/json'
        data=json.dumps(body).encode() if body is not None else None
        try:
            with self.opener.open(urllib.request.Request(args.base_url+path,method=method,data=data,headers=headers),timeout=15) as r:return r.status,json.loads(r.read()),r.headers
        except urllib.error.HTTPError as e:return e.code,json.loads(e.read()),e.headers
    def csrf(self):self.token=self.request('/auth/csrf')[1]['data']['token'];return self
    def clone(self):
        c=Client(list(self.jar));c.token=self.token;return c
password=secrets.token_urlsafe(24);name='smoke_'+uuid.uuid4().hex[:18]
guest=Client().csrf()
check('匿名顧客本人 API 401',guest.request('/customer/me')[0]==401)
check('顧客登入缺 CSRF 403',guest.request('/customer/auth/login','POST',{},csrf=False)[0]==403)
register=guest.request('/customer/auth/register','POST',dict(username=name,password=password,displayName='合成會員'))
check('顧客建立成功 201 與安全投影',register[0]==201 and set(register[1]['data'])=={'id','username','displayName','createdAt'})
check('相同帳號大小寫重複 409',guest.request('/customer/auth/register','POST',dict(username=name.upper(),password=password,displayName='合成會員'))[0]==409)
check('拒絕註冊 role 注入',guest.request('/customer/auth/register','POST',dict(username='other_'+uuid.uuid4().hex[:12],password=password,displayName='合成會員',role='STAFF'))[0]==400)
login=guest.request('/customer/auth/login','POST',dict(username=name,password=password,deviceId=str(uuid.uuid4()),rememberMe=True))
check('顧客 Cookie 登入成功',login[0]==200 and login[1]['code']==0)
cookies=login[2].get_all('Set-Cookie') or []
check('顧客獨立 HttpOnly Strict Cookie 與 rememberMe',len(cookies)==2 and all('HttpOnly' in h and 'SameSite=Strict' in h for h in cookies) and any('Max-Age=900' in h for h in cookies) and any('Max-Age=604800' in h for h in cookies))
check('顧客不可呼叫員工身分',guest.request('/auth/me')[0]==401)
check('顧客不可讀員工角色',guest.request('/admin/role')[0]==401)
owner=register[1]['data']['id']
check('查詢參數不能改變本人',guest.request('/customer/me?id='+str(uuid.uuid4()))[1]['data']['id']==owner)
check('PATCH 本人拒絕 id 注入',guest.request('/customer/me','PATCH',dict(id=str(uuid.uuid4()),displayName='竄改'))[0]==400)
check('PATCH 本人暱稱成功',guest.request('/customer/me','PATCH',dict(displayName='我的收藏'))[1]['data']['displayName']=='我的收藏')
check('讀取本人更新已持久保存',guest.request('/customer/me')[1]['data']['displayName']=='我的收藏')
check('舊會員 API 410',guest.request('/user/userInfo')[0]==410)
old=guest.clone();trials=[guest.clone() for _ in range(4)]
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:results=list(pool.map(lambda c:c.request('/customer/auth/refresh','POST')[0],trials))
check('顧客四個 refresh 競爭僅一成功',results.count(200)==1 and results.count(401)==3)
current=trials[results.index(200)]
check('舊顧客 access 失效',old.request('/customer/me')[0]==401)
check('舊顧客 refresh 重播拒絕',old.request('/customer/auth/refresh','POST')[0]==401)
old.request('/customer/auth/logout','POST')
check('舊顧客 token 登出不撤銷新 session',current.request('/customer/me')[0]==200)
check('顧客寫入缺 CSRF 403',current.request('/customer/auth/logout','POST',csrf=False)[0]==403)
logout=current.request('/customer/auth/logout','POST')
check('顧客登出清除本入口兩個 Cookie',logout[0]==200 and len(logout[2].get_all('Set-Cookie') or [])==2 and all('Max-Age=0' in h for h in logout[2].get_all('Set-Cookie')))
check('顧客登出後本人 API 401',current.request('/customer/me')[0]==401)
output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(dict(passed=len(checks),checks=checks,syntheticAccountsCreated=1),ensure_ascii=False,indent=2)+'\n')
print('顧客真實 HTTP 驗證通過',len(checks),'項')
