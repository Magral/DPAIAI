const fs=require('fs'),vm=require('vm'),assert=require('assert');
const pac=fs.readFileSync(process.argv[2],'utf8');
const context=vm.createContext({});vm.runInContext(pac,context);
for(const host of ['youtube.com','www.youtube.com','rr1.googlevideo.com','i.ytimg.com','youtubei.googleapis.com'])assert.match(context.FindProxyForURL('https://'+host+'/',host),/^SOCKS5 127\.0\.0\.1:/);
for(const host of ['notyoutube.com','youtube.com.evil.test','google.com','accounts.google.com','steamcommunity.com','maps.googleapis.com','example.com'])assert.equal(context.FindProxyForURL('https://'+host+'/',host),'DIRECT');
assert.equal(context.FindProxyForURL('http://youtube.com/','youtube.com'),'DIRECT');
async function environment(mode='system',level='controllable_by_this_extension'){
 const calls=[],store={};let own=false;
 const event=()=>({addListener:()=>{}});
 const chrome={proxy:{settings:{get:async()=>({levelOfControl:own?'controlled_by_this_extension':level,value:{mode}}),set:async x=>{own=true;calls.push(['set',x]);},clear:async x=>{own=false;calls.push(['clear',x]);}}},storage:{local:{set:async x=>Object.assign(store,x),get:async()=>store}},action:{setBadgeText:async()=>{},setBadgeBackgroundColor:async()=>{}},runtime:{onInstalled:event(),onStartup:event(),onMessage:event()},alarms:{create:()=>{},onAlarm:event()}};
 const sandbox=vm.createContext({chrome,setInterval,clearInterval});vm.runInContext(fs.readFileSync('windows/extension/background.js','utf8'),sandbox);
 async function publish(data){sandbox.input=data;await vm.runInContext('publish(input)',sandbox);}
 return {calls,publish,store};
}
(async()=>{
 let e=await environment();await e.publish({running:true,enabled:true,routingAllowed:true,pac});assert.equal(e.calls[0][0],'set');assert.equal(e.calls[0][1].scope,'regular_only');
 await e.publish({running:false,enabled:false,desiredEnabled:true});assert.equal(e.calls.at(-1)[0],'clear');assert.equal(e.store.want,true);
 await e.publish({running:false,enabled:false,desiredEnabled:false});assert.equal(e.store.want,false);
 for(const mode of ['fixed_servers','pac_script','auto_detect']){e=await environment(mode);await e.publish({running:true,enabled:true,routingAllowed:true,pac});assert.equal(e.calls.length,0);}
 e=await environment('system','controlled_by_other_extensions');await e.publish({running:true,enabled:true,routingAllowed:true,pac});assert.equal(e.calls.length,0);
 e=await environment();await e.publish({running:true,enabled:true,routingAllowed:false,routingReason:'existing system proxy',pac});assert.equal(e.calls.length,0);
 console.log('PAC routing, extension cleanup, regular-only scope and existing proxy conflict tests passed');
})().catch(e=>{console.error(e);process.exit(1);});
