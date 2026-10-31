let current={};
async function render(){
  current=await chrome.runtime.sendMessage({command:'get'});
  document.getElementById('status').textContent=(current.phase||'Нет связи')+'\n'+(current.extension||'')+'\n'+(current.strategy||'');
  document.getElementById('toggle').textContent=current.enabled?'Выключить':'Включить';
}
document.getElementById('toggle').onclick=async()=>{await chrome.runtime.sendMessage({command:current.enabled?'disable':'enable'});setTimeout(render,700);};
document.getElementById('check').onclick=()=>chrome.runtime.sendMessage({command:'check'});
document.getElementById('app').onclick=()=>chrome.runtime.sendMessage({command:'openApp'});
render();setInterval(render,2000);
