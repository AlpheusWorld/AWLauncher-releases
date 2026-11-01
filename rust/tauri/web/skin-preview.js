"use strict";
// The Kotlin SkinRenderer/PixelModel geometry, UVs, camera and poses, rendered in the webview.
(() => {
  const PI = Math.PI, sin = Math.sin, cos = Math.cos;
  const add = (a,b) => a.map((v,i)=>v+b[i]);
  const sub = (a,b) => a.map((v,i)=>v-b[i]);
  const mul = (a,n) => a.map(v=>v*n);
  const dot = (a,b) => a[0]*b[0]+a[1]*b[1]+a[2]*b[2];
  const cross = (a,b) => [a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
  function rotate([x,y,z],yaw,pitch) {
    const xx=x*cos(yaw)+z*sin(yaw),zz=-x*sin(yaw)+z*cos(yaw);
    return [xx,y*cos(pitch)-zz*sin(pitch),y*sin(pitch)+zz*cos(pitch)];
  }
  function boxUV(u,v,w,h,d) { return [[u+d,v+d,w,h],[u+d*2+w,v+d,w,h],[u,v+d,d,h],[u+d+w,v+d,d,h],[u+d,v,w,d],[u+d+w,v,w,d]]; }
  function box([x,y,z],w,h,d,uv,outer=false,cape=false) {
    const a=x-w/2,b=x+w/2,c=y-h/2,e=y+h/2,f=z-d/2,g=z+d/2;
    return [ [[a,e,g],[b,e,g],[b,c,g],[a,c,g]], [[b,e,f],[a,e,f],[a,c,f],[b,c,f]],
      [[a,e,f],[a,e,g],[a,c,g],[a,c,f]], [[b,e,g],[b,e,f],[b,c,f],[b,c,g]],
      [[a,e,f],[b,e,f],[b,e,g],[a,e,g]], [[a,c,g],[b,c,g],[b,c,f],[a,c,f]]
    ].map((p,i)=>({outer,cape,p:p.map((p,j)=>[...p,uv[i][0]+([1,2].includes(j)?uv[i][2]:0),uv[i][1]+(j>=2?uv[i][3]:0)])}));
  }
  function faces(model,yaw,pitch,time,wave,hasCape) {
    const result=[],arm=model==='SLIM'?3:4,bob=sin(time*1.7)*.12,yr=yaw*PI/180,pr=pitch*PI/180;
    function part(position,pivot,w,h,d,rx,ry,rz,u,v,ou,ov) {
      const inflate=w===8&&d===8?.5:.25;
      for(const face of [...box(position,w,h,d,boxUV(u,v,w,h,d)),...box(position,w+inflate*2,h+inflate*2,d+inflate*2,boxUV(ou,ov,w,h,d),true)]) {
        face.p=face.p.map(p=>{
          const q=sub(p.slice(0,3),pivot),r=rotate([q[0],q[1]*cos(rx)-q[2]*sin(rx),q[1]*sin(rx)+q[2]*cos(rx)],ry,0);
          return [...rotate([r[0]*cos(rz)-r[1]*sin(rz)+pivot[0],r[0]*sin(rz)+r[1]*cos(rz)+pivot[1]+bob,r[2]+pivot[2]],yr,pr),p[3],p[4]];
        });result.push(face);
      }
    }
    const breath=sin(time*1.7),glance=sin(time*.52)*.065,lift=wave>0?Math.max(0,sin(PI*wave)):0;
    part([0,12,0],[0,8,0],8,8,8,-.025+breath*.012,.05+glance,-.025,0,0,32,0);
    part([0,2,0],[0,-4,0],8,12,4,breath*.008,0,-.012,16,16,16,32);
    part([-4-arm/2,2,0],[-4,7.8,0],arm,12,4,-.04+breath*.018,0,-.1-lift*(2.4+sin(wave*PI*10)*.1),40,16,40,32);
    part([4+arm/2,2,0],[4,7.8,0],arm,12,4,.055-breath*.018,0,.075,32,48,48,48);
    part([-1.95,-10,0],[-1.95,-4,0],4,12,4,-.018,0,-.012,0,16,0,32);
    part([1.95,-10,0],[1.95,-4,0],4,12,4,.045,0,.008,16,48,0,48);
    if(hasCape)for(const face of box([0,-.3,-3.1],10,16,1,boxUV(0,0,10,16,1),false,true)) {
      const pivot=[0,7.7,-2.6],angle=.14+sin(time*1.7)*.025;
      face.p=face.p.map(p=>{const q=sub(p.slice(0,3),pivot);return [...rotate([q[0]+pivot[0],q[1]*cos(angle)-q[2]*sin(angle)+pivot[1]+bob,q[1]*sin(angle)+q[2]*cos(angle)+pivot[2]],yr,pr),p[3],p[4]];});result.push(face);
    }
    return result;
  }
  function normal(face) {const n=cross(sub(face.p[3],face.p[0]),sub(face.p[1],face.p[0]));return mul(n.slice(0,3),1/Math.sqrt(dot(n,n)));}
  // Same polygon splitting as the original: transparent outer layers retain the correct depth order.
  function ordered(polygons,eye,out=[]) {
    if(!polygons.length)return out;
    const splitter=polygons[0],n=splitter.n,origin=splitter.p[0],same=[],front=[],back=[];
    for(const face of polygons) {
      const ds=face.p.map(p=>dot(n,sub(p,origin)));
      if(ds.every(d=>Math.abs(d)<.001)){same.push(face);continue;}
      if(ds.every(d=>d>=-.001)){front.push(face);continue;}
      if(ds.every(d=>d<=.001)){back.push(face);continue;}
      const a=[],b=[];
      face.p.forEach((p,i)=>{const next=(i+1)%face.p.length,d=ds[i],nd=ds[next];if(d>=-.001)a.push(p);if(d<=.001)b.push(p);
        if((d>.001&&nd<-.001)||(d<-.001&&nd>.001)){const q=add(p,mul(sub(face.p[next],p),d/(d-nd)));a.push(q);b.push(q);}});
      if(a.length>=3)front.push({...face,p:a});if(b.length>=3)back.push({...face,p:b});
    }
    const near=dot(n,sub(eye,origin))>=0;ordered(near?back:front,eye,out);out.push(...same);ordered(near?front:back,eye,out);return out;
  }
  async function image(source) {const img=new Image();img.src=source;await img.decode();return img;}
  function normalize(img) {
    if(img.width!==64||![32,64].includes(img.height))throw Error('Выбери PNG скина размером 64×64 или 64×32');
    const canvas=document.createElement('canvas');canvas.width=canvas.height=64;const ctx=canvas.getContext('2d',{willReadFrequently:true});ctx.drawImage(img,0,0);
    if(img.height===32) {
      for(const [from,to] of [[[0,16],[16,48]],[[40,16],[32,48]]]) {
        const src=boxUV(...from,4,12,4),dst=boxUV(...to,4,12,4);
        for(let i=0;i<6;i++){const a=src[i===2?3:i===3?2:i],b=dst[i];ctx.save();ctx.translate(b[0]+b[2],b[1]);ctx.scale(-1,1);ctx.drawImage(img,...a,0,0,b[2],b[3]);ctx.restore();}
      }
      const hat=ctx.getImageData(32,0,32,16);if(hat.data.every((v,i)=>i%4!==3||v>=128))ctx.clearRect(32,0,32,16);
    }
    const data=ctx.getImageData(0,0,64,64);for(const [x,y,w,h] of [[0,0,32,16],[0,16,64,16],[16,48,32,16]])for(let yy=y;yy<y+h;yy++)for(let xx=x;xx<x+w;xx++)data.data[(yy*64+xx)*4+3]=255;ctx.putImageData(data,0,0);return canvas;
  }
  class Scene {
    constructor(canvas,{preserveDrawingBuffer=true}={}) {
      this.canvas=canvas;const gl=this.gl=canvas.getContext('webgl',{alpha:true,antialias:true,premultipliedAlpha:false,preserveDrawingBuffer,depth:true});
      if(!gl)throw Error('3D-предпросмотр недоступен: WebGL отключён');
      const shader=(type,code)=>{const s=gl.createShader(type);gl.shaderSource(s,code);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));return s;};
      const vertex=shader(gl.VERTEX_SHADER,'attribute vec3 p;attribute vec2 uv;attribute float light;attribute float cape;uniform vec2 size;uniform float focal;uniform float distance;varying vec2 vUV;varying float vLight;varying float vCape;void main(){float z=distance-p.z;gl_Position=vec4(2.0*focal/size.x*p.x,2.0*focal/size.y*(p.y+1.1)+0.06*z,((distance+65.0)*z-2.0*(distance+64.0))/(distance+63.0),z);vUV=uv/vec2(64.0,cape>0.5?32.0:64.0);vLight=light;vCape=cape;}');
      const fragment=shader(gl.FRAGMENT_SHADER,'precision mediump float;uniform sampler2D skin;uniform sampler2D capeSkin;uniform float pass;varying vec2 vUV;varying float vLight;varying float vCape;void main(){vec4 c=vCape>0.5?texture2D(capeSkin,vUV):texture2D(skin,vUV);if(c.a<0.0039||(pass<0.5?c.a<0.999:c.a>=0.999))discard;gl_FragColor=vec4(c.rgb*vLight,c.a);}');
      this.program=gl.createProgram();gl.attachShader(this.program,vertex);gl.attachShader(this.program,fragment);gl.linkProgram(this.program);gl.deleteShader(vertex);gl.deleteShader(fragment);if(!gl.getProgramParameter(this.program,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(this.program));
      gl.useProgram(this.program);this.buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,this.buffer);
      for(const [name,n,offset] of [['p',3,0],['uv',2,3],['light',1,5],['cape',1,6]]){const a=gl.getAttribLocation(this.program,name);gl.enableVertexAttribArray(a);gl.vertexAttribPointer(a,n,gl.FLOAT,false,28,offset*4);}
      this.masks=[];this.stream=new Float32Array(4096);this.textures=[gl.createTexture(),gl.createTexture()];gl.uniform1i(gl.getUniformLocation(this.program,'skin'),0);gl.uniform1i(gl.getUniformLocation(this.program,'capeSkin'),1);
      this.textures.forEach((texture,i)=>{gl.activeTexture(gl.TEXTURE0+i);gl.bindTexture(gl.TEXTURE_2D,texture);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,1,1,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array([0,0,0,0]));});
      gl.enable(gl.DEPTH_TEST);gl.depthFunc(gl.LEQUAL);gl.enable(gl.BLEND);gl.blendFuncSeparate(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA,gl.ONE,gl.ONE_MINUS_SRC_ALPHA);
    }
    async setSkin(source) {const generation=this.skinGeneration=(this.skinGeneration||0)+1,img=normalize(await image(source));if(this.closed||this.skinGeneration!==generation)return;this.texture(0,img);this.ready=true;}
    async setCape(source) {const generation=this.capeGeneration=(this.capeGeneration||0)+1;if(!source){this.hasCape=false;return;}const img=await image(source);if(this.closed||this.capeGeneration!==generation)return;if(img.width!==64||img.height!==32)throw Error('Некорректная текстура плаща');this.texture(1,img);this.hasCape=true;}
    texture(index,img) {const scan=document.createElement('canvas');scan.width=img.width;scan.height=img.height;const ctx=scan.getContext('2d',{willReadFrequently:true});ctx.drawImage(img,0,0);const pixels=ctx.getImageData(0,0,img.width,img.height).data,stride=img.width+1,prefix=new Uint16Array(stride*(img.height+1));for(let y=0;y<img.height;y++)for(let x=0;x<img.width;x++){const alpha=pixels[(y*img.width+x)*4+3],i=(y+1)*stride+x+1;prefix[i]=+(alpha>0&&alpha<255)+prefix[i-1]+prefix[i-stride]-prefix[i-stride-1];}this.masks[index]={prefix,stride};const gl=this.gl;gl.activeTexture(gl.TEXTURE0+index);gl.bindTexture(gl.TEXTURE_2D,this.textures[index]);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,img);}
    partial(face) {
      const mask=this.masks[face.cape?1:0];if(!mask)return false;
      const xs=face.p.map(p=>p[3]),ys=face.p.map(p=>p[4]),x=Math.min(...xs),right=Math.max(...xs),y=Math.min(...ys),bottom=Math.max(...ys),{prefix,stride}=mask;
      return prefix[bottom*stride+right]-prefix[y*stride+right]-prefix[bottom*stride+x]+prefix[y*stride+x]>0;
    }
    upload(polygons,eye) {
      const gl=this.gl,count=polygons.reduce((n,f)=>n+(f.p.length-2)*3,0),needed=count*7;
      if(needed>this.stream.length)this.stream=new Float32Array(2**Math.ceil(Math.log2(needed)));
      let offset=0;
      for(const face of polygons){const n=dot(face.n,sub(eye,face.p[0]))>0?face.n:mul(face.n,-1),light=Math.floor(204+Math.round(Math.max(0,Math.min(1,dot(n,[-3,4,2])/Math.sqrt(29)))*32)*51/32)/255;
        for(let i=1;i<face.p.length-1;i++)for(const p of [face.p[0],face.p[i],face.p[i+1]]){for(let j=0;j<5;j++)this.stream[offset++]=p[j];this.stream[offset++]=light;this.stream[offset++]=face.cape?1:0;}}
      gl.bindBuffer(gl.ARRAY_BUFFER,this.buffer);
      if(this.gpuBytes!==this.stream.byteLength){gl.bufferData(gl.ARRAY_BUFFER,this.stream.byteLength,gl.DYNAMIC_DRAW);this.gpuBytes=this.stream.byteLength;}
      gl.bufferSubData(gl.ARRAY_BUFFER,0,this.stream.subarray(0,needed));gl.drawArrays(gl.TRIANGLES,0,count);
    }
    draw({model='CLASSIC',yaw=-22.5,pitch=0,zoom=1,layers=true,time=0,wave=0}={}) {
      if(this.closed)return;const gl=this.gl,w=this.canvas.width,h=this.canvas.height;gl.viewport(0,0,w,h);gl.depthMask(true);gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);if(!this.ready||!w||!h)return;
      const focal=h/2/Math.tan(35*PI/360),distance=Math.max(17/.76,11.3/(w/h*.8))/Math.tan(35*PI/360)/zoom,eye=[0,-1.1,distance];
      const polygons=faces(model,yaw,pitch,time,wave,this.hasCape).filter(f=>layers||!f.outer).map(f=>({...f,n:normal(f)})).filter(f=>f.outer||dot(f.n,sub(eye,f.p[0]))>0);
      gl.useProgram(this.program);gl.uniform2f(gl.getUniformLocation(this.program,'size'),w,h);gl.uniform1f(gl.getUniformLocation(this.program,'focal'),focal);gl.uniform1f(gl.getUniformLocation(this.program,'distance'),distance);
      // Binary-alpha skin pixels use the GPU depth buffer, avoiding per-frame polygon splitting.
      gl.disable(gl.BLEND);gl.uniform1f(gl.getUniformLocation(this.program,'pass'),0);this.upload(polygons,eye);
      const translucent=polygons.filter(f=>this.partial(f));
      if(translucent.length){gl.enable(gl.BLEND);gl.depthMask(false);gl.uniform1f(gl.getUniformLocation(this.program,'pass'),1);this.upload(ordered(translucent,eye),eye);gl.depthMask(true);}
      this.canvas.dataset.ready='true';
    }
    destroy(){this.closed=true;const gl=this.gl;this.textures.forEach(t=>gl.deleteTexture(t));gl.deleteBuffer(this.buffer);gl.deleteProgram(this.program);}
  }
  function mount(root,{png,model='CLASSIC',cape=null,onError=()=>{}}={}) {
    root.innerHTML='<div class="skin-preview-ground" aria-hidden="true"></div><canvas tabindex="0" aria-label="3D-предпросмотр скина"></canvas><div class="skin-preview-tools"><button type="button" data-preview="layers" title="Слой одежды" aria-label="Слой одежды"><img src="assets/nav/layers.png" alt=""></button><button type="button" data-preview="animation" title="Анимации" aria-label="Анимации"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 5h4v14H6zm8 0h4v14h-4z"/></svg></button><button type="button" data-preview="reset" title="Сбросить вид" aria-label="Сбросить вид"><img src="assets/nav/refresh.png" alt=""></button></div><small class="skin-preview-hint">Тяни мышью для вращения · колёсико для масштаба</small><span class="skin-preview-status">Загружаю скин…</span>';
    const canvas=root.querySelector('canvas'),ground=root.querySelector('.skin-preview-ground'),status=root.querySelector('.skin-preview-status');let scene;
    try{scene=new Scene(canvas,{preserveDrawingBuffer:false});}catch(error){status.textContent=error.message;onError(error);return {set(){},resume(){},destroy(){}};}
    let yaw=-22.5,pitch=0,zoom=1,layers=true,playing=true,time=0,waveAt=0,last=0,frame=0,interactiveUntil=0,closed=false,focused=true,pointer=null,dragged=false;
    const abort=new AbortController(),opts={signal:abort.signal};
    function interact(){interactiveUntil=performance.now()+250;resume();}
    function reset(){yaw=-22.5;pitch=0;zoom=1;interact();}
    function tick(now){frame=0;if(closed||!focused||document.hidden||!canvas.isConnected||!canvas.getClientRects().length)return;const delta=Math.min(.1,(now-(last||now))/1000);if(now-last>=(now<interactiveUntil?12:32)){if(playing)time+=delta;last=now;scene.draw({model,yaw,pitch,zoom,layers,time,wave:waveAt?Math.min(1,(now-waveAt)/1600):0});const w=canvas.clientWidth,h=canvas.clientHeight,f=h/2/Math.tan(35*PI/360),distance=Math.max(17/.76,11.3/(w/h*.8))/Math.tan(35*PI/360)/zoom,scale=f/distance,p=rotate([0,-16.7,0],yaw*PI/180,pitch*PI/180),floor=h*.47-(p[1]+1.1)*f/(distance-p[2]);ground.style.width=20*scale+'px';ground.style.height=4.8*scale+'px';ground.style.transform=`translate(-50%,${floor-2.4*scale}px)`;if(waveAt&&now-waveAt>=1600)waveAt=0;}if(playing||waveAt)frame=requestAnimationFrame(tick);}
    function resume(){if(!frame&&!closed){last=0;frame=requestAnimationFrame(tick);}}
    function resize(){const r=canvas.getBoundingClientRect(),scale=Math.min(devicePixelRatio||1,1.5);const width=Math.max(1,Math.round(r.width*scale)),height=Math.max(1,Math.round(r.height*scale));if(canvas.width!==width)canvas.width=width;if(canvas.height!==height)canvas.height=height;resume();}
    const observer=new ResizeObserver(resize);observer.observe(canvas);
    canvas.addEventListener('pointerdown',e=>{pointer=[e.clientX,e.clientY];dragged=false;canvas.setPointerCapture(e.pointerId);canvas.focus();},opts);
    canvas.addEventListener('pointermove',e=>{if(!pointer)return;const dx=e.clientX-pointer[0],dy=e.clientY-pointer[1];if(Math.abs(dx)+Math.abs(dy)>1)dragged=true;yaw=(yaw+dx*.65)%360;pitch=Math.max(-35,Math.min(35,pitch+dy*.35));pointer=[e.clientX,e.clientY];interact();},opts);
    canvas.addEventListener('pointerup',()=>{if(!dragged){waveAt=performance.now();resume();}pointer=null;},opts);
    canvas.addEventListener('pointercancel',()=>pointer=null,opts);canvas.addEventListener('dblclick',reset,opts);
    canvas.addEventListener('wheel',e=>{e.preventDefault();zoom=Math.max(.7,Math.min(1.5,zoom-e.deltaY*.0007));interact();},{...opts,passive:false});
    canvas.addEventListener('keydown',e=>{if(['ArrowLeft','ArrowRight'].includes(e.key))yaw+=(e.key==='ArrowLeft'?-12:12);else if(['ArrowUp','ArrowDown'].includes(e.key))pitch=Math.max(-35,Math.min(35,pitch+(e.key==='ArrowUp'?6:-6)));else if(['+','=','-'].includes(e.key))zoom=Math.max(.7,Math.min(1.5,zoom+(e.key==='-'?-.1:.1)));else if(e.key==='Home')reset();else return;e.preventDefault();interact();},opts);
    root.querySelector('.skin-preview-tools').addEventListener('click',e=>{const action=e.target.closest('[data-preview]')?.dataset.preview;if(action==='layers'){layers=!layers;const b=e.target.closest('button');b.classList.toggle('off',!layers);b.setAttribute('aria-pressed',String(layers));}if(action==='animation'){playing=!playing;e.target.closest('button').innerHTML=playing?'<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 5h4v14H6zm8 0h4v14h-4z"/></svg>':'<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>';e.target.closest('button').setAttribute('aria-pressed',String(playing));}if(action==='reset')reset();resume();},opts);
    document.addEventListener('visibilitychange',resume,opts);window.addEventListener('focus',()=>{focused=true;resume();},opts);window.addEventListener('blur',()=>{focused=false;cancelAnimationFrame(frame);frame=0;},opts);
    async function set(value){try{if(value.model)model=value.model;if('png'in value&&value.png){status.hidden=false;await scene.setSkin(value.png);status.hidden=true;}if('cape'in value)await scene.setCape(value.cape);resize();}catch(error){status.textContent=error.message;status.hidden=false;onError(error);}}
    set({png,model,cape});return {set,resume,destroy(){closed=true;cancelAnimationFrame(frame);observer.disconnect();abort.abort();scene.destroy();}};
  }
  let thumbScene, thumbQueue=Promise.resolve();const thumbCache=new Map();
  async function thumbnail(png,model) {
    if(!thumbScene){const c=document.createElement('canvas');c.width=320;c.height=380;thumbScene=new Scene(c);}
    await thumbScene.setSkin(png);thumbScene.draw({model});return thumbScene.canvas.toDataURL('image/png');
  }
  window.AWSkinPreview={mount,thumbnail:(png,model)=>{const key=model+png;if(thumbCache.has(key))return Promise.resolve(thumbCache.get(key));const result=thumbQueue.then(async()=>{if(thumbCache.has(key))return thumbCache.get(key);const value=await thumbnail(png,model);thumbCache.set(key,value);while(thumbCache.size>96)thumbCache.delete(thumbCache.keys().next().value);return value;});thumbQueue=result.catch(()=>{});return result;},normalize,faces,boxUV,Scene};
})();
