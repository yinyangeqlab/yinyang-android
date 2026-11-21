// Exercise actual route SQL and cryptography with isolated browser identities and an in-memory D1 adapter.
import {DatabaseSync} from 'node:sqlite';
import {build} from 'esbuild';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
const site=process.env.YINYANG_COMPANION_SITE || fileURLToPath(new URL('../../work/companion-readonly-sync/site/',import.meta.url));
const db=new DatabaseSync(':memory:');
db.exec(`CREATE TABLE private_tester_devices(session_hash,invite_id,expires_at);
CREATE TABLE private_tester_invites(id,revoked_at,session_expires_at);
CREATE TABLE privacy_sessions(subject_id,token_hash,expires_at);
CREATE TABLE account_device_status(session_hash,id PRIMARY KEY,sync_enabled);
CREATE TABLE owner_public_profiles(subject_id,slug);
CREATE TABLE owner_profile_devices(id,session_hash,revoked_at);
CREATE TABLE account_sync_permissions(subject_id,enabled,notice_version,consent_source,agreed_at,updated_at);
CREATE TABLE account_browser_sync(subject_id,epoch,revision,initialized,payload,updated_at);
CREATE TABLE privacy_accounts(subject_id TEXT PRIMARY KEY); INSERT INTO privacy_accounts VALUES('A'),('B');
CREATE TABLE submission_owners(subject_id,record_kind,record_id);`);
for(const migration of ['0039_segmented_account_sync','0046_companion_library','0047_companion_preset_history'])db.exec(readFileSync(path.join(site,'drizzle/'+migration+'.sql'),'utf8'));
globalThis.d1={
  async batch(statements){db.exec('BEGIN');try{const replies=[];for(const stmt of statements)replies.push(await stmt.run());db.exec('COMMIT');return replies;}catch(err){db.exec('ROLLBACK');throw err;}},
  prepare(sql){
    const stmt=db.prepare(sql);
    return {bind(...args){return {
      async first(){return stmt.get(...args);},
      async all(){return {results:stmt.all(...args)};},
      async run(){return {meta:{changes:stmt.run(...args).changes}};}
    };}};
  }
};
const bundled=await build({stdin:{contents:`export {digest} from './lib/device-sync.mjs';export * as backups from './app/api/companion/backups/route';export * as grants from './app/api/companion/grants/route';export * as lib from './app/api/companion/library/route';export {describeValues,valueParts} from './lib/account-sync-transfer.mjs';`,resolveDir:site},
 bundle:true,platform:'node',format:'esm',write:false,tsconfig:path.join(site,'tsconfig.json'),alias:{'@':site},
 plugins:[{name:'fixture',setup(b){
 b.onResolve({filter:/^(cloudflare:workers|@\/lib\/account-sync-identity)$/},a=>({path:a.path,namespace:'fixture'}));
 b.onLoad({filter:/.*/,namespace:'fixture'},a=>({contents:a.path==='cloudflare:workers'?'export const env={DB:globalThis.d1}':
 'export async function accountSyncIdentity(){return {subject_id:globalThis.testSubject||"A",deviceId:"device-a",syncAccess:"a",syncSession:"privacy-a",deviceSyncEnabled:true};}'}));
 }}]});
const {digest,backups,grants,lib,describeValues,valueParts}=await import('data:text/javascript;base64,'+Buffer.from(bundled.outputFiles[0].contents).toString('base64'));
db.exec(`INSERT INTO private_tester_devices VALUES('a','invite',9999999999);INSERT INTO private_tester_invites VALUES('invite',NULL,9999999999);
 INSERT INTO privacy_sessions VALUES('A','privacy-a',9999999999999);INSERT INTO account_device_status VALUES('a','device-a',1);
 INSERT INTO account_sync_permissions VALUES('A',1,'account-sync-2026-10-19-v1','fixture',1,1);`);
const libKey='target-lab-library-eqs-v1',measKey='target-lab-measurements',eq={id:'1',name:'Owned EQ',preamp:-2,filters:[{hz:800,type:'PK',gain:2,q:1}]};
const m={id:'m',name:'A measurement',type:'oe',points:[[20,0],[20000,0]],notes:'never expose',channelMatchPreset:{channels:[{filters:[],preampDb:0},{filters:[],preampDb:-1}]}};
const values={[libKey]:JSON.stringify([eq]),[measKey]:JSON.stringify([m]),'target-lab-trials':'[]'};
db.prepare('INSERT INTO account_browser_sync VALUES(?,?,?,?,?,?)').run('A','epoch',1,1,JSON.stringify(values),1);
db.prepare('INSERT INTO account_browser_sync VALUES(?,?,?,?,?,?)').run('B','foreign',1,1,JSON.stringify({[libKey]:JSON.stringify([{...eq,name:'foreign account'}])}),1);
const secret='12'.repeat(32),challenge=createHash('sha256').update(secret).digest('hex'),base='https://example.test',issued=Date.now();
const req=(token=secret)=>new Request(base+'/api/companion/library',{headers:{Authorization:'Bearer '+token}});
const approve=(origin=base)=>grants.POST(new Request(base+'/api/companion/grants',{method:'POST',headers:{Origin:origin,'Content-Type':'application/json'},body:JSON.stringify({action:'approve',issued,challenge})}));
assert.equal((await lib.GET(req())).status,403);assert.equal((await approve('https://untrusted.test')).status,403);
assert.equal((await approve()).status,200);assert.equal((await approve()).status,409);assert.equal((await lib.GET(req(challenge))).status,403);
for(const decision of ['wrong-code','cancel']){
 const testSecret=(decision==='cancel'?'45':'67').repeat(32);
 const testKey=createHash('sha256').update(testSecret).digest('hex');
 const decide=action=>grants.POST(new Request(base+'/api/companion/grants',{method:'POST',headers:{Origin:base,'Content-Type':'application/json'},body:JSON.stringify({challenge:testKey,issued,action})}));
 assert.equal((await decide(decision)).status,200);
 const refused=await lib.GET(req(testSecret));assert.equal(refused.status,410);assert.equal((await refused.json()).decision,'cancel');
 assert.equal((await decide('approve')).status,409);
 assert.equal((await lib.GET(req())).status,200);
 await lib.DELETE(req(testSecret));assert.equal((await decide('approve')).status,409);
}
const first=await (await lib.GET(req())).json();assert.equal(first.eqs[0].name,'Owned EQ');assert.equal(first.measurements[0].channelMatchPreset.channels[1].preampDb,-1);assert(!JSON.stringify(first).includes('never expose'));
const sections=await describeValues(values);
for(const p of valueParts(values,sections)){

 db.exec('PRAGMA foreign_keys=OFF');if(!p.part)db.prepare('INSERT INTO account_sync_entries(version_id,storage_key,entry_index,part_count,content_hash) VALUES(?,?,?,?,?)').run('v',p.key,p.entry,p.total,await digest(JSON.parse(values[p.key])[p.entry]));
 db.prepare('INSERT INTO account_sync_parts VALUES(?,?,?,?,?)').run('v',p.key,p.entry,p.part,p.text);
}
for(const s of Object.values(sections))s.version='v';
db.prepare('INSERT INTO account_sync_versions VALUES(?,?,?,?,?,?,?,?)').run('v','A','device-a','epoch',0,JSON.stringify(sections),1,1);db.exec('PRAGMA foreign_keys=ON');
db.prepare('UPDATE account_browser_sync SET payload=? WHERE subject_id=?').run(JSON.stringify({format:3,version:'v',digest:'h'}),'A');
const segmented=await lib.GET(req());assert.equal(segmented.status,200);assert.deepEqual(await segmented.json(),first);
const paged={...values,[libKey]:JSON.stringify([eq,{...eq,id:'2'},{...eq,id:'3'}])};
db.prepare('UPDATE account_browser_sync SET payload=?,revision=2 WHERE subject_id=?').run(JSON.stringify(paged),'A');
const firstPage=await (await lib.GET(req())).json();assert.equal(firstPage.eqs.length,2);assert.equal(firstPage.next,2);
const pageReq=q=>new Request(base+'/api/companion/library?'+q,{headers:{Authorization:'Bearer '+secret}});
assert.equal((await lib.GET(pageReq('offset=2&epoch=epoch&revision=1'))).status,409);
const lastPage=await (await lib.GET(pageReq('offset=2&epoch=epoch&revision=2'))).json();assert.equal(lastPage.eqs[0].id,'3');assert.equal(lastPage.next,null);
db.exec("UPDATE account_device_status SET sync_enabled=0");assert.equal((await lib.GET(req())).status,403);db.exec("UPDATE account_device_status SET sync_enabled=1");
db.exec("UPDATE account_sync_permissions SET enabled=0");assert.equal((await lib.GET(req())).status,403);db.exec("UPDATE account_sync_permissions SET enabled=1");
db.exec("UPDATE private_tester_invites SET revoked_at=1");assert.equal((await lib.GET(req())).status,403);db.exec("UPDATE private_tester_invites SET revoked_at=NULL");
assert.equal((await lib.DELETE(req())).status,200);assert.equal((await lib.GET(req())).status,403);
console.log('PASS browser origin and approval, scope, isolation, both snapshot formats, number of grants, consent, pause, revocation and disconnect.');

const send=(body,token=secret)=>lib.POST(new Request(base+'/api/companion/library',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify(body)}));
assert.equal((await approve()).status,200);assert.equal((await send({action:'backup'})).status,403);
await lib.DELETE(req());
assert.equal((await grants.POST(new Request(base+'/api/companion/grants',{method:'POST',headers:{Origin:base,'Content-Type':'application/json'},body:JSON.stringify({action:'approve',issued,challenge,access:'edit-library-v1'})}))).status,200);
db.exec('UPDATE privacy_sessions SET expires_at=0;UPDATE private_tester_devices SET expires_at=0');assert.equal((await lib.GET(req())).status,200);
const main='Preamp: -1 dB\nFilter 1: ON PK Fc 1000 Hz Gain 1 dB Q 1',match='Channel: L\n'+main+'\nChannel: R\n'+main;
const backup={action:'backup',model:'5K Plus',kind:'pair',name:'Fixture',id:crypto.randomUUID(),main,match};
const ok=async(p)=>{const r=await p;assert.equal(r.status,200,await r.text());};
await ok(send(backup));await ok(send(backup));await ok(send({...backup,id:crypto.randomUUID(),main:main.replace('-1 dB','-2 dB')}));
const versions=await (await lib.GET(pageReq('history=0'))).json();assert.equal(versions.history.length,2);assert.equal(versions.history[0].versionRank,1);assert.equal(versions.history[1].versionRank,2);assert.equal(versions.history[0].match,match);
const remove=(bearer=false)=>backups.DELETE(new Request(base+'/api/companion/backups',{method:'DELETE',headers:{Origin:base,'Content-Type':'application/json',...(bearer?{Authorization:'Bearer '+secret}:{})},body:JSON.stringify({id:backup.id})}));
assert.equal((await remove(true)).status,403);globalThis.testSubject='B';assert.equal((await remove()).status,404);globalThis.testSubject='A';await ok(remove());
const write={action:'eq',name:'From phone',filters:eq.filters,preamp:-2,epoch:'epoch',revision:2};
await ok(send(write));assert.equal((await send(write)).status,409);
db.prepare('UPDATE account_browser_sync SET payload=?,revision=1 WHERE subject_id=?').run(JSON.stringify({format:3,version:'v',digest:'h'}),'A');
await ok(send({...write,revision:1}));
const points=Array.from({length:12},(_,i)=>[20*1.5**i,i/2]);
await ok(send({action:'measurement',name:'Stereo',device:'Test',rig:'Fixture',type:'oe',points,match:{leftPoints:points,rightPoints:points},epoch:'epoch',revision:2}));
const saved=await (await lib.GET(req())).json();assert.equal(saved.eqs[1].name,'From phone');assert.equal(saved.measurements[1].name,'Stereo');assert.equal(saved.measurements[1].leftPoints.length,12);
db.exec('UPDATE account_device_status SET sync_enabled=0');assert.equal((await send(backup)).status,403);
console.log('PASS durable consent, legacy scope, paired versions, browser-only deletion, account isolation, legacy and segmented appends, stale revision and permissions.');
