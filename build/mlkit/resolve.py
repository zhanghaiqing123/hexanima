import os,re,sys,urllib.request,zipfile,xml.etree.ElementTree as ET,shutil
GOOGLE="https://dl.google.com/dl/android/maven2/"
CENTRAL="https://repo1.maven.org/maven2/"
def fetch(url,timeout=90):
    req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'})
    with urllib.request.urlopen(req,timeout=timeout) as r: return r.read()
def repo_for(g):
    if g.startswith(('com.google','androidx.','com.android')): return [GOOGLE,CENTRAL]
    return [CENTRAL,GOOGLE]
def path_of(g,a,v,fmt):
    return g.replace('.','/')+'/'+a+'/'+v+'/'+a+'-'+v+'.'+fmt
seen=set(); queue=[('com.google.mlkit','translate','17.0.3')]; got=[]
os.makedirs('/tmp/mlk/deps',exist_ok=True)
while queue:
    g,a,v=queue.pop(0)
    key=(g,a,v)
    if key in seen: continue
    seen.add(key)
    pom=None
    for base in repo_for(g):
        try: pom=fetch(base+path_of(g,a,v,'pom')); break
        except Exception: pass
    if pom is None:
        print('NO POM',key); continue
    root=ET.fromstring(pom)
    ns={'m':'http://maven.apache.org/POM/4.0.0'}
    # dependencies
    for d in root.findall('.//m:dependencies/m:dependency',ns):
        dg=(d.findtext('m:groupId',default='',namespaces=ns) or '').strip()
        da=(d.findtext('m:artifactId',default='',namespaces=ns) or '').strip()
        dv=(d.findtext('m:version',default='',namespaces=ns) or '').strip()
        scope=(d.findtext('m:scope',default='compile',namespaces=ns) or 'compile').strip()
        opt=(d.findtext('m:optional',default='false',namespaces=ns) or 'false').strip()
        if not dg or not da or not dv or scope in ('test','provided') or opt=='true': continue
        if '$' in dv: continue
        queue.append((dg,da,dv))
    # artifact
    ok=False
    for fmt in ('aar','jar'):
        for base in repo_for(g):
            try:
                data=fetch(base+path_of(g,a,v,fmt))
                fn=f'/tmp/mlk/deps/{g}-{a}-{v}.{fmt}'
                open(fn,'wb').write(data)
                got.append((g,a,v,len(data)))
                ok=True; break
            except Exception: pass
        if ok: break
    print(('OK  ' if ok else 'MISS'),g,a,v)
print('artifacts',len(got),'bytes',sum(x[3] for x in got))
import json; json.dump(got,open('/tmp/mlk/got.json','w'))
