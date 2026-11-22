import os,urllib.request
GOOGLE="https://dl.google.com/dl/android/maven2/"
CENTRAL="https://repo1.maven.org/maven2/"
arts=[
 ("com.google.mlkit","translate","17.0.3"),
 ("com.google.mlkit","common","18.11.0"),
 ("com.google.android.gms","play-services-base","18.5.0"),
 ("com.google.android.gms","play-services-basement","18.4.0"),
 ("com.google.android.gms","play-services-tasks","18.2.0"),
 ("com.google.firebase","firebase-components","16.1.0"),
 ("com.google.firebase","firebase-encoders","16.1.0"),
 ("com.google.firebase","firebase-encoders-json","17.1.0"),
 ("com.google.android.datatransport","transport-api","2.2.1"),
 ("com.google.android.datatransport","transport-runtime","2.2.6"),
 ("com.google.android.datatransport","transport-backend-cct","2.3.3"),
 ("androidx.core","core","1.9.0"),
 ("androidx.annotation","annotation","1.5.0"),
 ("androidx.annotation","annotation-experimental","1.3.0"),
 ("androidx.collection","collection","1.2.0"),
 ("androidx.lifecycle","lifecycle-runtime","2.3.1"),
 ("androidx.versionedparcelable","versionedparcelable","1.1.1"),
 ("androidx.concurrent","concurrent-futures","1.0.0"),
 ("androidx.profileinstaller","profileinstaller","1.3.0"),
 ("androidx.lifecycle","lifecycle-common","2.3.1"),
 ("androidx.arch.core","core-common","2.1.0"),
 ("androidx.arch.core","core-runtime","2.1.0"),
 ("com.squareup.okhttp3","okhttp","3.0.0"),
 ("com.squareup.okio","okio","1.6.0"),
]
def fetch(u,t=120):
    req=urllib.request.Request(u,headers={'User-Agent':'Mozilla/5.0'})
    return urllib.request.urlopen(req,timeout=t).read()
os.makedirs('deps2',exist_ok=True)
ok=0
for g,a,v in arts:
    p=g.replace('.','/')+'/'+a+'/'+v+'/'+a+'-'+v
    done=False
    for base in (GOOGLE,CENTRAL):
        for fmt in ('aar','jar'):
            try:
                d=fetch(base+p+'.'+fmt)
                open(f'deps2/{g}-{a}-{v}.{fmt}','wb').write(d)
                print('OK',g,a,v,fmt,len(d)); ok+=1; done=True; break
            except Exception as e:
                pass
        if done: break
    if not done: print('MISS',g,a,v)
print('done',ok)
