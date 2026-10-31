import json,subprocess,pathlib,sys,shutil
root=pathlib.Path(__file__).resolve().parent.parent
output=root/'build/distribution-legal/rust'
platform='x86_64-pc-windows-msvc' if sys.platform=='win32' else 'x86_64-unknown-linux-gnu'
command=['cargo','metadata','--locked','--format-version','1','--filter-platform',platform,'--manifest-path',str(root/'rust/ui/Cargo.toml')]
result=subprocess.run(command,capture_output=True,text=True,check=True)
data=json.loads(result.stdout)
output.mkdir(parents=True,exist_ok=True)
lines=['# Rust components','', '| Component | License | Source |','| --- | --- | --- |']
for package in sorted(data['packages'],key=lambda p:(p['name'],p['version'])):
 if package.get('source') is None:continue
 folder=pathlib.Path(package['manifest_path']).parent
 name=package['name']+'-'+package['version']
 lines.append('| '+name+' | '+str(package.get('license') or 'See notices')+' | '+str(package.get('repository') or 'https://crates.io/crates/'+package['name'])+' |')
 for file in folder.rglob("*"):
  if file.is_file() and file.name.upper().startswith(('LICENSE','LICENCE','COPYING','COPYRIGHT','NOTICE','UNLICENSE')):
   target=output/name/file.relative_to(folder);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(file,target)
(output/'THIRD_PARTY_RUST.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Collected Rust notices for',len(data['packages']),'packages')
