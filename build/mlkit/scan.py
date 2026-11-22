import os,re,sys,collections
def classes_in(root):
    s=set()
    for dp,_,fs in os.walk(root):
        for f in fs:
            if f.endswith('.smali'):
                s.add(os.path.join(dp,f)[len(root):-6].replace('/','.'))
    return s
def samename(root):
    # convert path to class descriptor normalized: com/foo/Bar
    s=set()
    for dp,_,fs in os.walk(root):
        for f in fs:
            if f.endswith('.smali'):
                p=os.path.join(dp,f)[len(root)+1:-6]
                s.add(p)
    return s
deps=samename('/tmp/mlk/sm2')
app=samename('/tmp/ha_f/smali')
have=deps|app
pat=re.compile(r'L([a-zA-Z0-9_$]+(?:/[a-zA-Z0-9_$]+)+);')
missing=collections.Counter()
for root in ('/tmp/mlk/sm2',):
    for dp,_,fs in os.walk(root):
        for f in fs:
            if not f.endswith('.smali'): continue
            b=open(os.path.join(dp,f),encoding='utf-8',errors='ignore').read()
            for m in pat.finditer(b):
                c=m.group(1)
                if c.startswith(('android/','java/','javax/','dalvik/','org/json','org/xml','org/apache/http','org/w3c','org/xmlpull')): continue
                if c not in have: missing[c]+=1
print('missing classes',len(missing))
for c,n in missing.most_common(40): print(n,c)
