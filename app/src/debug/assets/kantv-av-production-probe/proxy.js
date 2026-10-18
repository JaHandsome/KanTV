// DEBUG ONLY synthetic Promise reaction delivery; never included in main/release.
const scenario = new URLSearchParams(location.search).get('scenario');
if (scenario === 'late_reaction' || scenario === 'never_settle') {
  const install = () => {
    const v = document.querySelector('video'); if (!v) return false;
    const nativeCall = v.mozRequestDebugInfo.bind(v);
    let injected = false;
    v.mozRequestDebugInfo = function() {
      if (injected) return nativeCall();
      injected = true;
      browser.runtime.sendNativeMessage('kantvAvProbe', {kind:'injection',phase:scenario,elapsedMs:performance.now()}).catch(()=>{});
      if (scenario === 'never_settle') return new Promise(()=>{});
      const actual = nativeCall();
      const p=Promise.resolve(actual); const nativeThen=p.then;
      // The actual engine API is called once; only delivery of its .then reaction is intentionally late.
      p.then=function(ok,bad){ return nativeThen.call(p,value=>new Promise(resolve=>setTimeout(()=>resolve(ok(value)),1500)),bad); };
      return p;
    };
    return true;
  };
  const interval=setInterval(()=>{if(install())clearInterval(interval);},20);
}
