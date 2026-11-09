"""僅對 loopback 的獨立展示服務驗證，不輸出密碼或 token。"""
import argparse, copy, concurrent.futures, http.cookiejar, json, os, urllib.request, urllib.error, urllib.parse, uuid
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/ELPSY/api');p.add_argument('--output');args=p.parse_args()
assert urllib.parse.urlsplit(args.base_url).hostname in ['127.0.0.1','localhost'], '只允許 loopback 演練'
checks=[]
def check(name,ok):
    assert ok,name
    checks.append(name)
def client(source=None):
    jar=http.cookiejar.CookieJar()
    for c in source or []:jar.set_cookie(copy.copy(c))
    return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(jar)),jar
def req(c,path,method='GET',data=None,csrf=None):
    headers={}
    if csrf:headers['X-XSRF-TOKEN']=csrf
    if data is not None:headers['Content-Type']='application/x-www-form-urlencoded'
    body=urllib.parse.urlencode(data).encode() if data is not None else None
    try:
        with c.open(urllib.request.Request(args.base_url+path,data=body,method=method,headers=headers),timeout=15) as r:return r.status,json.loads(r.read()),r.headers
    except urllib.error.HTTPError as e:return e.code,json.loads(e.read()),e.headers

def token(c):return req(c,'/auth/csrf')[1]['data']['token']
def login(name,password,remember=False):
    c,j=client();t=token(c);r=req(c,'/admin/login','POST',dict(username=name,password=password,deviceId=str(uuid.uuid4()),rememberMe=str(remember).lower()),t)
    assert r[0]==200 and r[1]['code']==0,'展示登入失敗'
    return c,j,token(c),r

adminPassword=os.environ['DEMO_ADMIN_PASSWORD'];staffPassword=os.environ['DEMO_STAFF_PASSWORD']
anon,aj=client();check('匿名身分 API 401',req(anon,'/auth/me')[0]==401)
check('缺少 CSRF 的登入 403',req(anon,'/admin/login','POST',dict(username='shop_admin',password=adminPassword,deviceId=str(uuid.uuid4())))[0]==403)
a,jar,t,logged=login('shop_admin',adminPassword)
check('私有 ADMIN 登入成功',logged[1]['data']['username']=='shop_admin')
cookies=[h for h in logged[2].get_all('Set-Cookie') or [] if h.startswith(('accessToken=','refreshToken='))]
check('登入 Cookie HttpOnly／Strict，非記住登入沒有 Max-Age',len(cookies)==2 and all('HttpOnly' in h and 'SameSite=Strict' in h and 'Max-Age' not in h for h in cookies))
me=req(a,'/auth/me');check('身分 API 回本人與權限且不回密碼',me[0]==200 and me[1]['data']['username']=='shop_admin' and 'password' not in me[1]['data'])
check('店長可讀展示角色',req(a,'/admin/role')[0]==200)
check('同 URL 未授權 method 被拒絕',req(a,'/admin/role','POST',{},t)[0]==403)
wrong,_=client();wt=token(wrong);check('錯誤密碼 401',req(wrong,'/admin/login','POST',dict(username='shop_admin',password='wrong-test-password',deviceId=str(uuid.uuid4())),wt)[0]==401)
staff,sj,st,sr=login('shop_staff',staffPassword)
check('STAFF 可讀本人',req(staff,'/auth/me')[1]['data']['username']=='shop_staff')
check('STAFF 管理角色 API 403',req(staff,'/admin/role')[0]==403)
remember,rj,rt,rr=login('shop_staff',staffPassword,True)
check('記住登入有效期 15 分／7 天',any('Max-Age=900' in h for h in rr[2].get_all('Set-Cookie')) and any('Max-Age=604800' in h for h in rr[2].get_all('Set-Cookie')))
req(remember,'/admin/logout','POST',csrf=rt)
check('登出該裝置失效',req(remember,'/auth/me')[0]==401)
check('其他裝置仍可用',req(staff,'/auth/me')[0]==200)
oldJar=list(jar);old,_=client(oldJar)
refreshTrials=[client(oldJar) for _ in range(4)]
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    results=list(pool.map(lambda pair:req(pair[0],'/auth/refresh','POST',csrf=t)[0],refreshTrials))
check('同 refresh 四個競爭請求僅一成功',results.count(200)==1 and results.count(401)==3)
winner=refreshTrials[results.index(200)][0]
check('refresh 後舊 access 401',req(old,'/auth/me')[0]==401)
check('舊 refresh 重播 401',req(old,'/auth/refresh','POST',csrf=t)[0]==401)
req(old,'/admin/logout','POST',csrf=t)
check('舊 refresh 登出不撤銷新 session',req(winner,'/auth/me')[0]==200)
check('寫入缺少 CSRF 403',req(winner,'/admin/logout','POST')[0]==403)
logout=req(winner,'/admin/logout','POST',csrf=t)
check('有效登出清除兩個 Cookie',logout[0]==200 and len(logout[2].get_all('Set-Cookie') or [])==2 and all('Max-Age=0' in h for h in logout[2].get_all('Set-Cookie')))
check('有效登出後不能讀本人',req(winner,'/auth/me')[0]==401)
req(staff,'/admin/logout','POST',csrf=st)
report=dict(passed=len(checks),checks=checks,database='獨立 MySQL 8.4 demo',realRedis=True)
if args.output:
    from pathlib import Path
    Path(args.output).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print('登入與權限 HTTP 驗證通過',len(checks),'項')
