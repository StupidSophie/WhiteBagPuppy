import re,sys
lines=[l.rstrip() for l in open(sys.argv[1],encoding='utf-8')]
def clean(s): return s.replace('’',"'").replace('|','').strip()
out=[]
# collections
i=next(k for k,l in enumerate(lines) if l.strip()=='Dungeon Collection')
j=next(k for k,l in enumerate(lines) if k>i and l.strip()=='Dungeon Completion')
k=i
rows=[]; cur=None
while k<j:
    l=lines[k].strip()
    if l.startswith('Complete each 1 time'):
        name=clean(lines[k-1]); cur={'name':name,'group':'','dungeons':[],'bonus':''}
        k+=1; cur['group']=clean(lines[k]).rstrip(':'); k+=1
        while k<j:
            t=lines[k].strip()
            d=clean(t)
            if d: cur['dungeons'].append(d)
            if t.endswith('|'): break
            k+=1
        k+=1; cur['bonus']=clean(lines[k]); rows.append(cur)
    k+=1
for r in rows:
    m=re.match(r'\+([\d.]+)%, \+([\d,]+) Fame',r['bonus'])
    out.append('C|%s|%s|%s|%s|%s'%(r['name'],r['group'],m.group(1),m.group(2).replace(',',''),';'.join(r['dungeons'])))
# completions
k=j+1; name=None; th=[]; fame=[]
end=next(x for x,l in enumerate(lines) if 'Repeatable bonuses can be earned only' in l)
while k<end:
    t=lines[k].strip(); c=clean(t)
    if t.startswith(('True','False')):
        if name and th: out.append('T|%s|%s|%s'%(name,','.join(th),','.join(fame)))
        name=None; th=[]; fame=[]
    elif re.fullmatch(r'[\d,]+',c): th.append(c.replace(',',''))
    elif re.search(r'\+[\d,]+ Fame',t):
        for f in re.findall(r'\+([\d,]+) Fame',t): fame.append(f.replace(',',''))
    elif c and c not in ('Category','Threshold','Bonus','Repeatable') and not th: name=c
    k+=1
if name and th: out.append('T|%s|%s|%s'%(name,','.join(th),','.join(fame)))
print('\n'.join(out))
