#!/usr/bin/env python3
"""Fetch fixed sources as text, extract commands, validate with the pinned engine, sign DATA.
Source text never becomes shell code. Unavailable/deleted sources cannot erase old entries.
"""
import base64,datetime,hashlib,html,json,pathlib,re,subprocess,tempfile,time,urllib.request,urllib.error
from html.parser import HTMLParser
from build_catalog import root,normalize,compile_engine,starts,sni

ENGINE='ba532298de7b28cfe854aea83d061369d13ca290'
FEED='https://github.com/Magral/DPAIAI/releases/download/strategy-catalog/catalog-v1.json'
MAX_BODY=2*1024*1024

class Text(HTMLParser):
    def __init__(self):super().__init__(convert_charrefs=True);self.parts=[];self.hidden=0
    def handle_starttag(self,tag,attrs):
        if tag in ('script','style'):self.hidden+=1
        if tag in ('br','p','div','pre','code','li'):self.parts.append('\n')
    def handle_endtag(self,tag):
        if tag in ('script','style'):self.hidden=max(0,self.hidden-1)
        if tag in ('p','div','pre','code','li'):self.parts.append('\n')
    def handle_data(self,data):
        if not self.hidden:self.parts.append(data)

def fetch(url):
    request=urllib.request.Request(url,headers={'User-Agent':'DPI-Pilot-catalog-updater/1','Accept-Encoding':'identity'})
    with urllib.request.urlopen(request,timeout=20) as response:
        if not response.url.startswith('https://'):raise ValueError('non-HTTPS redirect')
        data=response.read(MAX_BODY+1)
        if len(data)>MAX_BODY:raise ValueError('source too large')
        return data

def raw_url(url):
    match=re.fullmatch(r'https://github.com/([^/]+)/([^/]+)/blob/(.+)',url)
    return 'https://raw.githubusercontent.com/'+ '/'.join(match.groups()) if match else url

def extract(text):
    if re.search(r'<(?:html|div|pre|body)\b',text,re.I):
        parser=Text();parser.feed(text);text=''.join(parser.parts)
    text=html.unescape(text).replace('\\-','-')
    found=[]
    # Kotlin/list/document snippets are strings or complete command lines, never scripts.
    found.extend(re.findall(r'["`“](-{1,2}[^"`”\n\r]{1,4096})["`”]',text))
    for line in text.splitlines():
        line=line.strip().strip('`').strip()
        if line.startswith('-') and len(line)<=4096:found.append(line)
    return list(dict.fromkeys(found))[:4000]

def safe(command):
    # Same resource limits as the APK, before the native parser sees input.
    tokens=command.split();i=0;groups=0
    flags={'--md5sig','--drop-sack','--tfo','--wait-send'}
    while i<len(tokens):
        name=tokens[i];i+=1
        if name in flags:continue
        value=tokens[i];i+=1
        if len(value)>253 or any(int(n)>86400 for n in re.findall(r'[0-9]+',value)):return False
        bounds={'--buf-size':(500,65536),'--timeout':(1,60),'--ttl':(1,255),'--udp-fake':(0,16),'--cache-ttl':(0,86400),'--await-int':(0,1000)}
        if name in bounds:
            lo,hi=bounds[name]
            if not lo<=int(value)<=hi:return False
        if name=='--auto':
            groups+=1
            if groups>16:return False
        if name=='--round' and not all(0<=int(n)<=40 for n in value.split(':')):return False
        if name=='--fake-sni' and not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?',value):return False
    return True

def signer(mode,payload,signature):
    subprocess.run(['java',str(root/'scripts/CatalogSigner.java'),mode,str(payload),str(signature)],check=True)

def previous(tmp):
    try:data=fetch(FEED)
    except urllib.error.HTTPError as error:
        if error.code==404:
            # A missing asset in an existing channel is an outage, not a fresh empty channel.
            try:fetch('https://api.github.com/repos/Magral/DPAIAI/releases/tags/strategy-catalog')
            except urllib.error.HTTPError as meta:
                if meta.code==404:return None
                raise
            raise RuntimeError('Existing catalog channel has no downloadable asset')
        raise
    envelope=json.loads(data)
    payload=base64.b64decode(envelope['payload'],validate=True)
    signature=base64.b64decode(envelope['signature'],validate=True)
    p=tmp/'previous.json';s=tmp/'previous.sig';p.write_bytes(payload);s.write_bytes(signature)
    signer('verify',p,s)
    old=json.loads(payload)
    if old['schema']!=1 or old['engine_commit']!=ENGINE:raise ValueError('previous channel compatibility')
    if not 0<len(old['strategies'])<=2000:raise ValueError('previous size')
    return old

def main():
    bundled=json.loads((root/'tv/strategies/catalog.json').read_text())
    sources=json.loads((root/'tv/strategies/sources.json').read_text())
    report={'generated_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sources':[],'rejected':[]}
    with tempfile.TemporaryDirectory() as temporary:
        tmp=pathlib.Path(temporary);old=previous(tmp)
        entries={e['id']:e for e in bundled['strategies']}
        if old:
            for e in old['strategies']:
                if e['id']!=hashlib.sha256(e['args'].encode()).hexdigest()[:16]:raise ValueError('previous ID')
                entries.setdefault(e['id'],e)
        exe=tmp/'engine';compile_engine(exe)
        native_attempts=0
        # Every retained entry must still start with this exact pinned engine.
        for e in entries.values():
            if normalize(e['args'])!=e['args'] or not safe(e['args']):raise ValueError('unsafe retained entry')
            ok,error=starts(exe,e['args'])
            if not ok:raise ValueError('retained native validation failed: '+e['id'])
        for source in sources:
            record={'id':source['id'],'url':source['url'],'new':0}
            try:
                data=fetch(raw_url(source['url']))
                record['sha256']=hashlib.sha256(data).hexdigest()
                commands=extract(data.decode('utf-8',errors='replace'))
                record['extracted']=len(commands);record['status']='fetched' if commands else 'no complete commands; snapshot retained'
            except Exception as error:
                record['status']='fetch failed; snapshot retained';record['error']=type(error).__name__;commands=[]
            for raw in commands:
                for candidate in ([raw.replace('{sni}',domain) for domain in sni] if '{sni}' in raw else [raw]):
                    try:
                        command=normalize(candidate)
                        if not safe(command):raise ValueError('TV resource bounds')
                        ident=hashlib.sha256(command.encode()).hexdigest()[:16]
                        if ident in entries:
                            if source['id'] not in entries[ident]['sources']:entries[ident]['sources'].append(source['id'])
                            continue
                        if len(entries)>=2000:raise ValueError('catalog limit')
                        if native_attempts>=500:raise ValueError('daily native-check budget; retry next run')
                        native_attempts+=1
                        ok,error=starts(exe,command)
                        if not ok:raise ValueError('native: '+error)
                        entries[ident]={'id':ident,'args':command,'sources':[source['id']]};record['new']+=1
                    except (ValueError,TypeError,IndexError) as error:
                        if len(report['rejected'])<500:report['rejected'].append({'source':source['id'],'raw':candidate[:4096],'reason':str(error)[:300]})
            report['sources'].append(record)
        successful=sum(s['status']=='fetched' for s in report['sources'])
        if successful==0:raise RuntimeError('No source refreshed; refusing to publish a misleading update')
        sequence=max(int(time.time()),(old['sequence']+1 if old else 1))
        payload={'schema':1,'sequence':sequence,'version':'remote-'+str(sequence),'engine_commit':ENGINE,
                 'strategies':list(entries.values()),'sources':[{**{k:v for k,v in source.items() if k!='commands'},'fetch_status':report['sources'][i]['status'],'checked_at':report['generated_at']} for i,source in enumerate(sources)],'collection':{'fetched':successful,'total':len(sources),'checked_at':report['generated_at']}}
        out=root/'catalog-release';out.mkdir(exist_ok=True)
        data=json.dumps(payload,ensure_ascii=False,separators=(',',':')).encode()
        if len(data)>MAX_BODY:raise ValueError('payload too large')
        path=tmp/'payload.json';sig=tmp/'signature.bin';path.write_bytes(data)
        signer('sign',path,sig);signer('verify',path,sig)
        envelope=json.dumps({'payload':base64.b64encode(data).decode(),'signature':base64.b64encode(sig.read_bytes()).decode()},separators=(',',':'))
        (out/'catalog-v1.json').write_text(envelope)
        report.update(sequence=sequence,strategies=len(entries),previous_sequence=old['sequence'] if old else None)
        (out/'refresh-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        (out/'summary.json').write_text(json.dumps({'sequence':sequence,'strategies':len(entries),'sources_fetched':successful}))
        print(f"Validated and signed {len(entries)} strategies; {successful}/{len(sources)} sources fetched. Failures retained snapshots.")

if __name__=='__main__':main()
