import json,subprocess,tempfile,zipfile,pathlib,sys,os
app=pathlib.Path(sys.argv[1]).resolve()
java=app/'runtime/bin/java.exe'
with tempfile.TemporaryDirectory(prefix='aw-native-smoke-') as folder:
 home=pathlib.Path(folder); root=home/'.aw'; game=root/'instances/builds/Test'; (game/'mods').mkdir(parents=True)
 identity='00000000-0000-4000-8000-000000000001'
 (root/'local-builds.json').write_text(json.dumps([dict(id=identity,name='Test',versionId='1.21',loader='FABRIC',directoryName='Test')]),encoding='utf-8')
 with zipfile.ZipFile(game/'mods/test.jar','w') as archive:
  archive.writestr('fabric.mod.json',json.dumps(dict(schemaVersion=1,id='aw_smoke',version='1.0',name='AW smoke',depends={'minecraft':'*'})))
 def run(request):
  process=subprocess.Popen([str(java),'-Xms16m','-Xmx512m','-Dfile.encoding=UTF-8',f'-Duser.home={home}','-Daw.native.backend=true','-cp',str(app/'app/*'),'ru.aw.launcher.MainKt','--native-task'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
  process.stdin.write(json.dumps(request)+'\n');process.stdin.flush()
  result=None
  for line in process.stdout:
   if line.startswith('AW_RPC '):
    event=json.loads(line[7:])
    if event['type'] in ('done','failed','cancelled'):result=event;break
  process.stdin.close();process.wait(timeout=30)
  assert result is not None,process.stderr.read()
  return result
 target='build:'+identity
 if '--network' in sys.argv:
  project=run(dict(op='project',project='P7dR8mSH',kind='mod',target=target));assert project['type']=='done',project;assert project['data']['versions']
  installed=run(dict(op='install',project='P7dR8mSH',kind='mod',title='Fabric API smoke',target=target));assert installed['type']=='done',installed
  assert len(list((game/'mods').glob('*.jar')))>=2
  print('Real catalog project lookup and verified mod installation passed in temporary profile')

 assert run(dict(op='ping'))['data']['protocol']==1
 state=run(dict(op='launcherState'));assert state['type']=='done',state
 assert state['data']['builds'][0]['key']==target
 assert 'settings' in state['data'] and 'accounts' in state['data']
 content=run(dict(op='content',target=target));assert content['type']=='done',content;assert any(item['file']=='mods/test.jar' for item in content['data'])
 disabled=run(dict(op='toggle',target=target,kind='mod',file='mods/test.jar',enabled=False));assert disabled['type']=='done',disabled;assert (game/'mods/test.jar.disabled').is_file()
 enabled=run(dict(op='toggle',target=target,kind='mod',file='mods/test.jar.disabled',enabled=True));assert enabled['type']=='done',enabled;assert (game/'mods/test.jar').is_file()
 history=run(dict(op='history',target=target));assert history['type']=='done',history;assert len(history['data'])>=2
 rejected=run(dict(op='toggle',target=target,kind='mod',file='../outside.jar',enabled=False));assert rejected['type']=='failed',rejected
 assert (game/'mods/test.jar').is_file()
 saved=run(dict(op='saveOptions',target=target,options=dict(memoryMb=2048,windowWidth=1280,windowHeight=720,fullscreen=True,favorite=True)))
 assert saved['type']=='done',saved
 options=json.loads((game/'aw-instance.json').read_text(encoding='utf-8'));assert options['memoryMb']==2048 and options['favorite'] is True
 restored=run(dict(op='restore',target=target,snapshot=history['data'][-1]['id']));assert restored['type']=='done',restored
 renamed=run(dict(op='rename',target=target,title='Renamed test'));assert renamed['type']=='done',renamed
 editor=run(dict(op='buildEditor',target=target));assert editor['type']=='done',editor
 assert editor['data']['canRename'] and editor['data']['memoryLimit']>=512 and editor['data']['memoryPresets']
 bad_args=run(dict(op='saveOptions',target=target,options=dict(jvmArgs='-Dtest="unclosed')));assert bad_args['type']=='failed',bad_args
 bad_window=run(dict(op='saveOptions',target=target,options=dict(windowWidth=800,windowHeight=None)));assert bad_window['type']=='failed',bad_window
 loader=run(dict(op='saveOptions',target=target,options=dict(loaderVersion='0.16.14',javaPath=str(java),windowWidth=800,windowHeight=200)));assert loader['type']=='done',loader
 bad_loader=run(dict(op='saveOptions',target=target,options=dict(loaderVersion='../bad')));assert bad_loader['type']=='failed',bad_loader
 created_group=run(dict(op='buildGroups',title='Smoke group',options=dict(action='create')));assert created_group['type']=='done',created_group
 group_id=created_group['data'][0]['id']
 assert run(dict(op='saveOptions',target=target,options=dict(groupId=group_id)))['type']=='done'
 renamed_group=run(dict(op='buildGroups',project=group_id,title='Renamed group',options=dict(action='rename')));assert renamed_group['type']=='done' and renamed_group['data'][0]['name']=='Renamed group',renamed_group
 removed_group=run(dict(op='buildGroups',project=group_id,options=dict(action='remove')));assert removed_group['type']=='done' and removed_group['data']==[],removed_group
 assert json.loads((game/'aw-instance.json').read_text(encoding='utf-8')).get('groupId') is None
 icon=run(dict(op='buildSaveIcon',target=target,options=dict(symbol='fox',background='amber')));assert icon['type']=='done' and icon['data']['iconPreset']=='fox',icon
 icon_reset=run(dict(op='buildSaveIcon',target=target,options=dict(reset=True,symbol='grass',background='emerald')));assert icon_reset['type']=='done' and icon_reset['data'].get('iconPreset') is None,icon_reset
 config=game/'config';config.mkdir(exist_ok=True);(config/'smoke.txt').write_text('config preserved')
 world=game/'saves/SmokeWorld';world.mkdir(parents=True);(world/'level.dat').write_bytes(b'world-preserved')
 choices=run(dict(op='buildExportChoices',target=target));assert choices['type']=='done' and 'saves' in choices['data'] and 'aw-instance.json' not in choices['data'],choices
 exported=home/'selected.mrpack'
 export_result=run(dict(op='buildExport',target=target,title='Smoke export',file=str(exported),options=dict(selected=['config'])));assert export_result['type']=='done',export_result
 with zipfile.ZipFile(exported) as archive:
  assert archive.read('overrides/config/smoke.txt')==b'config preserved'
  assert not any('saves/' in name for name in archive.namelist())
 overwrite=run(dict(op='buildExport',target=target,title='Smoke export',file=str(exported),options=dict(selected=['config'])));assert overwrite['type']=='failed',overwrite
 invalid_export=run(dict(op='buildExport',target=target,title='Invalid',file=str(home/'invalid.mrpack'),options=dict(selected=['../outside'])));assert invalid_export['type']=='failed',invalid_export
 legacy=run(dict(op='instanceWindow',target=target,kind='settings'));assert legacy['type']=='failed',legacy
 denied_delete=run(dict(op='buildDelete',target=target));assert denied_delete['type']=='failed' and (world/'level.dat').read_bytes()==b'world-preserved',denied_delete
 plan_dir=root/'cache/native-migrations';plan_dir.mkdir(parents=True,exist_ok=True);plan_id='00000000-0000-4000-8000-000000000088'
 (plan_dir/(plan_id+'.json')).write_text(json.dumps(dict(key=target,source='obsolete',target='1.22',loader='FABRIC',mods=[],fingerprint={})),encoding='utf-8')
 plan=run(dict(op='buildMigrationGet',target=target,snapshot=plan_id));assert plan['type']=='done' and plan['data']['target']=='1.22',plan
 denied_migration=run(dict(op='buildMigrationApply',target=target,snapshot=plan_id,options=dict(keep=[])));assert denied_migration['type']=='failed' and (world/'level.dat').read_bytes()==b'world-preserved',denied_migration
 print('Embedded build operations: settings validation, loader, groups, icons, selected export, stale migration and deletion guards passed')
 account=run(dict(op='accountOffline',title='SmokePlayer'));assert account['type']=='done',account
 state=run(dict(op='launcherState'));assert state['type']=='done',state
 assert state['data']['accounts'][0]['name']=='SmokePlayer'
 assert {'id','name','type','selected'} <= set(state['data']['accounts'][0]) <= {'id','name','type','selected','face'}
 accounts=json.loads((root/'accounts.json').read_text(encoding='utf-8'));assert accounts['accounts'][0]['name']=='SmokePlayer'
 first=accounts['accounts'][0]['uuid']
 second=run(dict(op='accountOffline',title='SmokeSecond'));assert second['type']=='done',second
 stored=json.loads((root/'accounts.json').read_text(encoding='utf-8'));second_id=stored['selectedUuid']
 protected=dict(uuid='00000000-0000-4000-8000-000000000099',name='ProtectedSmoke',type='MICROSOFT',accessToken='dpapi:test-access-bytes',refreshToken='dpapi:test-refresh-bytes')
 stored['accounts'].append(protected);(root/'accounts.json').write_text(json.dumps(stored),encoding='utf-8')
 selected=run(dict(op='accountSelect',file=first));assert selected['type']=='done',selected
 after=json.loads((root/'accounts.json').read_text(encoding='utf-8'));assert after['selectedUuid']==first
 saved_protected=next(a for a in after['accounts'] if a['uuid']==protected['uuid'])
 assert saved_protected['accessToken']==protected['accessToken'] and saved_protected['refreshToken']==protected['refreshToken']
 invalid=run(dict(op='accountSelect',file='missing'));assert invalid['type']=='failed',invalid
 assert json.loads((root/'accounts.json').read_text(encoding='utf-8'))['selectedUuid']==first
 after['accounts']=[a for a in after['accounts'] if a['uuid']!=protected['uuid']];(root/'accounts.json').write_text(json.dumps(after),encoding='utf-8')
 removed_second=run(dict(op='accountRemove',file=second_id,options=dict(returnAccounts=True)));assert removed_second['type']=='done' and len(removed_second['data'])==1,removed_second
 denied_skin=run(dict(op='accountSkinProfile',file=first));assert denied_skin['type']=='failed',denied_skin
 removed=run(dict(op='accountRemove',file=accounts['accounts'][0]['uuid']));assert removed['type']=='done',removed
 assert not json.loads((root/'accounts.json').read_text(encoding='utf-8')).get('accounts',[])
 # Exercise skin data/library operations in the isolated home, without changing a live Minecraft profile.
 import struct,zlib,base64
 def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
 png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',64,64,8,6,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\x80\x40\x20\xff'*64)*64))+chunk(b'IEND',b'')
 skin_png='data:image/png;base64,'+base64.b64encode(png).decode()
 skin_owner='00000000-0000-4000-8000-000000000077'
 (root/'accounts.json').write_text(json.dumps(dict(accounts=[dict(uuid=skin_owner,name='SkinSmoke',type='MICROSOFT',accessToken='isolated-smoke-token',refreshToken='isolated-smoke-refresh')],selectedUuid=skin_owner)),encoding='utf-8')
 skin_state=run(dict(op='accountSkinState',file=skin_owner));assert skin_state['type']=='done',skin_state
 assert len(skin_state['data']['presets'])>9 and skin_state['data']['saved']==[]
 assert 'isolated-smoke-token' not in json.dumps(skin_state) and 'refreshToken' not in json.dumps(skin_state)
 invalid_image=run(dict(op='accountSkinRead',options=dict(png='data:image/png;base64,bm90LXBuZw==')));assert invalid_image['type']=='failed',invalid_image
 added=run(dict(op='accountSkinSave',file=skin_owner,title='Saved skin',kind='CLASSIC',options=dict(png=skin_png)));assert added['type']=='done',added
 skin_id=added['data']['selected'];assert len(added['data']['saved'])==1
 edited=run(dict(op='accountSkinSave',file=skin_owner,project=skin_id,title='Renamed skin',kind='SLIM',options=dict(png=skin_png)));assert edited['type']=='done' and edited['data']['saved'][0]['model']=='SLIM',edited
 assert edited['data']['selected']==skin_id
 second_skin=run(dict(op='accountSkinSave',file=skin_owner,title='Second skin',kind='CLASSIC',options=dict(png=skin_png)));assert second_skin['type']=='done',second_skin
 moved=run(dict(op='accountSkinMove',file=skin_owner,project=skin_id,options=dict(position=1)));assert moved['type']=='done' and moved['data'][-1]['id']==skin_id,moved
 textures=run(dict(op='accountSkinTextures',file=skin_owner,options=dict(ids=[skin_id])));assert textures['type']=='done' and textures['data'][0]['png'].startswith('data:image/png;base64,'),textures
 denied_cape=run(dict(op='accountSkinSave',file=skin_owner,title='Invalid cape',kind='CLASSIC',options=dict(png=skin_png,capeId='not-owned')));assert denied_cape['type']=='failed',denied_cape
 traversal=run(dict(op='accountSkinSave',file=skin_owner,project='../../escape',title='Invalid path',kind='CLASSIC',options=dict(png=skin_png)));assert traversal['type']=='failed',traversal
 old_window=run(dict(op='accountSkinWindow',file=skin_owner));assert old_window['type']=='failed',old_window
 for item in second_skin['data']['saved']:
  removed_skin=run(dict(op='accountSkinRemove',file=skin_owner,project=item['id']));assert removed_skin['type']=='done',removed_skin
 assert json.loads((root/'skins/library.json').read_text())==[]
 run(dict(op='accountRemove',file=skin_owner))
 print('Embedded skin protocol: catalog, import validation, library round-trip, reorder, texture, ownership and path checks passed')
 blocker=subprocess.Popen([sys.executable,'-c','import time;time.sleep(15)','--gameDir',str(game)])
 try:
  blocked=run(dict(op='toggle',target=target,kind='mod',file='mods/test.jar',enabled=False));assert blocked['type']=='failed' and 'Minecraft' in blocked['data'],blocked
 finally: blocker.terminate();blocker.wait(timeout=5)

 world=game/'saves/World';(world/'datapacks').mkdir(parents=True);(world/'level.dat').write_bytes(b'level')
 with zipfile.ZipFile(world/'datapacks/test.zip','w') as archive:archive.writestr('pack.mcmeta','{"pack":{"pack_format":48,"description":"Smoke"}}')
 disabled=run(dict(op='toggle',target=target,kind='datapack',file='saves/World/datapacks/test.zip',enabled=False));assert disabled['type']=='done',disabled
 assert (world/'datapacks/test.zip.disabled').exists()
 request=dict(op='project',project='P7dR8mSH',kind='mod',target=target)
 process=subprocess.Popen([str(java),'-Xms16m','-Xmx512m','-Dfile.encoding=UTF-8',f'-Duser.home={home}','-Daw.native.backend=true','-cp',str(app/'app/*'),'ru.aw.launcher.MainKt','--native-task'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
 out,err=process.communicate(json.dumps(request)+'\ncancel\n',timeout=30)
 terminal=[json.loads(line[7:]) for line in out.splitlines() if line.startswith('AW_RPC ') and json.loads(line[7:])['type'] in ('done','cancelled','failed')]
 assert any(item['type']=='cancelled' for item in terminal),(terminal,err)
 print('Datapack state and protocol cancellation passed')
 print('Native settings, history restore, accounts and running-game guard passed')
 print('Native protocol: ping, scan, disable, dependency-aware enable, history and path rejection passed')
