#!/usr/bin/env python3
"""Nepoužité deklarace v src/main (výskyty mimo komentáře, včetně app.js). Použití: python3 tools/audit/dead_code.py ."""
import re,os,sys
root=sys.argv[1]
files={}
for d,_,fs in os.walk(root):
    if any(x in d for x in ('/build','/.git','node_modules','/out','/legacy','/dist')): continue
    for f in fs:
        if f.endswith(('.kt','.js','.kts','.html','.xml','.yml')):
            p=os.path.join(d,f); files[p]=open(p,encoding='utf-8',errors='ignore').read().split('\n')
iscom=lambda l: l.lstrip().startswith(('//','*','/*'))
decl=re.compile(r'^[ \t]*((?:(?:private|internal|public|override|inline|suspend|operator|@JvmStatic|@Synchronized|@JavascriptInterface|@Volatile|const|lateinit|open|abstract)\s+)*)(fun|val|var|class|object|data class|enum class|interface)\s+(?:<[^>]+>\s*)?(?:[A-Za-z0-9_]+\.)?([A-Za-z_][A-Za-z0-9_]*)')
out=[]
for p,lines in files.items():
    if '/src/main/' not in p or not p.endswith('.kt'): continue
    for i,l in enumerate(lines):
        m=decl.match(l)
        if not m: continue
        mods,kind,name=m.groups()
        if 'override' in mods or len(name)<3 or name in ('main','invoke','toString','equals','hashCode','instance'): continue
        if kind in('val','var') and len(l)-len(l.lstrip())>4: continue
        rx=re.compile(r'(?<![A-Za-z0-9_])'+re.escape(name)+r'(?![A-Za-z0-9_])')
        prod=test=0
        for q,ql in files.items():
            for j,x in enumerate(ql):
                if q==p and j==i: continue
                if iscom(x): continue
                n=len(rx.findall(x))
                if not n: continue
                if '/src/test/' in q: test+=n
                else: prod+=n
        if prod==0: out.append((p.replace(root+'/',''),i+1,kind,name,'jen-testy' if test else 'NEPOUŽITO'))
for r in sorted(out): print(*r)
print(len(out),file=sys.stderr)
