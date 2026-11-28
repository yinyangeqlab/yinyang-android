let measurementSource='',targetMode='',singleFile,websiteMeasurement,websiteTarget,targetFile;
let confirmed=null,localChoice='',searchOrigin='',uploadMessage='';
function resetTarget(){targetSearchOpen=false;targetMode='';websiteTarget=null;targetFile=null;requestId++;waiting=null;searchQuery='';searchMessage='';}
function editMeasurement(){confirmed=null;uploadMessage='';resetTarget();restoreOpened=false;draw();}
function clearSelection(){singleFile=localLeft=localRight=websiteMeasurement=null;localChoice='';selectedMeasurement='';searchOrigin='';}
function clearAccountSelection(){
 if(searchOrigin==='library'){websiteMeasurement=null;selectedMeasurement='';searchOrigin='';confirmed=null;resetTarget();}
 else if(targetMode==='search'){websiteTarget=null;}
}
function radios(parent,title,items,current,change){
 const group=el('fieldset',null,parent);group.className='fit-choices';el('legend',title,group);
 for(const [key,name] of items){
   const label=el('label',null,group),input=el('input',null,label);input.type='radio';input.name=title;
   input.checked=key===current;input.onchange=()=>change(key);el('span',name,label);
 }
}
let selectedMeasurement='',selectedTarget='',selectedMatch='auto',comparison,restoreOpened=false;
let indexRows=[],indexPartial=false,searchQuery='',requestId=0,waiting=null,searchMessage='';
const offlineMessage='Please restore your internet connection to resume indexing';
const fixed=(db,n=2)=>Number(db).toFixed(n);
function pane(parent,heading){
 const panel=el('section',null,parent);panel.className='fit-pane';el('h2',heading,panel);return panel;
}
function fitDetailText(value){
 return typeof value==='string'?value.replace(/[\p{Cc}\p{Cf}]+/gu,' ').trim().replace(/\s+/g,' ').slice(0,160):'';
}
function fitDeviceName(measurement){
 return fitDetailText(measurement.device || measurement.name).replace(/\.(?:txt|csv|tsv)$/i,'');
}
function measurementDetails(parent,measurement){
 const details=el('details',null,parent);details.className='fit-measurement-details';
 el('summary','Preset details (optional)',details);
 const fields=[['device',measurement.type==='ie'?'IEM':'Headphones']];
 if(measurement.type==='ie')fields.push(['tips','Ear tips'],['size','Tip size']);
 for(const [key,title] of fields){
   const field=el('input',null,el('label',title,details));
   field.maxLength=160;
   field.value=measurement.fitDetails[key];
   field.oninput=()=>{measurement.fitDetails[key]=field.value;};
 }
}
function phoneFitPreset(saved,option,text){
 const details={
   device:fitDetailText(saved.fitDetails?.device)||fitDeviceName(saved),
   tips:fitDetailText(saved.fitDetails?.tips),
   size:fitDetailText(saved.fitDetails?.size),
   target:fitDetailText(saved.target).replace(/\.(?:txt|csv|tsv)$/i,''),
   fitter:fitDetailText(option.label+(option.fallback?' · fallback':'')),
 };
 const shortened=(value,limit)=>value.length>limit
   ?value.slice(0,limit-1).replace(/[\uD800-\uDBFF]$/,'').trimEnd()+'…':value;
 const sizeKey=details.size.toLowerCase().replace(/[\s-]+/g,'');
 const sizes=new Map([['extrasmall','XS'],['small','S'],['mediumsmall','MS'],['smallmedium','MS'],['medium','M'],
   ['mediumlarge','ML'],['largemedium','ML'],['large','L'],['extralarge','XL']]);
 const size=sizes.get(sizeKey)
   || (/^(?:xxs|xs|ss|s|ms|m|ml|l|ll|xl|xxl)$/i.test(details.size)?details.size.toUpperCase():details.size);
 const fitter=({standard:'Std',precise:'Prec',peqdb:'PEQdB-U','peqdb-smoothed':'PEQdB-S',joint:'All-Band'})[option.method];
 const fitName=[shortened(details.device,28),
   [shortened(details.tips,15),shortened(size,7)].filter(Boolean).join(' '),
   shortened(details.target,24),
   fitter&&option.bands?fitter+' '+option.bands+'b':shortened(details.fitter,15)].filter(Boolean).join(' · ');
 return {name:fitName||'Generated EQ',text:'# YinYang-Fit: '+JSON.stringify(details)+'\n'+text};
}
function segments(parent,label,items,selected,change){
 const group=el('div',null,parent);group.className='segments';group.setAttribute('role','group');group.setAttribute('aria-label',label);
 for(const [value,title] of items){const b=btn(title,()=>{if(value!==selected)change(value);},group);b.setAttribute('aria-pressed',String(value===selected));}
}
function choice(parent,label,items,key,change){
 const l=el('label',label,parent),s=el('select',null,l);
 for(const [id,title] of items){const o=el('option',title,s);o.value=id;o.selected=id===key;}
 s.onchange=()=>change(s.value);return s;
}
function pickFile(parent,title,current,change,{disabled=false,stereo=false}={}){
 const wrap=el('div',null,parent),label=el('label',null,wrap);label.className='curve-upload';
 label.setAttribute('aria-disabled',String(disabled));
 el('strong',title,label);el('span',current?.name||'Choose file',label);
 const input=el('input',null,label);input.type='file';input.accept='.txt,.csv,.tsv,text/plain,text/csv';input.setAttribute('aria-label',title);
 input.disabled=disabled;
 input.onchange=async()=>{const file=input.files[0];if(!file)return;try{
   if(file.size>4000000)throw Error('Choose a file under 4 MB.');
   const raw=await file.text();
   if(!input.isConnected)return;
   change(stereo?singleMeasurement(file.name,raw):{name:file.name,points:Curves.parseCurve(raw,false)});
 }catch(err){status(err.message||'Could not read the curve.');}};
 if(current){const clear=btn('Remove file',()=>change(null),wrap);clear.className='clear-file';clear.setAttribute('aria-label','Remove '+title);}
}
function invalidate(){stopJob();restoreOpened=false;draw();}
function stereoMeasurement(name,left,right){
 const l=Curves.at(left,1000),r=Curves.at(right,1000);
 return {name,points:Curves.averageChannels(left,right),leftPoints:left.map(([hz,v])=>[hz,v-l]),
  rightPoints:right.map(([hz,v])=>[hz,v-r]),leftLevelDb:l,rightLevelDb:r};
}
function singleMeasurement(name,raw){
 const lines=raw.split(/\r?\n/);
 for(let i=0;i<lines.length;i++){
   const clean=lines[i].trim().replace(/^[#*]\s*/, '');
   if(!/\b(?:left|right|l|r)\b/i.test(clean))continue;
   const sep=/[,;\t]/.test(clean)?/[,;\t]/:/\s+/;
   const cols=clean.split(sep).map(x=>x.replace(/["']/g,'').trim());
   const hz=cols.findIndex(x=>/^(?:freq(?:uency)?|hz)\b/i.test(x));
   const l=cols.findIndex(x=>/^(?:left|l)\b/i.test(x)),r=cols.findIndex(x=>/^(?:right|r)\b/i.test(x));
   if(hz<0 || l<0 || r<0)continue;
   const left=[],right=[];
   for(const line of lines.slice(i+1)){
     const cells=line.trim().split(sep).map(x=>x.trim());
     if(!cells[hz] || !Number.isFinite(Number(cells[hz])))continue;
     if(!cells[l] || !cells[r] || !Number.isFinite(Number(cells[l])) || !Number.isFinite(Number(cells[r])))throw Error('Each stereo row needs a Left and Right level.');
     left.push(cells[hz]+','+cells[l]);right.push(cells[hz]+','+cells[r]);
   }
   return stereoMeasurement(name,Curves.parseCurve(left.join('\n'),false),Curves.parseCurve(right.join('\n'),false));
 }
 return {name,points:Curves.parseCurve(raw,false)};
}
function pairedMeasurement(){
 return localLeft && localRight?stereoMeasurement(localLeft.name+' + '+localRight.name,localLeft.points,localRight.points):null;
}
function online(){return Phone.onlineForTargets();}
function phoneMeasurements(){
 try{return JSON.parse(Phone.phoneMeasurements()).filter(row=>row.type===localKind);}
 catch{return [];}
}
let catalogView='headphones',hitLimit=30,headphoneRows=publicHeadphones,headphonesLoaded=false;
let targetSearchOpen=false,rankOrder='global',personalRanks=new Map(),rankState=null;
let rankRequest=0,syncedRuns=[],selectedRun=0,refreshRankControls=null;
let indexLoaded=false,indexRanked=false;
const alphabetic=new Intl.Collator(undefined,{numeric:true,sensitivity:'base'});
function rankOf(row){
 if(rankOrder==='global')return row.globalRank;
 return personalRanks?.get(row.id) ?? personalRanks?.get(row.localId);
}
function targetOrder(a,b){
 return (rankOf(a)??Infinity)-(rankOf(b)??Infinity)
   || (a.globalRank??Infinity)-(b.globalRank??Infinity)
   || alphabetic.compare(a.name,b.name) || alphabetic.compare(a.credit||'',b.credit||'');
}
function resultsForSearch(parent){
 parent.replaceChildren();
 const words=searchQuery.trim().toLowerCase().split(/\s+/).filter(Boolean);
 function section(title,rows,select,{rank=false,enabled=true,limited=false}={}){
   const found=rows.filter(t=>{
     const hay=[t.name,t.credit,t.source,t.rig,t.device].filter(Boolean).join(' ').toLowerCase();
     return words.every(w=>hay.includes(w));
   }).sort(rank?targetOrder:(a,b)=>alphabetic.compare(a.name,b.name)||alphabetic.compare(a.credit||'',b.credit||''));
   el('h3',title+' ('+found.length+')',parent);
   if(!found.length){el('small','No matches',parent);return;}
   for(const row of limited?found.slice(0,hitLimit):found){
     const b=btn(null,()=>select(row),parent);b.disabled=!enabled;
     b.className='catalog-result';
     const position=rank?rankOf(row):null;
     if(position!=null){
       const badge=el('span','#'+position,b);badge.className='catalog-rank';
       badge.setAttribute('aria-label',(rankOrder==='personal'?'Personal rank ':'Global rank ')+position);
     }
     const title=el('span',row.name,b);
     if(row.credit||row.rig)el('small',row.credit||row.rig,title);
   }
   if(limited && found.length>hitLimit)
     btn('Show more',()=>{const offset=parent.scrollTop;hitLimit+=30;resultsForSearch(parent);parent.scrollTop=offset;},parent);
 }
 const connected=online(), head=catalogView==='headphones';
 if(head){
   const local=phoneMeasurements();
   if(local.length)section('Saved on this phone',local,row=>{
     try{
       const measurement=JSON.parse(Phone.phoneMeasurement(row.id));
       if(!measurement?.points?.length)throw Error('The saved measurement has no curve.');
       requestId++;waiting=null;websiteMeasurement=measurement;selectedMeasurement=row.id;searchOrigin='phone';invalidate();
     }catch{status('Could not open that saved measurement.');}
   },{limited:true});
   const saved=library.measurements.filter(m=>!m.type || m.type===localKind);
   if(saved.length)section('My Collection',saved,row=>{
     requestId++;waiting=null;websiteMeasurement=row;selectedMeasurement=row.id;searchOrigin='library';invalidate();
   },{limited:true});
 }
 const savedTargets=head?[]:library.eqs.filter(e=>e.target?.points?.length).map(e=>({...e.target,name:e.target.name||e.name}));
 if(waiting){
   const message=waiting.key && waiting.key!=='headphone:index'?'Loading curve…':head?'Loading headphone catalog…':'Loading targets and global rankings…';
   el('p',message,parent).setAttribute('role','status');
   if(savedTargets.length)section('Saved targets',savedTargets,chooseTarget);
   return;
 }
 if(!connected)el('p',offlineMessage,parent);
 else if(searchMessage)el('p',searchMessage,parent).setAttribute('role','status');
 else if(!head && indexPartial)el('p','Part of the website target catalog is unavailable.',parent).setAttribute('role','status');
 else if(!head && indexLoaded && !indexRanked && rankOrder==='global')
   el('p','Global rankings are temporarily unavailable.',parent).setAttribute('role','status');
 if(connected && (searchMessage || !head && indexLoaded && (indexPartial || !indexRanked)))
   btn('Retry',()=>requestTarget(null),parent);
 if(head){
   section(localKind==='ie'?'Public IEMs':'Public headphones',headphoneRows.filter(row=>row.type===localKind),
     requestTarget,{limited:true,enabled:connected});
 }else{
   if(indexRanked && rankOrder==='global'){
     const ranked=indexRows.some(row=>row.globalRank!=null);
     el('p',ranked?'Global ranks use the website’s All results view, with matched A/B and earlier blind comparisons.'
       :'No targets have a global rank yet.',parent).setAttribute('role','status');
   }
   if(indexRows.length)section('Targets',indexRows,requestTarget,{rank:true,enabled:connected});
   if(savedTargets.length)section('Saved targets',savedTargets,chooseTarget);
 }
}
function requestTarget(row){
 const list=document.querySelector('#target-hits');
 if(!list || waiting || !online())return;
 const key=row?.key ?? (catalogView==='headphones'?'headphone:index':'');
 waiting={id:++requestId,key,name:row?.name,credit:row?.credit,catalog:catalogView,rows:[]};
 searchMessage='';
 if(catalogView==='headphones' && row)draw();else resultsForSearch(list);
 Phone.targetRequest(waiting.id,key);
}
function headphoneIndexPage(id,rows){
 if(waiting?.id===id && waiting.key==='headphone:index')waiting.rows.push(...rows);
}
function targetReply(id,data,error){
 if(!waiting || waiting.id!==id)return;
 const request=waiting;waiting=null;const list=document.querySelector('#target-hits');
 if(active!=='Auto EQ' || (!list && request.catalog!=='headphones'))return;
 try{
   if(error)throw Error(error);
   if(request.key==='headphone:index'){headphoneRows=request.rows;headphonesLoaded=true;}
   else if(request.key){
     if(request.catalog==='headphones'){
       const left=Curves.parseCurve(data.csv,false);
       websiteMeasurement=data.rightCsv?stereoMeasurement(request.name,left,Curves.parseCurve(data.rightCsv,false)):{name:request.name,points:left};
       websiteMeasurement.credit=request.credit;
       selectedMeasurement='';searchOrigin='public';invalidate();return;
     }
     let points=data.points;
     if(!points){
       const text=data.column<0?data.csv:data.csv.trim().split(/\r?\n/).slice(1).map(line=>{const cells=line.split(',');return cells[0]+','+cells[data.column+1];}).join('\n');
       points=Curves.parseCurve(text);
     }
     if(!Array.isArray(points)||points.length<2)throw Error('No compatible target points.');
     chooseTarget({name:request.name,points});return;
   }else{
     indexRows=data.rows;indexPartial=data.partial;indexRanked=data.ranked;indexLoaded=true;
   }
 }catch(e){searchMessage=request.key==='headphone:index'?'Could not refresh the site catalog. Showing the included catalog.':e.message;}
 if(list)resultsForSearch(list);else draw();
}
function searchBox(parent,catalog){
 catalogView=catalog;
 const label=el('label',catalog==='headphones'?'Search headphones':null,parent);
 const field=el('input',null,label);
 field.type='search';field.placeholder=catalog==='headphones'?'Model, source or rig':'Name or creator';field.value=searchQuery;
 if(catalog==='targets')field.setAttribute('aria-label','Search targets by name or creator');
 const list=el('div',null,parent);list.id='target-hits';list.className='target-hits';list.setAttribute('aria-label','Search results');
 field.oninput=()=>{searchQuery=field.value;hitLimit=30;resultsForSearch(list);list.scrollTop=0;};
 field.onkeydown=event=>{
   if(event.key==='ArrowDown'){
     const first=list.querySelector('button:not(:disabled)');if(first){event.preventDefault();first.focus();}
   }
 };
 resultsForSearch(list);
 const loaded=catalog==='headphones'?headphonesLoaded:indexLoaded;
 if(!loaded && online() && !waiting && !searchMessage)requestTarget(null);
 return list;
}
function closeTargetSearch(){
 if(!targetSearchOpen)return false;
 targetSearchOpen=false;refreshRankControls=null;
 if(waiting?.catalog==='targets'){requestId++;waiting=null;}
 searchQuery='';searchMessage='';
 const dialog=document.querySelector('.target-lookup');
 if(dialog?.open)dialog.close();
 document.querySelector('.open-target-lookup')?.focus();
 return true;
}
function chooseTarget(row){
 websiteTarget=row;closeTargetSearch();invalidate();
 document.querySelector('.open-target-lookup')?.focus();
}
function resetSyncedRanks(){
 rankRequest++;rankState=null;personalRanks=new Map();syncedRuns=[];selectedRun=0;
}
function fetchSyncedRanks(){
 if(!linked || rankState==='loading')return;
 rankState='loading';
 Phone.syncedPersonalRanks(++rankRequest);
}
function setPersonalRun(index){
 selectedRun=index;
 personalRanks=new Map(Object.entries(syncedRuns[index]?.positions||{}));
}
function syncedRanksReply(request,data,error){
 if(request!==rankRequest || !linked)return;
 if(error || data?.requiresApproval){
   syncedRuns=[];setPersonalRun(0);rankState=error||'approval';
 }
 else{
   syncedRuns=data?.personalRankings||[];
   const measurement=confirmed?.id;
   const match=measurement?syncedRuns.findIndex(run=>run.measurementId===measurement):-1;
   setPersonalRun(match<0?0:match);
   rankState='ready';
 }
 refreshRankControls?.();
}
function personalRankControls(parent,refresh){
 const message=value=>el('small',value,parent).setAttribute('role','status');
 const sync=()=>{
   closeTargetSearch();
   Phone.openSavedLibrary();
 };
 if(!linked){
   message('Link your browser in Sync to load your Personal Tournaments.');
   btn('Open Sync',sync,parent);
 }else if(rankState==='approval'){
   message('Reconnect your browser in Sync to approve Personal rank access.');
   btn('Open Sync',sync,parent);
 }else if(rankState==='ready'){
   if(syncedRuns.length){
     choice(parent,'Personal Tournament',syncedRuns.map((run,i)=>[String(i),run.label||'Tournament']),String(selectedRun),index=>{
       setPersonalRun(Number(index));
       const results=document.querySelector('#target-hits');
       if(results){resultsForSearch(results);results.scrollTop=0;}
     });
   }else message('No ranked Personal Tournaments have synced yet.');
   btn('Refresh ranks',()=>{fetchSyncedRanks();refresh();},parent);
 }else if(rankState==='loading' || rankState===null){
   if(rankState===null)fetchSyncedRanks();
   message('Loading synced rankings…');
 }else{
   message(rankState);
   btn('Retry',()=>{fetchSyncedRanks();refresh();},parent);
 }
}
function renderTargetLookup(parent){
 if(websiteTarget)el('strong',websiteTarget.name,parent);
 const open=btn(websiteTarget?'Change target':'Choose target',()=>{
   targetSearchOpen=true;searchQuery='';searchMessage='';draw();
 },parent);
 open.className='open-target-lookup';open.setAttribute('aria-haspopup','dialog');
 if(!targetSearchOpen)return;
 const dialog=el('dialog',null,parent);dialog.className='target-lookup';
 const title=el('h2','Choose a target',dialog);title.id='target-lookup-title';
 dialog.setAttribute('aria-labelledby',title.id);
 const done=btn('Close',closeTargetSearch,dialog);done.autofocus=true;done.className='target-lookup-close';
 dialog.addEventListener('cancel',event=>{event.preventDefault();closeTargetSearch();});
 const rankArea=el('section',null,dialog);
 let list;
 function renderRankControls(){
   rankArea.replaceChildren();
   segments(rankArea,'Rank targets by',[['global','Global rank'],['personal','Personal rank']],rankOrder,value=>{
     rankOrder=value;renderRankControls();
     resultsForSearch(list);list.scrollTop=0;
     rankArea.querySelector('[aria-pressed=true]')?.focus();
   });
   if(rankOrder==='personal'){
     personalRankControls(rankArea,refreshRankControls);
   }
 }
 refreshRankControls=()=>{
   if(!dialog.open || rankOrder!=='personal')return;
   renderRankControls();resultsForSearch(list);
 };
 renderRankControls();
 list=searchBox(dialog,'targets');
 dialog.showModal();
}
window.addEventListener('offline',()=>{
 const list=document.querySelector('#target-hits');
 if(!list && waiting?.catalog!=='headphones')return;
 requestId++;waiting=null;searchMessage='';
 if(list)resultsForSearch(list);else draw();
});
window.addEventListener('online',()=>{
 const list=document.querySelector('#target-hits');
 if(list){resultsForSearch(list);if(!(catalogView==='headphones'?headphonesLoaded:indexLoaded))requestTarget(null);}
});
function chart(parent,series){
 const canvas=el('canvas',null,parent);canvas.width=700;canvas.height=156;canvas.className='fit-chart';
 canvas.setAttribute('role','img');canvas.setAttribute('aria-label',series.map(s=>s.name).join(' and ')+' frequency response from 20 Hz to 20 kHz, aligned at 1 kHz');
 const ctx=canvas.getContext('2d');if(!ctx)return;
 const curves=series.map(s=>{const ref=Curves.at(s.points,1000);return Array.from({length:161},(_,i)=>Curves.at(s.points,20*1000**(i/160))-ref);});
 const extent=Math.ceil(Math.max(3,...curves.flat().map(Math.abs))),x=i=>65+614*i/160,y=v=>66-53*v/extent;
 const styles=getComputedStyle(document.body),ink=styles.getPropertyValue('--ink').trim()||'#edf0f5';
 ctx.strokeStyle=ink;ctx.globalAlpha=.2;ctx.beginPath();ctx.moveTo(65,66);ctx.lineTo(679,66);ctx.stroke();ctx.globalAlpha=1;
 ctx.fillStyle=ink;ctx.font='18px sans-serif';ctx.textAlign='right';
 for(const [v,label] of [[extent,'+'+Math.ceil(extent)],[0,'0 dB'],[-extent,'−'+Math.ceil(extent)]])ctx.fillText(label,59,y(v)+5);
 for(const [hz,label] of [[20,'20 Hz'],[1000,'1 kHz'],[20000,'20 kHz']]){
   ctx.textAlign=hz===20?'left':hz===20000?'right':'center';ctx.fillText(label,x(160*Math.log(hz/20)/Math.log(1000)),148);
 }
 const legend=el('div',null,parent);legend.className='plot-key';
 series.forEach((s,j)=>{
   ctx.strokeStyle=j?'#e0a24a':document.body.style.colorScheme==='light'?'#167174':'#63c4bd';ctx.lineWidth=3;ctx.setLineDash(j?[9,5]:[]);ctx.beginPath();
   curves[j].forEach((v,i)=>{if(!i)ctx.moveTo(x(i),y(v));else ctx.lineTo(x(i),y(v));});ctx.stroke();
   const key=el('span',s.name,legend);key.className=j?'target-key':'measurement-key';
 });
}
function fitForm(){
 if(comparison===undefined){try{comparison=JSON.parse(Phone.lastComparison());}catch{comparison=null;}}
 if(comparison?.version===2 || Array.isArray(comparison)&&comparison.length){
   const last=el('section');last.className='previous-fit';
   const open=btn(restoreOpened?'Close last comparison':'Open last comparison',()=>{restoreOpened=!restoreOpened;draw();},last);
   open.setAttribute('aria-expanded',String(restoreOpened));
   el('small',comparison.version===2?comparison.name+' · '+new Date(comparison.time).toLocaleDateString():'Previously saved choices',last);
   if(restoreOpened){
     if(comparison.version===2)renderComparison(comparison,main);
     else for(const c of comparison)draftButton(c.name,()=>use(c.name,c.shared,c.channels),last);
     return;
   }
 }
 const input=pane(main,'1. Select a measurement');
 if(!confirmed){
   radios(input,'Device type',[['ie','IEM'],['oe','Headphone']],localKind,kind=>{
     clearSelection();measurementSource='';localKind=kind;editMeasurement();
   });
   if(!localKind)return;
   const methods=[['search','Search the index'],['local','Add your own measurement']];
   const stored=phoneMeasurements();
   if(stored.length)methods.push(['phone','Use a file saved on this phone']);
   radios(input,'Measurement source',methods,measurementSource,source=>{
     clearSelection();measurementSource=source;editMeasurement();
   });
   let candidate;
   if(measurementSource==='search'){
     candidate=websiteMeasurement;
     const selected=candidate || (waiting?.catalog==='headphones'?waiting:null);
     if(selected){
       const summary=el('div',null,input);summary.className='selected-headphone';
       el('strong',selected.name,summary);
       const source=searchOrigin==='phone'?'Saved on this phone':searchOrigin==='library'?
         ['My Collection',selected.rig].filter(Boolean).join(' · '):selected.credit;
       if(source)el('small',source,summary);
       if(!candidate)el('small','Loading measurement…',summary).setAttribute('role','status');
       btn('Change selection',()=>{
         requestId++;waiting=null;searchMessage='';
         websiteMeasurement=null;searchOrigin='';selectedMeasurement='';invalidate();
         const field=document.querySelector('input[type=search]');if(field)field.focus();
       },summary).className='clear-file';
     }else searchBox(input,'headphones');
   }else if(measurementSource==='local'){
     el('small','For Bluetooth listening, measure your headphones with the codec you normally use. Auto EQ can compensate for repeatable changes in frequency response. EQ can’t recover detail lost to lossy codecs such as LC3.',input);
     pickFile(input,'Single measurement',singleFile,file=>{singleFile=file;invalidate();},{disabled:!!(localLeft||localRight),stereo:true});
     el('small','One response, or a file with Frequency, Left and Right columns.',input);
     el('div','or',input).className='measurement-or';el('h3','Left and Right',input);
     const ears=el('div',null,input);ears.className='ear-files';
     pickFile(ears,'Left ear',localLeft,file=>{localLeft=file;invalidate();},{disabled:!!singleFile});
     pickFile(ears,'Right ear',localRight,file=>{localRight=file;invalidate();},{disabled:!!singleFile});
     candidate=singleFile||pairedMeasurement();
     if(!!localLeft!==!!localRight)el('small','Attach both ears to continue.',input);
   }else if(measurementSource==='phone'){
     for(const file of stored)btn(file.name,()=>{localChoice=file.id;invalidate();},input).setAttribute('aria-pressed',String(localChoice===file.id));
     if(localChoice)try{candidate=JSON.parse(Phone.phoneMeasurement(localChoice));}catch{}
   }else return;
   const attachment=measurementSource!=='search';
   if(attachment)el('small',linked && library.writable && online()?'Using this measurement saves it on this phone and uploads it to My Collection with your approved Sync link.':'This file stays on this phone. Link a browser with upload permission in Sync to save it to My Collection later.',input);
   const set=btn('Use this measurement',()=>{
     if(!candidate?.points?.length)return;
     const m={...candidate,type:localKind};
     if(attachment){
       if(!Phone.keepMeasurement(JSON.stringify(m))){status('Could not save this file on your phone. Check available storage.');return;}
       uploadMessage='Saved on this phone.';
     }
     m.fitDetails={device:fitDeviceName(m),tips:fitDetailText(m.tips),size:fitDetailText(m.tipSize)};
     confirmed=m;requestId++;waiting=null;searchQuery='';searchMessage='';hitLimit=30;draw();
     if(attachment && linked && library.writable && online()){
       const match=m.leftPoints?{leftPoints:m.leftPoints,rightPoints:m.rightPoints,leftLevelDb:m.leftLevelDb,rightLevelDb:m.rightLevelDb}:null;
       const same=library.measurements.some(x=>x.name===m.name.slice(0,80) && x.type===localKind && ['points','leftPoints','rightPoints','leftLevelDb','rightLevelDb'].every(k=>JSON.stringify(x[k]??null)===JSON.stringify(m[k]??null)));
       if(!same)saveEntry({action:'measurement',name:m.name.slice(0,80),device:m.name.slice(0,120),type:localKind,points:m.points,...(match?{match}:{})});
     }
   },input);set.className='fit-primary';set.disabled=!candidate?.points?.length;
   return;
 }
 el('strong',confirmed.name,input);el('small',[localKind==='ie'?'IEM':'Headphone',uploadMessage].filter(Boolean).join(' · '),input);
 measurementDetails(input,confirmed);
 btn('Change measurement',editMeasurement,input).className='clear-file';
 const targetPanel=pane(main,'2. Choose a target response');
 radios(targetPanel,'Target source',[['search','Search target curves'],['file','Attach target file']],targetMode,key=>{
   resetTarget();targetMode=key;targetSearchOpen=key==='search';invalidate();
 });
 let t;
 if(targetMode==='file'){pickFile(targetPanel,'Target response',targetFile,file=>{targetFile=file;invalidate();});t=targetFile;}
 if(targetMode==='search'){renderTargetLookup(targetPanel);t=websiteTarget;}
 if(!t?.points?.length)return;
 const run=el('section');run.className='fit-run';chart(run,[{name:'Measurement',points:confirmed.points},{name:'Target',points:t.points}]);
 const matches=[...(someMatch(confirmed)?[['auto','Selected measurement',confirmed]]:[]),
   ...library.eqs.filter(e=>someMatch(e.channelMatch)).map(e=>['eq:'+e.id,e.name,e.channelMatch]),
   ...library.measurements.filter(someMatch).map(m=>['m:'+m.id,m.name,m])];
 if(!matches.some(([id])=>id===selectedMatch))selectedMatch=matches[0]?.[0]||'';
 if(matches.length>1)choice(run,'Channel Match source',matches.map(([id,label])=>[id,label]),selectedMatch,id=>{selectedMatch=id;invalidate();});
 const match=matches.find(([id])=>id===selectedMatch)?.[2];
 const generate=btn('Generate EQ',()=>startFit(confirmed,t,match,run,generate),run);
 generate.className='fit-primary';
 el('small',match?'Generates Main PEQ and left/right Channel Match together.':'Generates Main PEQ. Add a left/right measurement to include Channel Match.',run);
}

function startFit(m,t,match,run,button){
 if(job || !m || !t)return;button.disabled=true;
 const fitDetails={...m.fitDetails};
 const progress=el('div',null,run);progress.className='fit-progress';progress.setAttribute('role','status');
 const spinner=el('span',null,progress);spinner.className='dot-ring';spinner.setAttribute('aria-hidden','true');
 const message=el('strong','Generating fits…',progress);
 const cancel=btn('Cancel',()=>{stopJob();progress.remove();button.disabled=false;},progress);
 progress.scrollIntoView({block:'nearest'});
 function failed(reason){stopJob();progress.remove();button.disabled=false;status(reason);}
 let result,allowed=[];
 try{
   // The WebView loads the module asynchronously. Keep its URL until stopJob.
   jobUrl=URL.createObjectURL(new Blob([workerSource],{type:'text/javascript'}));
   job=new Worker(jobUrl,{type:'module'});
   job.onerror=()=>{const updated=workerSource!==bundledWorker;if(updated){fitterUpdate('');Phone.resetFitter();}failed('The fitter could not finish. Try generating again.');};
   job.onmessage=e=>{
     const data=e.data;
     if(data.progress){message.textContent='Generating · '+data.progress;return;}
     if(data.error){failed(data.error);return;}
     if(data.result){
       result=data.result;message.textContent=standalone?'Ranking fits…':'Checking device capacity…';
       result.ranked.forEach((c,i)=>{const u=result.user[c.ui],channels=result.matches[c.mi].channels;
         if(standalone || Phone.fits(peq(u),channelPeq(channels)))allowed.push(i);
       });job.postMessage({allowed});return;
     }
     if(!data.choices || !result)return;
     stopJob();
     comparison={version:2,time:Date.now(),name:m.name,fitDetails,target:t.name,mainOnly:!match,result,allowed,choices:data.choices,raw:m.points,targetPoints:t.points};
     const saved=Phone.rememberComparison(JSON.stringify(comparison));restoreOpened=true;draw();
     if(saved===false)status('The comparison is open. This phone could not save it locally.');
     window.scrollTo(0,0);
   };
   job.postMessage({raw:m.points,target:t.points,match,plus:model||standalone,type:m.type,allBand:true});
 }catch(err){failed('Could not start the fitter. Try again.');}
}
function renderComparison(saved,parent){
 const {result,allowed}=saved,valid=allowed.map(i=>({i,c:result.ranked[i]}));
 const {options}=result;
 const ordered=(a,b)=>a.c.rms-b.c.rms||a.c.peak-b.c.peak||a.c.count-b.c.count;
 valid.sort(ordered);
 const [best,close]=saved.choices;
 const hasMatch=match=>!saved.mainOnly && match?.channels.some(ear=>ear.filters.length || ear.preamp);
 const mainText=(option,c)=>peq(c?result.user[c.ui]:option);
 const matchText=match=>hasMatch(match)?channelPeq(match.channels):'';
 const saveFiles=(name,files)=>Phone.saveFitFiles(JSON.stringify({name,files}));
 const pairedText=(main,match)=>main+(match?'\n\n'+match:'');
 function saveAll(){
   const files=options.map((opt,i)=>{
     const fit=valid.find(({c})=>result.user[c.ui].index===i)?.c;
     return {name:(i+1)+' - '+opt.label+' - Main PEQ',text:mainText(opt,fit)};
   });
   const matchFiles=new Set();
   for(const match of result.matches){
     const text=matchText(match);
     if(text && !matchFiles.has(text)){
       matchFiles.add(text);
       files.push({name:matchFiles.size+' - '+match.label+' - Channel Match',text});
     }
   }
   saveFiles(saved.name+' - EQ fits',files);
 }
 function compareFits(){
   const describe=c=>{
     const user=result.user[c.ui],opt=options[user.index];
     return {name:opt.label,main:peq(user),match:matchText(result.matches[c.mi])};
   };
   if(best<0)return null;
   const first=describe(result.ranked[best]);
   const alternates=[close,...valid.map(v=>v.i)].filter(i=>i>=0 && i!==best);
   const second=alternates.map(i=>describe(result.ranked[i])).find(fit=>fit.main!==first.main || model && fit.match!==first.match);
   return second?[first,second]:null;
 }
 if(confirmed)btn('Back to measurement and target',()=>{restoreOpened=false;draw();},parent).className='clear-file';
 const heading=el('header',null,parent);heading.className='comparison-head';
 el('h2','Your fit comparison',heading);
 el('p',saved.name+' → '+saved.target,heading);
 chart(heading,[{name:'Measurement',points:saved.raw},{name:'Target',points:saved.targetPoints}]);
 btn('Save all fits as TXT',saveAll,heading);
 const budget=el('section',null,parent);budget.className='fit-pane';
 el('h3','Best fit at each band limit',budget);
 el('small','Ranked by stereo RMS error · lower is closer',budget);
 const levels=[...new Set(options.map(o=>o.bands))].map(bands=>{
   const winner=valid.find(({c})=>options[result.user[c.ui].index].bands===bands);
   const fallback=options.map((o,index)=>({o,index})).filter(x=>x.o.bands===bands).sort((a,b)=>a.o.rms-b.o.rms)[0];
   return {bands,winner,option:winner?result.user[winner.c.ui].index:fallback.index,score:winner?winner.c.rms:fallback.o.rms};
 }).sort((a,b)=>Number(!!b.winner)-Number(!!a.winner)||a.score-b.score||a.bands-b.bands);
 const rankedList=el('div',null,budget);rankedList.className='budget-list';
 const focus=el('div',null,budget);focus.className='budget-detail';
 function showCandidate(parent,optionIndex,candidateIndex){
   const opt=options[optionIndex],c=candidateIndex==null?null:result.ranked[candidateIndex];
   const box=el('article',null,parent);box.className='candidate-detail';
   el('h3',opt.label,box);
   const kpis=el('div',null,box);kpis.className='fit-kpis';
   function metric(label,v){const col=el('div',null,kpis);el('small',label,col);el('b',v,col);}
   metric(c?'Stereo RMS':'Website RMS',fixed(c?c.rms:opt.rms,3)+' dB');
   if(c)metric('Peak boost',fixed(c.peak)+' dB');
   metric('Main preamp',fixed(c?result.user[c.ui].preamp:opt.preamp,1)+' dB');
   const activeFilters=c?result.user[c.ui].filters:opt.filters;
   const corrected=Array.from({length:241},(_,i)=>{const hz=20*1000**(i/240);
     return [hz,Curves.at(saved.raw,hz)+activeFilters.reduce((v,f)=>v+Curves.response(hz,f,48000),0)];});
   chart(box,[{name:'Predicted Main EQ',points:corrected},{name:'Target',points:saved.targetPoints}]);
   if(opt.labels.length)el('small',opt.labels.join(' · '),box);
   if(opt.fallback)el('small',opt.fallback,box);
   const main=mainText(opt,c),filename=saved.name+' - '+opt.label;
   const match=c?result.matches[c.mi]:null;
   const matchPreset=matchText(match);
   if(c){
     const u=result.user[c.ui],title=saved.name+' · '+opt.label;
     el('p',match.label+' · '+[u.filters.length,...match.channels.map(ear=>ear.filters.length)].join(' / ')+' filters (Main / Left / Right)',box);
     draftButton('Review in Filters',()=>use(title,u,saved.mainOnly?null:match.channels),box).className='fit-primary';
     if(matchPreset){
       draftButton('Review Channel Match only',()=>use(title,null,match.channels),box);
     }
     if(linked && library.writable)btn('Save fit to My Collection',()=>saveEntry({action:'eq',name:title.slice(0,100),filters:u.filters,preamp:u.preamp,
       ...(match.label==='No channel correction'?{}:{match:{channelMatchMode:'custom',channelMatchPreset:{channels:match.channels.map(c=>({filters:c.filters,preampDb:c.preamp}))}}})}),box);
   }else if(!standalone)el('p','This result exceeds the device’s filter or preamp limits.',box);
   const exports=el('fieldset',null,box);exports.className='fit-export';
   el('legend','Use or save this preset',exports);
   const variants=matchPreset?[
     {label:'Paired EQ',text:pairedText(main,matchPreset)},
     {label:'Main EQ',text:main},
     {label:'Channel Match',text:matchPreset},
   ]:[{label:'Main EQ',text:main}];
   const label=el('label',null,exports);
   el('span','Preset type',label);
   const type=el('select',null,label);
   for(const [index,preset] of variants.entries())el('option',preset.label,type).value=index;
   type.disabled=variants.length===1;
   const current=()=>variants[type.selectedIndex];
   const name=()=>filename.slice(0,72)+' - '+current().label;
   btn('Open in Phone EQ',()=>{
     const preset=phoneFitPreset(saved,opt,current().text);
     Phone.openPhoneEq(preset.name,preset.text);
   },exports).className='fit-export-open';
   const fileActions=el('div',null,exports);fileActions.className='fit-export-actions';
   btn('Save TXT',()=>saveFiles(filename,[{name:name(),text:current().text}]),fileActions);
   btn('Copy filters',()=>Phone.copyPeq(current().text),fileActions);
   const filters=el('details',null,box);el('summary','Calculated Main EQ filters',filters);const table=el('table',null,filters);table.className='filter-values';
   const headings=el('tr',null,el('thead',null,table));
   for(const title of ['Band','Type','Hz','dB','Q'])el('th',title,headings);
   const rows=el('tbody',null,table),names={PK:'Peak',PEQ:'Peak',LS:'Low shelf',LSC:'Low shelf',HS:'High shelf',HSC:'High shelf',LP:'Low pass',HP:'High pass',OFF:'Off'};
   for(const [i,f] of activeFilters.entries()){
     const tr=el('tr',null,rows);for(const v of [i+1,names[f.type||'PK']||f.type,f.hz,f.gain,f.q])el('td',v,tr);
   }
 }
 const buttons=[];
 const maxError=Math.max(.001,...levels.filter(level=>level.winner).map(level=>level.score));
 for(const [rank,item] of levels.entries()){
   const b=btn('',()=>{buttons.forEach(v=>v.setAttribute('aria-pressed',String(v===b)));focus.replaceChildren();showCandidate(focus,item.option,item.winner?.i);},rankedList);
   buttons.push(b);b.className='budget-row';b.setAttribute('aria-pressed','false');
   el('small',item.winner?String(rank+1):'—',b);
   const name=el('span',null,b);el('strong',item.bands+' bands',name);el('small',options[item.option].label.split(' · ').slice(0,-1).join(' · '),name);
   if(item.winner){
     const track=el('span',null,name);track.className='rms-track';track.setAttribute('aria-hidden','true');
     const fill=el('i',null,track);fill.style.width=(100*item.score/maxError)+'%';
   }
   const score=el('span',null,b);el('strong',fixed(item.score,3)+' dB',score);
   el('small',item.winner?'Stereo RMS':standalone?'Main EQ RMS':'Device limit · website RMS',score);
 }
 if(!standalone && levels.some(item=>!item.winner))el('small','Results marked Device limit show the calculated website error. They can be inspected here; loading requires a compatible band budget.',budget);
 const rec=pane(parent,'Recommendations');
 parent.insertBefore(rec,budget);
 const pick=(index,label)=>{if(index<0)return;const c=result.ranked[index],d=el('details',null,rec);d.className='fit-pick';
  const h=el('summary',null,d);el('strong',label,h);el('small',options[result.user[c.ui].index].label+' · '+fixed(c.rms,3)+' dB RMS',h);
  d.ontoggle=()=>{if(d.open && d.children.length===1)showCandidate(d,result.user[c.ui].index,index);};
 };
 if(best>=0){pick(best,best===close?'Best fit and lower boost':'Best fit');if(close!==best)pick(close,'Lower boost, close fit');}
 else el('p',standalone?'Review and copy the calculated Main EQ options below.':'No complete fit can be loaded on this model.',rec);
 const pair=compareFits();
 const compare=btn('Compare best fits in Blind A/B',()=>Phone.compareGeneratedFits(JSON.stringify(pair)),rec);
 compare.disabled=standalone || !pair;
 if(standalone)el('small','Link a Qudelix to audition the fits in Blind A/B.',rec);
 else if(!pair)el('small','Blind A/B needs two distinct fits.',rec);
 else if(!model && !saved.mainOnly)el('small','Blind A/B on the original 5K compares Main PEQ with the current Channel Match fixed.',rec);
 const all=el('details',null,parent);all.className='all-options';el('summary','All generated options ('+options.length+')',all);
 const picker=choice(all,'Fitter and band limit',options.map((o,i)=>[String(i),o.label+(o.labels.includes('Lower boost')?' · Lower boost':'')]),'0',()=>inspect());
 const variants=el('div',null,all);
 function inspect(){
   variants.replaceChildren();const i=Number(picker.value),compatible=valid.filter(({c})=>result.user[c.ui].index===i);
   const detail=el('div',null,variants);
   if(compatible.length>1){
     const select=choice(variants,'Channel Match variant',compatible.map(({i:idx,c})=>[String(idx),result.matches[c.mi].label+' · '+fixed(c.rms,3)+' dB RMS']),String(compatible[0].i),v=>{
       detail.replaceChildren();showCandidate(detail,i,Number(v));
     });variants.insertBefore(select.parentNode,detail);
   }
   showCandidate(detail,i,compatible[0]?.i);
 }
 all.ontoggle=()=>{if(all.open && !variants.children.length)inspect();};
 if(best>=0){const note=pane(parent,'Choosing between the two'),b=result.ranked[best],h=result.ranked[close];
  el('p','Best fit follows the target most closely across both ears. RMS error measures the remaining difference. A narrow peak or dip can take more boost to correct.',note);
  el('p','Lower boost, close fit keeps the error within '+fixed(result.windowDb)+' dB RMS of the best and minimizes the highest combined Main EQ and Channel Match boost on either ear. Lower boost usually needs less preamp reduction.',note);
  el('p',best===close?'One preset meets both goals for these curves.':'The lower-boost choice saves '+fixed(b.peak-h.peak)+' dB of peak boost for '+fixed(h.rms-b.rms,3)+' dB more error. Try both on the same passage at matched volume and choose the one you prefer.',note);
 }
}
