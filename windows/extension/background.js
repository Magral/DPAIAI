/* No page/content access, TLS interception, history or host permissions. */
const HOST = 'io.github.magral.dpipilot';
let port = null, timer = null, applied = '', state = {running:false,enabled:false,phase:'Ожидание Windows-компонента'};
let serial = Promise.resolve();
const proxyGet = () => chrome.proxy.settings.get({incognito:false});
const clearOwn = async () => {
  const setting = await proxyGet();
  if (setting.levelOfControl === 'controlled_by_this_extension')
    await chrome.proxy.settings.clear({scope:'regular_only'});
  applied = '';
};
async function publish(message) {
  state = message;
  if (!state.running || !state.enabled || !state.routingAllowed) {
    await clearOwn();
    state.extension = state.routingAllowed === false ? state.routingReason : (!state.running ? 'Компонент не запущен; обычные настройки Chrome восстановлены' : 'Выключено');
  } else {
    const setting = await proxyGet();
    const own = setting.levelOfControl === 'controlled_by_this_extension';
    if (!own && (!['controllable_by_this_extension'].includes(setting.levelOfControl) || !['system','direct'].includes(setting.value?.mode))) {
      state.extension = 'Обнаружен другой прокси или политика Chrome. Настройки не изменены.';
      applied = '';
    } else {
      const fingerprint = state.pac;
      if (applied !== fingerprint || !own) {
        await chrome.proxy.settings.set({value:{mode:'pac_script',pacScript:{data:state.pac,mandatory:true}},scope:'regular_only'});
        applied = fingerprint;
      }
      state.extension = 'YouTube — локальный обработчик; остальные адреса — DIRECT';
    }
  }
  await chrome.storage.local.set({want:!!(state.running && state.enabled),state});
  await chrome.action.setBadgeText({text:applied ? 'ON' : ''});
  await chrome.action.setBadgeBackgroundColor({color:'#197753'});
}
function offline(reason) {
  applied = '';
  serial = serial.then(()=>publish({running:false,enabled:false,phase:reason,routingAllowed:false,routingReason:'Windows-компонент недоступен; настройки Chrome освобождены'})).catch(()=>{});
}
async function connect(launch = false) {
  if (port) return;
  try {
    port = chrome.runtime.connectNative(HOST);
    port.onMessage.addListener(message => {
      serial = serial.then(()=>publish(message)).catch(error=>{ state.extension=String(error.message); });
    });
    port.onDisconnect.addListener(()=>{
      const reason = chrome.runtime.lastError?.message || 'Компонент отключён';
      port=null; if(timer) clearInterval(timer); timer=null; offline(reason);
    });
    port.postMessage({command:'hello',launch});
    timer = setInterval(()=>{try {port?.postMessage({command:'status'});}catch{}},10000);
  } catch(error) { port=null; offline(String(error.message)); }
}
chrome.runtime.onInstalled.addListener(async()=>{ await clearOwn(); await connect(false); });
chrome.runtime.onStartup.addListener(async()=>{ const data=await chrome.storage.local.get('want'); await connect(!!data.want); });
chrome.alarms.create('helper', {periodInMinutes:1});
chrome.alarms.onAlarm.addListener(async()=>{ if(!port) {const data=await chrome.storage.local.get('want'); await connect(!!data.want);} });
chrome.runtime.onMessage.addListener((message,_sender,reply)=>{
  if(message.command==='get') {reply(state);return;}
  if(['enable','disable','check','refresh','openApp'].includes(message.command)) {
    (async()=>{await connect(message.command==='enable'||message.command==='openApp');port?.postMessage({command:message.command});reply({ok:true});})().catch(error=>reply({error:String(error)}));
    return true;
  }
});
