export const VIEWER_HTML = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <meta name="robots" content="noindex,nofollow,noarchive">
  <title>Debrief shared sets</title>
  <link rel="stylesheet" href="/assets/viewer.css">
</head>
<body>
  <main id="app" aria-live="polite"><p class="loading">Opening private share…</p></main>
  <script src="/assets/viewer.js" defer></script>
</body>
</html>`;

export const VIEWER_CSS = `:root{color-scheme:light;--ink:#17231d;--muted:#637168;--green:#164e3d;--wash:#f3f5ef;--card:#fff;--line:#dce3dc;--comment:#eef2ff}*{box-sizing:border-box}body{margin:0;background:var(--wash);color:var(--ink);font:16px/1.5 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}main{width:min(780px,100%);margin:auto;padding:24px 16px 64px}h1{font-size:clamp(1.6rem,6vw,2.3rem);line-height:1.15;margin:8px 0}.eyebrow,.expiry,.meta,.timestamp,.speaker{color:var(--muted);font-size:.88rem}.share-head{margin:8px 0 24px}.set-card{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:18px;margin:16px 0;box-shadow:0 5px 22px #153d2820}.set-card h2{margin:0 0 4px;font-size:1.25rem}.set-card audio{width:100%;margin:14px 0}.transcript{list-style:none;padding:0;margin:8px 0}.segment,.comment{border:0;width:100%;text-align:left;background:transparent;color:inherit;padding:12px 8px;border-radius:12px;font:inherit;cursor:pointer}.segment:hover,.segment:focus-visible{background:#f2f7f3;outline:2px solid var(--green)}.speaker{display:inline-block;font-weight:700;margin-right:8px}.timestamp{font-variant-numeric:tabular-nums}.text{display:block;margin-top:4px}.comments{border-top:1px solid var(--line);margin-top:12px;padding-top:10px}.comment{background:var(--comment);margin:8px 0}.comment strong{display:block}.loading,.notice{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:18px}.pin-form{background:var(--card);padding:22px;border-radius:18px}.pin-form label{display:block;font-weight:700}.pin-form input{width:100%;font:inherit;padding:12px;margin:8px 0;border:1px solid var(--line);border-radius:10px}.pin-form button{background:var(--green);color:#fff;border:0;border-radius:999px;padding:12px 20px;font-weight:700}.error{color:#8d2525}@media(prefers-reduced-motion:no-preference){.set-card{animation:rise .25s ease-out}@keyframes rise{from{opacity:0;transform:translateY(8px)}}}`;

export const VIEWER_JS = `(()=>{
  const app=document.getElementById('app');
  const token=location.pathname.split('/').filter(Boolean)[1]||'';
  const fmt=ms=>{const total=Math.max(0,Math.floor(ms/1000));const h=Math.floor(total/3600),m=Math.floor((total%3600)/60),s=total%60;return h?h+':'+String(m).padStart(2,'0')+':'+String(s).padStart(2,'0'):m+':'+String(s).padStart(2,'0')};
  const node=(tag,cls,text)=>{const el=document.createElement(tag);if(cls)el.className=cls;if(text!=null)el.textContent=text;return el};
  function unavailable(){app.replaceChildren(node('div','notice','This Debrief share is unavailable, expired, or has been revoked.'))}
  function pin(){
    const form=node('form','pin-form'),title=node('h1','','Enter share PIN'),label=node('label','','PIN'),input=node('input'),error=node('p','error'),button=node('button','','Unlock');
    input.type='password';input.inputMode='numeric';input.autocomplete='one-time-code';input.required=true;input.minLength=6;input.maxLength=12;
    form.append(title,label,input,error,button);
    form.addEventListener('submit',async event=>{event.preventDefault();button.disabled=true;error.textContent='';const response=await fetch('/v1/public/'+encodeURIComponent(token)+'/unlock',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({pin:input.value})});button.disabled=false;if(response.ok){load()}else{const body=await response.json().catch(()=>null);error.textContent=body?.error?.message||'That PIN did not work.'}});
    app.replaceChildren(form);input.focus();
  }
  function seek(player,ms){player.currentTime=Math.max(0,ms/1000);player.play().catch(()=>{})}
  function render(data){
    document.title=data.title+' — Debrief';
    const head=node('header','share-head');head.append(node('div','eyebrow','Shared from Debrief'),node('h1','',data.title),node('div','expiry','Available until '+new Date(data.expiresAt).toLocaleString()));
    const fragment=document.createDocumentFragment();fragment.append(head);
    data.sets.forEach((set,index)=>{
      const card=node('section','set-card'),heading=node('h2','',set.title);heading.id='set-'+index;
      const meta=node('div','meta',fmt(set.durationMs)+' · '+set.comments.length+' comment'+(set.comments.length===1?'':'s'));
      const player=document.createElement('audio');player.controls=true;player.preload='metadata';player.controlsList='nodownload';player.setAttribute('disableRemotePlayback','');player.src=set.audioUrl;card.append(heading,meta,player);
      const list=node('ol','transcript');set.segments.forEach(segment=>{const item=node('li'),button=node('button','segment');button.type='button';button.append(node('span','speaker',segment.speaker),node('span','timestamp',fmt(segment.startMs)),node('span','text',segment.text));button.addEventListener('click',()=>seek(player,segment.startMs));item.append(button);list.append(item)});card.append(list);
      if(set.comments.length){const comments=node('section','comments');comments.append(node('h3','','Comments'));set.comments.forEach(comment=>{const button=node('button','comment');button.type='button';button.append(node('strong','',fmt(comment.timestampMs)),node('span','text',comment.text));button.addEventListener('click',()=>seek(player,comment.timestampMs));comments.append(button)});card.append(comments)}
      fragment.append(card);
    });
    app.replaceChildren(fragment);
  }
  async function load(){const response=await fetch('/v1/public/'+encodeURIComponent(token),{headers:{Accept:'application/json'}});if(response.status===401){pin();return}if(!response.ok){unavailable();return}render(await response.json())}
  load().catch(unavailable);
})();`;
