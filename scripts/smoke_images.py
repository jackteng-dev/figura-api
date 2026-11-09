"""Real socket catalog checks against disposable loopback demo only; no credentials in report."""
import argparse, copy, http.cookiejar, json, os, urllib.request, urllib.error, urllib.parse, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19092/ELPSY/api');p.add_argument('--output',default='work/images-20261007/http-images.json');args=p.parse_args()
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
body=dict(title='HTTP 收藏品 '+suffix,brand='ELPSY',category='公仔',description='真實 HTTP 驗證資料',expectedVersion=0,skus=[dict(code='HTTP-'+suffix,label='標準款',priceTwd=1280,enabled=True),dict(code='HTTP-OFF-'+suffix,label='停用款',priceTwd=1880,enabled=False)])
product_id=None

import struct,zlib,hashlib,concurrent.futures
def png(color):
    def chunk(t,b):return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',24,18,8,6,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+bytes(color)*24)*18))+chunk(b'IEND',b'')
def upload(c,path,fields,data,csrf=None,mime='image/png'):
    boundary='elpsy-'+uuid.uuid4().hex;out=[]
    for k,v in fields.items():out.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="'+k+'"\r\n\r\n'+str(v)+'\r\n').encode())
    out.extend([('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="../../image.svg"\r\nContent-Type: '+mime+'\r\n\r\n').encode(),data,('\r\n--'+boundary+'--\r\n').encode()]);headers={'Content-Type':'multipart/form-data; boundary='+boundary}
    if csrf:headers['X-XSRF-TOKEN']=csrf
    try:
        with c.open(urllib.request.Request(args.base_url+path,data=b''.join(out),method='POST',headers=headers),timeout=20) as r:return r.status,json.load(r)
    except urllib.error.HTTPError as e:return e.code,json.load(e)
def binary(c,path):
    try:
        with c.open(args.base_url+path,timeout=15) as r:return r.status,r.headers,r.read()
    except urllib.error.HTTPError as e:return e.code,e.headers,e.read()
def change(product):return dict(requestId=str(uuid.uuid4()),expectedVersion=product['version'],alt='合成主圖 <img src=x>')
try:
    status,result=req(staff,'/admin/products','POST',body,st);assert status==201;product=result['data'];product_id=product['id'];path='/admin/products/'+product_id;ip=path+'/image';original=png([100,80,180,255]);request=change(product)
    check('匿名後台圖片讀寫401',req(anon,ip)[0]==401 and upload(anon,ip,request,original)[0]==403 and upload(anon,ip,request,original,token(anon))[0]==401)
    check('圖片上傳需要CSRF',upload(staff,ip,request,original)[0]==403)
    check('非法欄位與客戶端objectKey拒絕',upload(staff,ip,request|dict(objectKey='forged'),original,st)[0]==400)
    check('SVG偽圖與MIME不一致拒絕',upload(staff,ip,request,b'<svg/>',st,'image/svg+xml')[0]==400 and upload(staff,ip,request,original,st,'image/jpeg')[0]==400)
    check('空檔過大與不整數版次拒絕',upload(staff,ip,request,b'',st)[0]==400 and upload(staff,ip,request,b'0'*5242881,st)[0]==413 and upload(staff,ip,request|dict(expectedVersion='0.5'),original,st)[0]==400)
    r=upload(staff,ip,request,original+b'<script>tail</script>',st);assert r[0]==200;firstRequest=request;firstSource=original+b'<script>tail</script>';product=req(staff,path)[1]['data'];image=product['image'];old=image
    check('草稿圖片版本加一與安全投影',product['version']==1 and set(image)=={'id','url','alt','width','height'} and image['width']==24 and image['height']==18)
    r=binary(staff,image['url'][4:]);check('私有圖片內容經API且重編碼除去附加內容',r[0]==200 and r[1]['Content-Type']=='image/png' and r[1]['Cache-Control']=='no-store' and r[1]['X-Content-Type-Options']=='nosniff' and b'<script>' not in r[2])
    pub='/catalog/products/'+product_id+'/image/'+image['id'];check('草稿主圖公開404',binary(anon,pub)[0]==404)
    check('同操作同bytes重送不新增',upload(staff,ip,firstRequest,firstSource,st)[1]['data']['replayed'] and len(req(staff,ip)[1]['data']['events'])==1)
    check('同ID改內容或替代文字409',upload(staff,ip,firstRequest,original,st)[0]==409 and upload(staff,ip,firstRequest|dict(alt='changed'),firstSource,st)[0]==409)
    request=change(product)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:upload(staff,ip,request,png([0,150,100,255]),st),range(2)))
    check('同替換操作競爭一勝一重送',all(r[0]==200 for r in results) and len(req(staff,ip)[1]['data']['events'])==2)
    product=req(staff,path)[1]['data'];image=product['image'];check('替換產生新ID且舊URL立即404',image['id']!=old['id'] and binary(staff,old['url'][4:])[0]==404)
    check('歷史上傳重送回目前主圖',upload(staff,ip,firstRequest,firstSource,st)[1]['data']['current']['image']==image)
    check('普通員工不可上架',req(staff,path+'/publication','PATCH',dict(expectedVersion=product['version'],published=True),st)[0]==403)
    product=req(admin,path+'/publication','PATCH',dict(expectedVersion=product['version'],published=True),at)[1]['data'];visible=req(anon,'/catalog/products/'+product_id)[1]['data']
    check('匿名公開商品真主圖與PNG內容',visible['image']['url'].startswith('/api/catalog/') and binary(anon,visible['image']['url'][4:])[0]==200 and 'objectKey' not in json.dumps(visible))
    check('已上架圖片修改拒絕',upload(staff,ip,change(product),original,st)[0]==409)
    check('公開錯誤圖片ID404',binary(client(),'/catalog/products/'+product_id+'/image/'+str(uuid.uuid4()))[0]==404)
    try:client().open(os.environ['SHOP_IMAGES_ENDPOINT']+'/'+os.environ['SHOP_IMAGES_BUCKET']);private=False
    except urllib.error.HTTPError as e:private=e.code==403
    check('S3 bucket拒絕匿名直接存取',private)
    product=req(admin,path+'/publication','PATCH',dict(expectedVersion=product['version'],published=False),at)[1]['data'];check('下架立即阻擋既有公開圖片URL',binary(anon,visible['image']['url'][4:])[0]==404)
    removal=dict(requestId=str(uuid.uuid4()),expectedVersion=product['version']);r=req(staff,ip,'DELETE',removal,st);assert r[0]==200
    check('移除清主圖且版次加一',r[1]['data']['current']['image'] is None and r[1]['data']['current']['version']==product['version']+1)
    check('移除重送只一筆紀錄',req(staff,ip,'DELETE',removal,st)[1]['data']['replayed'] and len(req(staff,ip)[1]['data']['events'])==3)
    check('移除未知欄位拒絕與稽核不可修改',req(staff,ip,'DELETE',removal|dict(owner='forged'),st)[0]==400 and req(staff,ip,'PUT',removal,st)[0]==403)
    check('原SKU與庫存不變',req(staff,path)[1]['data']['skus']==product['skus'])

finally:
    # No production deletion; best-effort unpublish/archive only this synthetic product.
    if product_id:
        current=req(admin,'/admin/products/'+product_id)[1].get('data')
        if current and current['status']=='PUBLISHED':current=req(admin,'/admin/products/'+product_id+'/publication','PATCH',dict(expectedVersion=current['version'],published=False),at)[1].get('data')
        if current and current['status']=='DRAFT':req(admin,'/admin/products/'+product_id+'?expectedVersion='+str(current['version']),'DELETE',csrf=at)
    req(admin,'/admin/logout','POST',csrf=at);req(staff,'/admin/logout','POST',csrf=st)
Path(args.output).parent.mkdir(parents=True,exist_ok=True);Path(args.output).write_text(json.dumps(dict(passed=len(checks),checks=checks,transport='real loopback socket',syntheticProductsCreated=1),ensure_ascii=False,indent=2)+'\n')
print('商品圖片 HTTP 驗證通過',len(checks),'項')
