"use strict";
(() => {
  const svg=(path)=>`<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${path}"/></svg>`;
  const check=svg('M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z');
  const more=svg('M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4z');
  const close=svg('m18.3 5.71-1.41-1.42L12 9.17 7.11 4.29 5.7 5.71 10.59 10.6 5.7 15.49l1.41 1.42L12 12.01l4.89 4.9 1.41-1.42-4.89-4.89z');
  const arrow=svg('m7.41 8.59 4.59 4.58 4.59-4.58L18 10l-6 6-6-6z');
  const plus=svg('M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z');

  class Skins {
    constructor(root,host) {
      this.root=root;this.host=host;this.owner='';this.saved=[];this.presets=[];this.choice='current';this.query='';this.sections=new Map([['saved',true],['Стандартные скины',true]]);
      this.images=new Map();this.pending=new Map();this.queue=[];this.busy=false;this.closed=false;this.generation=0;
      this.abort=new AbortController();const opts={signal:this.abort.signal};
      root.innerHTML=`<div class="row spread skin-heading"><h1>Скины</h1><label class="skin-account"><img class="icon" src="assets/nav/account.png" alt=""><select aria-label="Аккаунт для скина"></select></label></div><div class="skins-layout"><aside class="skin-inspector"><strong class="skin-owner" data-native-text></strong><div class="skin-live-preview"></div><div class="skin-inspector-actions"></div><small class="skin-account-hint"></small></aside><section class="skin-library"><div class="search"><img class="icon" src="assets/nav/search.png" alt=""><input type="search" aria-label="Поиск скинов или набора" placeholder="Поиск скинов или набора…"></div><div class="skin-gallery" aria-label="Библиотека скинов"><span class="loading"></span></div></section></div><div class="skin-error" role="alert" hidden><span></span><button data-skin="refresh" class="icon-button" aria-label="Обновить профиль"><img class="icon" src="assets/nav/refresh.png" alt=""></button></div><input class="skin-file-input" type="file" accept="image/png,.png" hidden>`;
      this.preview=AWSkinPreview.mount(root.querySelector('.skin-live-preview'),{onError:e=>this.error(e)});
      const selector=root.querySelector('select');selector.addEventListener('change',()=>this.activate(selector.value),opts);
      root.querySelector('input[type=search]').addEventListener('input',e=>{this.query=e.target.value;this.sections.clear();this.renderGallery();root.querySelector('.skin-gallery').scrollTop=0;},opts);
      root.querySelector('.skin-file-input').addEventListener('change',async e=>{const file=e.target.files[0];e.target.value='';if(file)await this.importFile(file);},opts);
      root.addEventListener('click',e=>{const button=e.target.closest('[data-skin]');if(!button||button.disabled||button.getAttribute('aria-disabled')==='true')return;e.preventDefault();const action=button.dataset.skin;
        if(action==='pick')this.pick(button.dataset.id);if(action==='section')this.toggle(button.dataset.section,button);
        if(action==='import')root.querySelector('.skin-file-input').click();if(action==='edit')this.edit();
        if(action==='menu')this.menu(button);if(action==='reset')this.pick('current');if(action==='apply')this.apply();if(action==='refresh')this.refreshProfile();if(action==='retry-texture'){this.images.delete(button.dataset.id);this.renderGallery();}
      },opts);
      root.addEventListener('keydown',e=>{if(e.target.matches('.skin-card')&&['Enter',' '].includes(e.key)){e.preventDefault();if(!this.busy)this.pick(e.target.dataset.id);}},opts);
      root.addEventListener('dragover',e=>{if(e.dataTransfer.types.includes('Files')){e.preventDefault();if(!this.busy)root.classList.add('skin-file-over');}},opts);
      root.addEventListener('dragleave',e=>{if(!root.contains(e.relatedTarget))root.classList.remove('skin-file-over');},opts);
      root.addEventListener('drop',e=>{root.classList.remove('skin-file-over');if(e.dataTransfer.files.length){e.preventDefault();if(!this.busy)this.importFile(e.dataTransfer.files[0]);}},opts);
      const gallery=root.querySelector('.skin-gallery');
      this.near=new IntersectionObserver(entries=>{for(const entry of entries)if(entry.isIntersecting){this.near.unobserve(entry.target);this.loadCard(entry.target);}},{root:gallery,rootMargin:'180px'});
      this.resize=new ResizeObserver(()=>{const w=root.clientWidth;root.style.setProperty('--skin-columns',w>=1800?6:w>=1500?5:w>=1100?4:w<420?2:3);this.preview.resume();});this.resize.observe(root);
      this.host.enhance(root);
    }
    error(error){if(this.closed)return;const box=this.root.querySelector('.skin-error');box.hidden=!error;box.querySelector('span').textContent=error?.message||error||'';}
    get profile(){return this.data?.profile||{};}
    meta(id=this.choice){return id==='current'?{id:'current',name:'Текущий скин',model:this.profile.model||'CLASSIC',capeId:this.profile.capeId}:this.saved.find(s=>s.id===id)||this.presets.find(s=>s.id===id);}
    image(id=this.choice){return id==='current'?Promise.resolve(this.profile.png||null):this.texture(id);}
    async activate(id) {
      const licensed=this.host.accounts().filter(a=>a.type==='MICROSOFT'),owner=licensed.find(a=>a.id===id)||licensed[0];
      const selector=this.root.querySelector('select');selector.innerHTML=licensed.map(a=>`<option value="${this.host.esc(a.id)}">${this.host.esc(a.name)}</option>`).join('');
      if(!owner){this.error('Войди через Microsoft, чтобы применить скин');this.root.querySelector('.skin-gallery').innerHTML='<p>Для изменения скина нужен лицензионный аккаунт Minecraft</p>';return;}
      selector.value=owner.id;this.host.enhance(this.root);selector.dispatchEvent(new Event('skin-selection'));
      if(this.owner===owner.id&&this.data){this.preview.resume();return;}
      if(this.busy){selector.value=this.owner;return;}
      const generation=++this.generation;this.refreshing=false;this.owner=owner.id;this.choice='current';this.data=null;this.error(null);this.root.querySelector('.skin-owner').textContent=owner.name;
      try {
        const data=await this.host.rpc({op:'accountSkinState',file:owner.id});if(this.closed||generation!==this.generation)return;
        this.data=data;this.saved=data.saved;this.presets=data.presets;this.model=this.profile.model;this.capeId=this.profile.capeId||null;this.images.clear();this.renderGallery();await this.updatePreview();this.refreshProfile();
      }catch(error){if(generation===this.generation)this.error(error);}
    }
    async refreshProfile() {
      if(!this.owner||this.refreshing||this.busy)return;if(!this.data)return this.activate(this.owner);this.refreshing=true;const generation=this.generation;
      try{const result=await this.host.rpc({op:'accountSkinProfile',file:this.owner});if(this.closed||generation!==this.generation)return;this.data.profile=result.profile;this.host.applyAccounts(result.accounts);this.error(null);if(this.choice==='current'){this.model=this.profile.model;this.capeId=this.profile.capeId||null;}this.renderGallery();await this.updatePreview();}
      catch(error){if(generation===this.generation)this.error(error);}
      finally{if(generation===this.generation)this.refreshing=false;}
    }
    texture(id) {
      if(this.images.has(id))return Promise.resolve(this.images.get(id));
      if(this.pending.has(id))return this.pending.get(id).promise;
      let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});this.pending.set(id,{promise,resolve,reject});this.queue.push(id);queueMicrotask(()=>this.flush());return promise;
    }
    async flush(){
      if(this.reading||this.closed||!this.queue.length)return;this.reading=true;
      const ids=this.queue.splice(0,8),owner=this.owner,generation=this.generation;
      try{const results=await this.host.rpc({op:'accountSkinTextures',file:owner,options:{ids}});
        for(const id of ids){const pending=this.pending.get(id);this.pending.delete(id);if(!pending)continue;const result=results.find(x=>x.id===id);if(this.closed||generation!==this.generation)pending.reject(Error('Раздел уже закрыт'));else if(result?.png){this.images.set(id,result.png);while(this.images.size>96)this.images.delete(this.images.keys().next().value);pending.resolve(result.png);}else pending.reject(Error(result?.error||'Не удалось загрузить текстуру'));}
      }catch(error){for(const id of ids){this.pending.get(id)?.reject(error);this.pending.delete(id);}}
      finally{this.reading=false;if(this.queue.length)this.flush();}
    }
    async pick(id){if(this.busy||!this.meta(id))return;const meta=this.meta(id);this.choice=id;this.model=meta.model;this.capeId=meta.capeId&&this.profile.capes?.some(c=>c.id===meta.capeId)?meta.capeId:null;this.markSelection();await this.updatePreview();}
    markSelection(){for(const node of this.root.querySelectorAll('.skin-card')){const picked=node.dataset.id===this.choice;node.classList.toggle('chosen',picked);node.setAttribute('aria-selected',String(picked));node.querySelector('.skin-picked').hidden=!picked;}}
    async updatePreview() {
      if(this.closed)return;const choice=this.choice,generation=this.generation;this.picked=null;this.updateActions();
      try{let png=await this.image();if(!png&&choice==='current'&&this.presets[0])png=await this.texture(this.presets[0].id);if(this.closed||generation!==this.generation||choice!==this.choice)return;this.picked=png;
        await this.preview.set({png,model:this.model});this.updateActions();
        const cape=this.capeId?await this.texture('cape:'+this.capeId):null;if(this.closed||choice!==this.choice||generation!==this.generation)return;await this.preview.set({cape});
      }catch(error){if(choice===this.choice&&generation===this.generation)this.error(error);}
    }
    changed(){return !!this.picked&&(this.picked!==this.profile.png||this.model!==this.profile.model)||this.capeId!==(this.profile.capeId||null);}
    updateActions(){if(this.closed)return;const pending=this.changed(),disabled=this.busy||!this.picked;this.root.querySelector('.skin-inspector-actions').innerHTML=pending?`<span class="skin-draft-badge">Предпросмотр</span><button data-skin="reset" ${this.busy?'disabled':''}>Сбросить</button><button class="primary" data-skin="apply" ${disabled?'disabled':''}>${this.busy?'Применяю…':'Применить'}</button>`:`<button data-skin="edit" ${disabled?'disabled':''}>${this.host.editIcon()}Редактировать скин</button>`;
      for(const button of this.root.querySelectorAll('.skin-card,.skin-add,.skin-card-menu')){button.setAttribute('aria-disabled',String(this.busy));if(button.tagName==='BUTTON')button.disabled=this.busy;}
      const select=this.root.querySelector('select');select.disabled=this.busy;if(select._trigger)select._trigger.disabled=this.busy;this.host.translate(this.root);
    }
    async perform(action){if(this.busy)return;this.busy=true;this.error(null);this.updateActions();try{await action();}catch(error){this.error(error);}finally{this.busy=false;if(!this.closed)this.updateActions();}}
    async apply(reset=false){await this.perform(async()=>{const options=reset?{changeSkin:true,changeCape:false,png:null}:{changeSkin:this.picked!==this.profile.png||this.model!==this.profile.model,changeCape:this.capeId!==(this.profile.capeId||null),png:this.picked,capeId:this.capeId};
      const result=await this.host.rpc({op:'accountSkinApply',file:this.owner,kind:this.model,options});this.data.profile=result.profile;this.host.applyAccounts(result.accounts);this.choice='current';this.model=this.profile.model;this.capeId=this.profile.capeId||null;this.renderGallery();await this.updatePreview();this.host.toast(`Внешний вид ${this.profile.name} изменён в профиле Minecraft`);
    });}
    card(skin,current=false){const e=this.host.esc,picked=this.choice===skin.id;return `<article class="skin-card ${picked?'chosen':''}" role="option" tabindex="0" aria-selected="${picked}" data-skin="pick" data-id="${e(skin.id)}" draggable="${!current&&this.saved.some(s=>s.id===skin.id)}"><div class="skin-card-image"><img alt="${e(skin.name)}" hidden><span class="skin-card-placeholder"><img class="icon" src="assets/nav/image.png" alt=""></span><span class="skin-picked" ${picked?'':'hidden'}>${check}</span>${current||this.saved.some(s=>s.id===skin.id)?`<button class="skin-card-menu icon-button" data-skin="menu" data-id="${e(skin.id)}" aria-label="Действия: ${e(skin.name)}">${more}</button>`:''}</div><strong data-native-text title="${e(skin.name)}">${e(skin.name)}</strong></article>`;}
    renderGallery(){
      if(this.closed||!this.data)return;const gallery=this.root.querySelector('.skin-gallery'),e=this.host.esc,q=this.query.trim().toLocaleLowerCase(),saved=this.saved.filter(s=>!q||s.name.toLocaleLowerCase().includes(q)),sections=[];
      if(!q||saved.length)sections.push({id:'saved',name:'Сохранённые скины',items:saved,extra:!q});
      const grouped=new Map();for(const skin of this.presets){if(q&&!skin.name.toLocaleLowerCase().includes(q)&&!skin.section.toLocaleLowerCase().includes(q))continue;if(!grouped.has(skin.section))grouped.set(skin.section,[]);grouped.get(skin.section).push(skin);}
      for(const [section,items] of grouped)sections.push({id:section,name:`${section} · ${items.length}`,items});
      this.near.disconnect();gallery.style.paddingBottom='';gallery.innerHTML=sections.map(section=>{const expanded=this.sections.get(section.id)??(!!q||section.id==='saved'||section.id==='Стандартные скины');return `<section class="skin-section" data-section="${e(section.id)}"><button class="skin-section-heading" data-skin="section" data-section="${e(section.id)}" aria-expanded="${expanded}"><span>${e(section.name)}</span><span class="skin-section-arrow ${expanded?'expanded':''}">${arrow}</span></button>${expanded?`<div class="skin-grid" role="listbox" aria-label="${e(section.name)}">${section.extra?`<button class="skin-add" data-skin="import">${plus}<strong>Добавить скин</strong></button>${this.card({id:'current',name:'Текущий скин',model:this.profile.model},true)}`:''}${section.items.map(s=>this.card(s)).join('')}</div>`:''}</section>`;}).join('')||'<p class="skin-no-results">Скины не найдены</p>';
      gallery.querySelectorAll('.skin-card').forEach(card=>this.near.observe(card));
      for(const card of gallery.querySelectorAll('.skin-card[draggable=true]')){
        card.addEventListener('dragstart',e=>{if(this.busy){e.preventDefault();return;}this.dragId=card.dataset.id;e.dataTransfer.setData('application/x-aw-skin',this.dragId);e.dataTransfer.effectAllowed='move';card.classList.add('dragging');});
        card.addEventListener('dragend',()=>{this.dragId=null;card.classList.remove('dragging');});
        card.addEventListener('dragover',e=>{if(e.dataTransfer.types.includes('application/x-aw-skin'))e.preventDefault();});
        card.addEventListener('drop',e=>{const id=e.dataTransfer.getData('application/x-aw-skin');if(id){e.preventDefault();e.stopPropagation();this.move(id,this.saved.findIndex(s=>s.id===card.dataset.id));}});
      }
      this.markSelection();this.updateActions();
    }
    async loadCard(card){const id=card.dataset.id,generation=this.generation;try{const png=await this.image(id);if(!png)return;const thumb=await AWSkinPreview.thumbnail(png,this.meta(id)?.model||'CLASSIC');if(this.closed||!card.isConnected||generation!==this.generation)return;const img=card.querySelector('.skin-card-image>img');img.src=thumb;img.hidden=false;card.querySelector('.skin-card-placeholder').hidden=true;}catch(error){if(card.isConnected){card.querySelector('.skin-card-placeholder').innerHTML=`<button data-skin="retry-texture" data-id="${this.host.esc(id)}" class="skin-texture-retry" title="${this.host.esc(error.message)}">Повторить</button>`;}}}
    toggle(id,button){const gallery=this.root.querySelector('.skin-gallery'),top=button.getBoundingClientRect().top-gallery.getBoundingClientRect().top,open=button.getAttribute('aria-expanded')==='true';this.sections.set(id,!open);this.renderGallery();const next=[...gallery.querySelectorAll('.skin-section-heading')].find(n=>n.dataset.section===id);if(!next)return;const remaining=gallery.scrollHeight-(next.offsetTop-gallery.offsetTop),tail=Math.max(0,gallery.clientHeight-remaining-top);gallery.style.paddingBottom=tail+'px';gallery.scrollTop+=next.getBoundingClientRect().top-gallery.getBoundingClientRect().top-top;}
    menu(button){const id=button.dataset.id,skin=this.saved.find(s=>s.id===id),index=this.saved.indexOf(skin),e=this.host.esc;const items=id==='current'?[['reset-profile','Вернуть стандартный скин']]:[['edit','Редактировать'],['left','Влево',index<=0],['right','Вправо',index>=this.saved.length-1],['delete','Удалить']];
      const menu=this.host.popover(button,items.map(([action,label,disabled])=>`<button data-skin-menu="${action}" ${disabled?'disabled':''} class="${action==='delete'?'danger':''}">${e(label)}</button>`).join(''),'skin-context-menu',240);
      menu.addEventListener('click',event=>{const action=event.target.closest('[data-skin-menu]')?.dataset.skinMenu;if(!action||event.target.closest('button').disabled)return;this.host.closePopover();if(action==='edit')this.edit(skin);if(action==='left')this.move(id,index-1);if(action==='right')this.move(id,index+1);if(action==='reset-profile')this.apply(true);if(action==='delete')this.host.confirm('Удалить скин?',`${skin.name}. Удалится только сохранённый скин из библиотеки AWLauncher`,()=>this.remove(id));},{signal:this.abort.signal});
    }
    async move(id,position){if(position<0)return;await this.perform(async()=>{this.saved=await this.host.rpc({op:'accountSkinMove',file:this.owner,project:id,options:{position}});this.renderGallery();});}
    async remove(id){await this.perform(async()=>{this.saved=await this.host.rpc({op:'accountSkinRemove',file:this.owner,project:id});this.images.delete(id);if(this.choice===id){this.choice='current';this.model=this.profile.model;this.capeId=this.profile.capeId||null;}this.renderGallery();await this.updatePreview();});}
    async importFile(file){if(this.busy)return;try{const image=await this.readFile(file);this.openEditor({...image,name:file.name.replace(/\.png$/i,''),capeId:null});}catch(error){this.error(error);}}
    async readFile(file){if(file.size>1024*1024)throw Error('PNG скина должен быть не больше 1 МБ');const bytes=new Uint8Array(await file.arrayBuffer()),signature=[137,80,78,71,13,10,26,10];if(bytes.length<33||!signature.every((b,i)=>bytes[i]===b)||String.fromCharCode(...bytes.slice(12,16))!=='IHDR')throw Error('Выбери PNG скина размером 64×64 или 64×32');const header=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);if(header.getUint32(16)!==64||![32,64].includes(header.getUint32(20)))throw Error('Выбери PNG скина размером 64×64 или 64×32');
      const url=URL.createObjectURL(file);try{const img=new Image();img.src=url;await img.decode();if(img.width!==64||![32,64].includes(img.height))throw Error('Выбери PNG скина размером 64×64 или 64×32');const c=document.createElement('canvas');c.width=64;c.height=img.height;const ctx=c.getContext('2d',{willReadFrequently:true});ctx.drawImage(img,0,0);const slim=img.height===64&&ctx.getImageData(54,20,2,12).data.every((v,i)=>i%4!==3||v===0);return {png:c.toDataURL('image/png'),model:slim?'SLIM':'CLASSIC'};}finally{URL.revokeObjectURL(url);}}
    async edit(saved){try{const meta=saved||this.meta(),png=await this.image(meta.id);if(png)this.openEditor({png,name:meta.name,model:saved?meta.model:this.model,capeId:saved?meta.capeId:this.capeId,id:saved?.id||this.saved.find(s=>s.id===this.choice)?.id});}catch(error){this.error(error);}}
    openEditor(draft){
      if(this.busy||this.dialog)return;const e=this.host.esc,dialog=this.dialog=document.createElement('dialog');dialog.className='skin-editor-dialog';
      dialog.innerHTML=`<div class="modal-head"><h2>${draft.id?'Редактирование скина':'Добавление скина'}</h2><button class="icon-button" data-editor="close" aria-label="Закрыть">${close}</button></div><form class="skin-editor-form"><div class="skin-editor-body"><div class="skin-editor-preview"></div><div class="skin-editor-controls"><label>Название<input name="name" maxlength="80" required placeholder="Название скина" value="${e(draft.name)}"></label><div><h3>Текстура</h3><button type="button" data-editor="texture"><img class="icon" src="assets/nav/folder.png" alt="">Заменить текстуру</button><input type="file" accept="image/png,.png" hidden></div><div><h3>Модель рук</h3><div class="row skin-model-options"><button type="button" data-model="CLASSIC">Широкие</button><button type="button" data-model="SLIM">Тонкие</button></div></div><div><h3>Плащ</h3><div class="skin-cape-options" role="radiogroup" aria-label="Плащ"><button type="button" data-cape="" role="radio"><span>${close}</span><small>Без плаща</small></button>${(this.profile.capes||[]).map(c=>`<button type="button" data-cape="${e(c.id)}" role="radio" title="${e(c.alias||'Плащ')}"><span><canvas width="10" height="16" aria-label="${e(c.alias||'Плащ')}"></canvas></span><small data-native-text>${e(c.alias||'Плащ')}</small></button>`).join('')}</div>${this.profile.capes?.length?'':'<small>У этого аккаунта пока нет плащей</small>'}</div></div></div><p class="skin-editor-error error" role="alert" hidden></p><div class="skin-editor-footer"><button type="button" data-editor="close">Отменить</button><button type="submit" class="primary">${draft.id?'Сохранить':'Добавить скин'}</button></div></form>`;
      document.body.append(dialog);let model=draft.model,capeId=draft.capeId||null,png=draft.png,saving=false,reading=false,disposed=false,change=0;
      const error=error=>{const node=dialog.querySelector('.skin-editor-error');node.hidden=!error;node.textContent=error?.message||error||'';},preview=AWSkinPreview.mount(dialog.querySelector('.skin-editor-preview'),{png,model,onError:error});
      const update=async()=>{const generation=++change;dialog.querySelectorAll('[data-model]').forEach(b=>{const selected=b.dataset.model===model;b.classList.toggle('selected',selected);b.setAttribute('aria-pressed',String(selected));});dialog.querySelectorAll('[data-cape]').forEach(b=>{const selected=b.dataset.cape===(capeId||'');b.classList.toggle('selected',selected);b.setAttribute('aria-checked',String(selected));});preview.set({png,model});try{const cape=capeId?await this.texture('cape:'+capeId):null;if(!disposed&&change===generation)preview.set({cape});}catch(failure){error(failure);}};
      const dismiss=()=>{if(!saving&&!reading)dialog.close();};dialog.addEventListener('cancel',event=>{if(saving||reading)event.preventDefault();});dialog.addEventListener('close',()=>{disposed=true;preview.destroy();dialog.remove();if(this.dialog===dialog)this.dialog=null;},{once:true});
      const disabled=()=>{dialog.querySelectorAll('button,input').forEach(b=>b.disabled=saving||reading);};
      dialog.addEventListener('click',event=>{const button=event.target.closest('button');if(!button||button.disabled)return;if(button.dataset.editor==='close')dismiss();if(button.dataset.editor==='texture')dialog.querySelector('input[type=file]').click();if(button.dataset.model){model=button.dataset.model;update();}if('cape'in button.dataset){capeId=button.dataset.cape||null;update();}});
      dialog.querySelector('input[type=file]').addEventListener('change',async event=>{const file=event.target.files[0];event.target.value='';if(!file)return;reading=true;disabled();try{const data=await this.readFile(file);png=data.png;model=data.model;error(null);await update();}catch(failure){error(failure);}finally{reading=false;disabled();}});
      dialog.querySelector('form').addEventListener('submit',async event=>{event.preventDefault();if(saving||reading)return;const name=dialog.querySelector('[name=name]').value.trim();if(!name)return;saving=true;disabled();error(null);try{const result=await this.host.rpc({op:'accountSkinSave',file:this.owner,project:draft.id||'',title:name,kind:model,options:{png,capeId}});this.saved=result.saved;this.images.set(result.selected,png);this.choice=result.selected;this.model=model;this.capeId=capeId;saving=false;dialog.close();this.renderGallery();await this.updatePreview();}catch(failure){error(failure);}finally{saving=false;if(!disposed)disabled();}});
      for(const cape of this.profile.capes||[])this.texture('cape:'+cape.id).then(async png=>{const img=new Image();img.src=png;await img.decode();if(disposed)return;const node=[...dialog.querySelectorAll('[data-cape]')].find(b=>b.dataset.cape===cape.id);node?.querySelector('canvas')?.getContext('2d').drawImage(img,12,1,10,16,0,0,10,16);}).catch(error);
      dialog.showModal();update();this.host.translate(dialog);
    }
    resume(id){this.activate(id);this.preview.resume();}
    destroy(){this.closed=true;this.abort.abort();this.resize.disconnect();this.near.disconnect();this.preview.destroy();if(this.dialog)this.dialog.close();for(const pending of this.pending.values())pending.reject(Error('Раздел уже закрыт'));this.pending.clear();this.queue=[];}
  }
  window.AWSkins={mount:(root,host)=>new Skins(root,host)};
})();
