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
  process=subprocess.Popen([str(java),'-Dfile.encoding=UTF-8',f'-Duser.home={home}','-Daw.native.backend=true','-cp',str(app/'app/*'),'ru.aw.launcher.MainKt','--native-task'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
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
 account=run(dict(op='accountOffline',title='SmokePlayer'));assert account['type']=='done',account
 accounts=json.loads((root/'accounts.json').read_text(encoding='utf-8'));assert accounts['accounts'][0]['name']=='SmokePlayer'
 removed=run(dict(op='accountRemove',file=accounts['accounts'][0]['uuid']));assert removed['type']=='done',removed
 assert not json.loads((root/'accounts.json').read_text(encoding='utf-8')).get('accounts',[])
 blocker=subprocess.Popen([sys.executable,'-c','import time;time.sleep(15)','--gameDir',str(game)])
 try:
  blocked=run(dict(op='toggle',target=target,kind='mod',file='mods/test.jar',enabled=False));assert blocked['type']=='failed' and 'Minecraft' in blocked['data'],blocked
 finally: blocker.terminate();blocker.wait(timeout=5)

 world=game/'saves/World';(world/'datapacks').mkdir(parents=True);(world/'level.dat').write_bytes(b'level')
 with zipfile.ZipFile(world/'datapacks/test.zip','w') as archive:archive.writestr('pack.mcmeta','{"pack":{"pack_format":48,"description":"Smoke"}}')
 disabled=run(dict(op='toggle',target=target,kind='datapack',file='saves/World/datapacks/test.zip',enabled=False));assert disabled['type']=='done',disabled
 assert (world/'datapacks/test.zip.disabled').exists()
 request=dict(op='project',project='P7dR8mSH',kind='mod',target=target)
 process=subprocess.Popen([str(java),'-Dfile.encoding=UTF-8',f'-Duser.home={home}','-Daw.native.backend=true','-cp',str(app/'app/*'),'ru.aw.launcher.MainKt','--native-task'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
 out,err=process.communicate(json.dumps(request)+'\ncancel\n',timeout=30)
 terminal=[json.loads(line[7:]) for line in out.splitlines() if line.startswith('AW_RPC ') and json.loads(line[7:])['type'] in ('done','cancelled','failed')]
 assert any(item['type']=='cancelled' for item in terminal),(terminal,err)
 print('Datapack state and protocol cancellation passed')
 print('Native settings, history restore, accounts and running-game guard passed')
 print('Native protocol: ping, scan, disable, dependency-aware enable, history and path rejection passed')
