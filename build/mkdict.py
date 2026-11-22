import csv, re, sys, collections
cjk=re.compile(r'[\u3400-\u9fff]')
def clean(s):
    s=s.strip().strip('"')
    return s
rows=[]
for fn,catchar in (('gen.csv',False),('char.csv',True)):
    with open(fn,encoding='utf-8',newline='') as f:
        for r in csv.reader(f):
            if len(r)<4: continue
            tag=r[0].strip(); cat=r[1].strip(); cnt=r[2].strip(); al=r[3]
            try: n=int(cnt)
            except: n=0
            if not tag or not cjk.search(height:=al): continue
            for a in al.split(','):
                a=clean(a)
                if not cjk.search(a): continue
                if catchar:
                    # 角色: 中文名-作品-... ; take the leading Chinese run
                    first=a.split('-')[0]
                    cands=[first]
                else:
                    cands=[a]
                for c in cands:
                    c=re.sub(r'[\s\u3000]','',c)
                    if not cjk.search(c): continue
                    if len(c)<2: continue
                    rows.append((c,tag,n))
best={}
for zh,tag,n in rows:
    cur=best.get(zh)
    if cur is None or n>cur[1]:
        best[zh]=(tag,n)
print('total pairs',len(best))
# sort by count desc
out=sorted(best.items(), key=lambda kv:-kv[1][1])
with open('tr_dict.tsv','w',encoding='utf-8') as f:
    for zh,(tag,n) in out:
        f.write(f'{zh}\t{tag}\n')
import os; print('bytes',os.path.getsize('tr_dict.tsv'))
for zh,(tag,n) in out[:10]: print(zh,tag,n)
