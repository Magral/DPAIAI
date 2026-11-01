"""Weekly bounded search for new public source candidates; never activates their commands."""
import base64,datetime,json,os,pathlib,re,urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[2]
def get(url):
    request=urllib.request.Request(url,headers={'Accept':'application/vnd.github+json','User-Agent':'DPI-Pilot-source-discovery','Authorization':'Bearer '+os.environ['GITHUB_TOKEN']})
    with urllib.request.urlopen(request,timeout=15) as response:
        data=response.read(2*1024*1024+1)
        if len(data)>2*1024*1024:raise ValueError('discovery response limit')
        return json.loads(data)
def main():
    known={match.group(1).lower() for source in json.loads((ROOT/'tv/strategies/sources.json').read_text()) if (match:=re.match(r'https://github.com/([^/]+/[^/]+)',source['url']))}
    proposals=[];errors=[]
    try:
        items=get('https://api.github.com/search/repositories?q=byedpi+in:name,description&sort=updated&per_page=10').get('items',[])
        for item in items[:10]:
            name=item.get('full_name','')
            if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',name) or name.lower() in known:continue
            proposal={'repository':'https://github.com/'+name,'updated_at':item.get('updated_at'),'state':'candidate; not included in signed catalog'}
            try:
                doc=get('https://api.github.com/repos/'+name+'/readme')
                raw=base64.b64decode(doc.get('content',''),validate=False)
                if len(raw)>1024*1024:raise ValueError('README limit')
                text=raw.decode('utf-8',errors='replace')
                proposal['command_lines']=sum(1 for line in text.splitlines() if line.strip().startswith(('-s','-d','-f','--split','--disorder','--fake')))
                proposal['references']=list(dict.fromkeys(re.findall(r'https://(?:4pda\.to|webhamster\.ru|t\.me|github\.com)/[^\s<>"`)]{1,500}',text)))[:20]
            except Exception as error:proposal['readme_error']=type(error).__name__
            proposals.append(proposal)
    except Exception as error:errors.append(type(error).__name__)
    out=ROOT/'catalog-release';out.mkdir(exist_ok=True)
    (out/'source-discovery.json').write_text(json.dumps({'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'proposals':proposals,'errors':errors,'note':'Candidates require source review and strict parser/native checks before activation.'},ensure_ascii=False,indent=2)+'\n')
    print('Source candidates:',len(proposals),'; discovery errors:',len(errors))
if __name__=='__main__':main()
