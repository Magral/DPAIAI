#!/usr/bin/env python3
import json,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parent/'native'
text=subprocess.check_output(['go','-C',str(root),'list','-m','-json','all'],text=True)
print('DPI Pilot WARP source-built engine. AmneziaWG v3.1.20260828; Go1.25.3.\nNo Nova binary or credentials are bundled.\n')
decoder=json.JSONDecoder();position=0
while position<len(text):
    while position<len(text) and text[position].isspace():position+=1
    if position==len(text):break
    module,end=decoder.raw_decode(text,position);position=end
    if module.get('Main'):continue
    directory=module.get('Dir');path=pathlib.Path(directory) if directory else None
    if path:
        for candidate in ['LICENSE','LICENSE.txt','LICENSE.md','COPYING']:
            file=path/candidate
            if file.exists():print(module['Path']+' '+module.get('Version','')+'\n'+file.read_text()+'\n');break
stdlib=pathlib.Path(subprocess.check_output(['go','env','GOROOT'],text=True).strip())/'LICENSE'
print('Go standard library\n'+stdlib.read_text())
