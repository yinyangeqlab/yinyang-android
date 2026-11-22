'use strict';
const main=document.querySelector('main');
let syncView='library',libraryType='eq',backupName='',backupKind='';
let library={eqs:[],measurements:[]}, model=false, job, output, linked=false, linkCode='', active='Sync';
let standalone=false;
let localMeasurement,localTarget,localLeft,localRight,localKind='';
const el=(tag,value,parent=main)=>{const n=document.createElement(tag);if(value!=null)n.textContent=value;parent.append(n);return n;};
function btn(label,fn,parent=main){const b=el('button',label,parent);b.onclick=fn;return b;}
function status(msg){document.querySelector('#status').textContent=msg;}
function init(plus,paired,bg,ink,panel,line,theme,autoEq=false,accent,muted,tint,withoutDevice=false){
  active=autoEq?'Auto EQ':'Sync';
  standalone=withoutDevice;
  model=plus;linked=paired;Object.entries({bg,ink,panel,line,accent,muted,tint}).forEach(([k,v])=>{if(v)document.body.style.setProperty('--'+k,v);});
  document.body.style.colorScheme=theme;draw();
}
let workerSource=bundledWorker;
function fitterUpdate(source){workerSource=source||bundledWorker;}
let staged, linkIssued=0, ended='', approvalTimer;
function stagePage(first,data){
 if(first)staged={eqs:[],measurements:[],epoch:data.epoch,revision:data.revision,writable:data.writable===true};
 staged.eqs.push(...data.eqs);staged.measurements.push(...data.measurements);
}
function receivedOffline(data){if(!data)return;library={...data,writable:false,offline:true};draw();status('Using the library saved on this phone.');}
function received(data){
 const keepFit=active==='Auto EQ' && (job || output);
 library=data;linked=true;linkCode='';ended='';clearTimeout(approvalTimer);
 if(!keepFit)draw();
 if(!keepFit || justSaved)status(justSaved?'Saved to My Collection.':'Synced from your browser account.');
 justSaved=false;if(library.writable)Phone.history(0);
}
function signedOut(){resetSyncedRanks();clearAccountSelection();syncView='library';libraryType='eq';backupName='';backupKind='';historyRows=[];historyNext=null;stopJob();library={eqs:[],measurements:[]};linked=false;linkCode='';ended='';clearTimeout(approvalTimer);draw();}
function pairing(code,issued){
 clearTimeout(approvalTimer);ended='';linkCode=code;linkIssued=issued;draw();
 approvalTimer=setTimeout(()=>{if(!linked && linkCode===code)requestEnded('expired');},Math.max(0,issued+600000-Date.now()));
}
function requestEnded(reason){
 signedOut();ended=reason;draw();
}
function stopJob(){if(job){job.terminate();job=null;}output=null;}
const someMatch=m=>m && (m.channelMatchPreset || (m.leftPoints?.length && m.rightPoints?.length));
function peq(bank){
  const fs=bank.filters;const rows=(fs.length?fs:[{hz:1000,gain:0,q:1,type:'PK'}]).map((f,i)=>
    'Filter '+(i+1)+': '+(fs.length?'ON':'OFF')+' '+(f.type||'PK')+' Fc '+f.hz+' Hz Gain '+f.gain+' dB Q '+f.q);
  return 'Preamp: '+Math.round(bank.preamp*10)/10+' dB\n'+rows.join('\n');
}
function channelPeq(channels){return channels.map((c,i)=>'Channel: '+(i?'R':'L')+'\n'+peq(c)).join('\n');}
function use(title,shared,channels){
  const match=channels&&channelPeq(channels);
  Phone.draft(JSON.stringify({name:title,shared:shared?peq(shared):'',match:match||''}));
}
function draftButton(label,load,parent){
 const button=btn(label,load,parent);button.disabled=standalone;return button;
}
function showTab(autoEq){
 const next=autoEq?'Auto EQ':'Sync';
 if(active!==next){stopJob();active=next;draw();window.scrollTo(0,0);}
}
function draw(){
 main.replaceChildren();main.className=active==='Auto EQ'?'auto-eq':'sync';el('h1',active);

 const msg=el('p','');msg.id='status';msg.setAttribute('role','status');
 if(active==='Auto EQ'){
   if(standalone)el('p','Generate and copy filters on this phone. Link a Qudelix in Device to use its controls.').className='standalone-note';
   fitForm();
   if(linked){
     const browse=btn('Browse saved EQs and channel matches',()=>{
       syncView='library';libraryType='eq';Phone.openSavedLibrary();
     });
     browse.className='sync-text';browse.disabled=!!job;
   }
   return;
 }
 if(!linked){
   const section=el('section');section.className='sync-link';
   if(linkCode){
     el('h2','Compare your code',section);
     el('p','Open the request in your signed-in browser. Confirm there when both codes match.',section);
     const code=el('strong',linkCode.slice(0,4)+' '+linkCode.slice(4),section);code.className='sync-code';
     el('small','Expires at '+new Date(linkIssued+600000).toLocaleTimeString([],{hour:'numeric',minute:'2-digit'}),section);
     btn('Open in-app browser',()=>Phone.approvalInApp(),section).className='sync-primary';
     const more=el('details',null,section);el('summary','Other linking options',more);
     btn('Open in another browser',()=>Phone.openApproval(),more);
     btn('Share approval link',()=>Phone.shareApproval(),more);
     btn('Check approval',()=>Phone.refresh(),more);
     btn('Get new code',()=>Phone.link(),more);
     btn('Wrong code or cancel',()=>Phone.cancelRequest(),section);
   }else{
     const messages={cancel:['Request cancelled','To try again, get a new code and open it in your browser.'],
       expired:['Code expired','Get a new code to start again.']};
     const copy=messages[ended]||['Link your library','Read your saved audio settings and choose presets or measurements to send to My Collection. Your link lasts until you disconnect or revoke it.'];
     el('h2',copy[0],section);el('p',copy[1],section);
     btn(ended?'Get new code':'Link browser account',()=>Phone.link(),section).className='sync-primary';
   }
   btn('Report issue',()=>Phone.reportIssue(),section).className='sync-text';
   return;
 }
 const controls=el('div');controls.className='sync-controls';
 el('strong',library.offline?'Library saved on this phone':'Browser account linked',controls);
 btn('Refresh',()=>Phone.refresh(),controls);
 const account=el('details',null,controls);el('summary','Account settings',account);
 btn('Manage browser devices',()=>Phone.browserDevices(),account);
 btn('Manage in My Collection',()=>Phone.collection(),account);
 btn('Disconnect',()=>Phone.disconnect(),account);
 segments(main,'Sync activity',[['library','My library'],['backup','Preset backups']],syncView,value=>{syncView=value;draw();});
 if(syncView==='backup'){
   if(library.writable)backupPanel();
   else el('p',library.offline?'Connect to the internet to save a backup or view its history.':'To enable backups on an older link, disconnect and approve a new code.');
   return;
 }
 const matches=[...library.measurements.map(m=>[m.name,m]),...library.eqs.map(e=>[e.name,e.channelMatch])].filter(([,m])=>someMatch(m));
 const groups=[['eq','EQs',library.eqs.length],['measurement','Measurements',library.measurements.length],['match','Channel matches',matches.length]];
 const categories=el('div');categories.className='library-categories';categories.setAttribute('aria-label','Library type');categories.setAttribute('role','group');
 for(const [key,title,count] of groups){
   const b=btn(title,()=>{libraryType=key;draw();},categories);
   b.setAttribute('aria-pressed',String(libraryType===key));b.setAttribute('aria-label',title+', '+count);
 }
 const [,title,count]=groups.find(([key])=>key===libraryType);
 el('h2',title+' ('+count+')');
 if(!count){el('p','No saved '+title.toLowerCase()+' in this library.');return;}
 const ears=m=>m.channelMatchPreset.channels.map(c=>({filters:c.filters,preamp:c.preampDb}));
 if(libraryType==='eq')for(const e of library.eqs){
   const a=el('article');el('strong',e.name,a);el('small',e.filters.length+' filters',a);
   draftButton('Load EQ',()=>use(e.name,e,null),a);
   if(e.channelMatch?.channelMatchPreset)draftButton('Load EQ and channel match',()=>use(e.name,e,ears(e.channelMatch)),a);
 }
 if(libraryType==='measurement')for(const m of library.measurements){
   const a=el('article');el('strong',m.name,a);el('small',[m.device,m.points.length+' frequency points'].filter(Boolean).join(' · '),a);
   btn('Use in Auto EQ',()=>{clearSelection();resetTarget();measurementSource='search';localKind=m.type||'';websiteMeasurement=m;searchOrigin='library';selectedMeasurement=m.id;confirmed=null;restoreOpened=false;Phone.openAutoEq();},a);
 }
 if(libraryType==='match')for(const [name,m] of matches){
   const a=el('article');el('strong',name,a);
   if(m.channelMatchPreset)draftButton('Load channel match',()=>use(name,null,ears(m)),a);
   if(m.leftPoints?.length && m.rightPoints?.length)el('small','Paired response available for Auto EQ.',a);
 }
}
function select(label,list,onchange){const wrap=el('label',label);const s=el('select',null,wrap);
 list.forEach(([name],i)=>{const o=el('option',name,s);o.value=i});s.onchange=onchange;return s;}
let historyRows=[],historyNext=null,justSaved=false;
function saveEntry(data){
 if(!library.writable){status('Reconnect with permission to save.');return;}
 const payload=JSON.stringify({...data,epoch:library.epoch,revision:library.revision});
 if(new TextEncoder().encode(payload).length>450000){status('This entry is too large to upload. The file is available on this phone.');return;}
 status('Saving to My Collection…');Phone.saveLibrary(payload);
}
function saveFinished(){justSaved=true;}
function accessPaused(){resetSyncedRanks();clearAccountSelection();stopJob();library={eqs:[],measurements:[]};historyRows=[];historyNext=null;draw();}
function receivedHistory(offset,data){
 historyRows=offset?[...historyRows,...data.history]:data.history;historyNext=data.next;
 const list=document.querySelector('#phone-history');if(list)renderHistory(list);
}
function renderHistory(list){
 list.replaceChildren();if(!historyRows.length)el('p','No preset backups yet.',list);
 for(const row of historyRows){
  const entry=el('article',null,list);el('strong',row.name,entry);
  el('small',(row.versionRank===1?'Current':'Previous')+' · '+row.model+' · '+new Date(row.savedAt).toLocaleString(),entry);
  const restore=(main,match)=>Phone.draft(JSON.stringify({name:row.name,shared:main,match}));
  if(row.main)draftButton('Load '+(row.mainName||'Main EQ')+' (USR)',()=>restore(row.main,''),entry);
  if(row.match)draftButton('Load '+(row.matchName||'Channel Match')+' (SPK)',()=>restore('',row.match),entry);
  if(row.main && row.match)draftButton('Load pair',()=>restore(row.main,row.match),entry);
 }
 if(historyNext!=null)btn('Load more versions',()=>Phone.history(historyNext),list);
}
function backupPanel(){
 el('h2','Back up a preset');
 const draft=JSON.parse(Phone.editorPreset()),kinds=[];
 if(draft.main&&draft.match)kinds.push(['Paired Main EQ and Channel Match','pair']);
 if(draft.main)kinds.push(['Main EQ (USR)','main']);
 if(draft.match)kinds.push(['Channel Match (SPK)','match']);
 if(kinds.length){
  const kind=select('Stages to back up',kinds,()=>{backupKind=kinds[kind.selectedIndex][1];}),label=el('label','Backup name'),name=el('input',null,label);
  const previous=kinds.findIndex(([,key])=>key===backupKind);if(previous>=0)kind.selectedIndex=previous;
  name.maxLength=100;name.value=backupName||draft.mainName||draft.matchName||'';
  name.oninput=()=>{backupName=name.value;};
  btn('Back up preset',()=>{if(!name.value.trim()){name.focus();status('Enter a backup name.');return;}
   saveEntry({...draft,action:'backup',model:model?'5K Plus':'5K',id:crypto.randomUUID(),name:name.value.trim(),kind:kinds[kind.selectedIndex][1]});});
  el('small','Use the same name and preset type to keep a version history. Edits stay on this phone until you save.');
 }else el('p',standalone?'Link a Qudelix to back up its editor settings.':'Open Filters, then Sync, to back up the current editor settings.');
 el('h2','Preset history');const list=el('div');list.id='phone-history';renderHistory(list);
}
