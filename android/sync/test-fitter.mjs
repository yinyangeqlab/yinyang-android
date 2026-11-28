// Compare the worker extracted from each APK against the independently bundled, recorded live website worker.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {build} from 'esbuild';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {Worker} from 'node:worker_threads';
import {fileURLToPath,pathToFileURL} from 'node:url';
import path from 'node:path';
const root=fileURLToPath(new URL('../../',import.meta.url));
const release=path.join(root,JSON.parse(readFileSync(path.join(root,'LIVE_RELEASE.json'))).applicationSnapshot);
const assets=path.join(release,'dist/client/vendor/peqdb-autoeq');
const site=process.env.YINYANG_FITTER_SITE||path.join(release,'site');
const getWorker=apk=>JSON.parse(execFileSync('unzip',['-p',apk,'assets/library.html'],{encoding:'utf8',maxBuffer:16*1024*1024}).match(/const bundledWorker=(.+?);\n/)[1]);
const apks=process.argv.length>2?process.argv.slice(2):[path.join(root,'android/build/YinYangEQ Q5K Companion App.apk')];
const app=getWorker(apks[0]);
for(const apk of apks.slice(1))assert.equal(app,getWorker(apk));
const {compileCompanionFitter}=await import(new URL('../scripts/build/companion-fitter.mjs',pathToFileURL(site+'/')).href);
const packaged=compileCompanionFitter(assets);
assert.equal(packaged.manifest.allBand,true);
assert.equal(packaged.manifest.sha256,createHash('sha256').update(app).digest('hex'));
assert.equal(new TextDecoder().decode(packaged.worker),app);
console.log('PASS website update payload matches the APK’s offline program');
const opts={bundle:true,write:false,format:'esm',platform:'browser',tsconfig:path.join(site,'tsconfig.json'),
  alias:{'@':site,engine:path.join(assets,'autoeq-wasm.mjs'),bytes:path.join(assets,'autoeq-wasm.wasm')},external:['fs','path','url','module','node:*'],loader:{'.wasm':'binary'}};
const web=(await build({...opts,entryPoints:[path.join(site,'lib/autoeq.worker.ts')],plugins:[{name:'offline-reference',setup(b){
 b.onResolve({filter:/^\/vendor\/peqdb-autoeq\/autoeq-wasm.mjs$/},()=>({path:'wasm',namespace:'reference'}));
 b.onLoad({filter:/.*/,namespace:'reference'},()=>({contents:"import factory from 'engine';import wasmBinary from 'bytes';export default opts=>factory({...opts,wasmBinary,locateFile:name=>'https://library.invalid/'+name});",resolveDir:site}));
}}]})).outputFiles[0].text;
const {exportFitRms}=await import('data:text/javascript;base64,'+Buffer.from((await build({...opts,stdin:{contents:"export {exportFitRms} from './lib/preference-finalize'",resolveDir:site}})).outputFiles[0].text).toString('base64'));
function worker(code){
 const wrapper=`import {parentPort} from 'node:worker_threads';globalThis.self=globalThis;globalThis.process=undefined;globalThis.location={href:'https://library.invalid/'};globalThis.WorkerGlobalScope=class {};globalThis.fetch=()=>{throw Error('Network disabled');};globalThis.postMessage=data=>parentPort.postMessage(data);await import(${JSON.stringify('data:text/javascript;base64,'+Buffer.from(code).toString('base64'))});parentPort.on('message',data=>self.onmessage({data}));`;
 const w=new Worker(new URL('data:text/javascript;base64,'+Buffer.from(wrapper).toString('base64')));
 return {run(data){return new Promise((resolve,reject)=>{function done(reply){if(reply.progress)return;w.off('message',done);w.off('error',fail);if(reply.error)reject(Error(reply.error));else resolve(reply);}
 function fail(e){w.off('message',done);reject(e);}w.on('message',done);w.once('error',fail);w.postMessage(data);});},close:()=>w.terminate()};
}
const raw=Array.from({length:96},(_,i)=>[20*1000**(i/95),5*Math.sin(i/12)+1.3*Math.cos(i/6)]);
const target=[[20,4],[80,2],[1000,0],[4500,3],[20000,-3]];
const a=worker(app),w=worker(web);
try {
 const {result:legacy}=await a.run({raw,target,type:'ie',plus:false,match:null});
 assert.equal(new Set(legacy.options.map(option=>option.method+':'+option.bands)).size,16);
 assert.ok(legacy.options.every(option=>option.method!=='joint'));
 console.log('PASS existing clients still receive the 16 original fits');
 for(const [type,plus] of [['oe',true],['ie',false]]){
  const match=plus?{leftPoints:raw,rightPoints:raw.map(([hz,db],i)=>[hz,db+Math.sin(i/8)]),leftLevelDb:1,rightLevelDb:0}:null;
  const start=performance.now();
  const {result}=await a.run({raw,target,type,plus,match,allBand:true});
  const elapsed=Math.round(performance.now()-start);
  const baseFits=result.options.slice(0,20);
  assert.equal(new Set(baseFits.map(option=>option.method+':'+option.bands)).size,20);
  assert.deepEqual(baseFits.filter(option=>option.method==='joint').map(option=>option.bands).sort((a,b)=>a-b),[10,15,20,30]);
  assert.ok(result.ranked.length);
  if(match)assert.ok(result.matches.some(candidate=>candidate.label.startsWith('All-Band Fit')));
  for(const option of baseFits){
   const {results:[ref]}=await w.run({id:1,request:{raw,targets:[target],kind:'export',method:option.method,bands:option.bands,headphoneType:option.method==='peqdb-smoothed'?type:undefined,sampleRate:48000}});
   assert.deepEqual(option.filters,ref.filters);assert.equal(option.preamp,ref.preamp);assert.equal(option.rms,exportFitRms(raw,target,ref.filters));
   assert.equal(ref.engine,option.method==='joint'?'joint':option.method.startsWith('peqdb')?'peqdb':'legacy');
  }
  for(const fit of result.ranked){
   const main=result.user[fit.ui].filters.length;
   const channel=Math.max(...result.matches[fit.mi].channels.map(c=>c.filters.length));
   assert.ok(plus?main<=30 && Math.max(0,main-10)+channel<=20:main<=10 && channel<=10);
  }
  const [best,close]=result.choices.map(i=>result.ranked[i]);assert(best && close);
  assert.equal(best.rms,Math.min(...result.ranked.map(f=>f.rms)));assert.equal(close.peak,Math.min(...result.ranked.filter(f=>f.rms<=best.rms+result.windowDb).map(f=>f.peak)));
  console.log('PASS',type,plus?'5K Plus':'5K','20 identical website results, device limits, local fitting ('+elapsed+' ms)');
 }
}finally{await Promise.all([a.close(),w.close()]);}
