'use strict';
window.BMLaunchEditor=async function({read,api,header}){
 const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;};
 const defaults={enabled:true,message:'每一球 都有迹可循',displayDurationMs:800,startsAt:null,endsAt:null,backgroundImage:null};
 let draft=await read('launch-screen'),busy=false;
 const root=el('div'),layout=el('div',undefined,'launch-layout'),form=el('form',undefined,'panel launch-form'),previewPanel=el('section',undefined,'panel launch-preview-panel');
 root.append(header('开屏配置','BM 和 App 图标固定。配置下载完成后，在下次启动时使用；断网时沿用本地有效缓存。'));
 const status=el('p','', 'launch-status');status.setAttribute('role','status');
 const label=(title,input,hint)=>{const l=el('label',undefined,'launch-field');l.append(el('span',title),input);if(hint)l.append(el('small',hint));form.append(l);return input;};
 const enabled=el('input');enabled.type='checkbox';enabled.checked=draft.enabled;label('启用 App 内开屏',enabled,'关闭后直接进入首页，系统启动页仍保留。');
 const file=el('input');file.type='file';file.accept='image/png,image/jpeg';label('背景图片',file,'建议竖图 1206 × 2622，PNG / JPEG，最多 3 MB。图片上会叠加深色遮罩。');
 const clear=el('button','使用默认深绿背景');clear.type='button';form.append(clear);
 const message=el('textarea');message.rows=2;message.maxLength=80;message.value=draft.message;label('底部文案',message,'最多 40 字、两行；留空仅显示 App 图标。');
 const duration=el('input');duration.type='number';duration.min='.3';duration.max='2';duration.step='.1';duration.value=draft.displayDurationMs/1000;label('显示时长（秒）',duration,'0.3 至 2 秒，用户可随时跳过。');
 const localValue=v=>v===null?'':new Date(v+8*3600000).toISOString().slice(0,16);
 const start=el('input'),end=el('input');for(const input of [start,end])input.type='datetime-local';start.value=localValue(draft.startsAt);end.value=localValue(draft.endsAt);
 label('开始时间（北京时间）',start,'留空立即生效。');label('结束时间（北京时间）',end,'到期后回到默认深绿开屏；留空长期有效。');
 const buttons=el('div',undefined,'actions'),save=el('button','发布配置','primary'),reset=el('button','恢复默认内容');save.type='submit';reset.type='button';buttons.append(save,reset);form.append(buttons,status);
 previewPanel.append(el('h2','效果预览'));
 const preview=el('div',undefined,'launch-preview'),base=el('img'),image=el('img',undefined,'launch-custom-bg'),shade=el('div',undefined,'launch-shade'),brand=el('img',undefined,'launch-fixed-brand'),footer=el('div',undefined,'launch-preview-footer'),icon=el('img'),caption=el('p');
 base.src='/admin/launch-background.png';base.alt='默认深绿背景';base.className='launch-default-bg';brand.src='/admin/launch-identity.png';brand.alt='固定 BM 品牌标识';icon.src='/admin/app-icon.png';icon.alt='固定 App 图标';image.alt='配置背景';footer.append(icon,caption);preview.append(base,image,shade,brand,footer);previewPanel.append(preview,el('p','预览显示所选内容；实际生效以启停和有效期为准。','fine'));
 const update=()=>{caption.textContent=message.value;caption.hidden=!message.value;image.hidden=shade.hidden=!draft.backgroundImage;if(draft.backgroundImage)image.src=draft.backgroundImage.path;else image.removeAttribute('src');};
 message.addEventListener('input',update);clear.addEventListener('click',()=>{draft.backgroundImage=null;file.value='';update();});
 reset.addEventListener('click',()=>{Object.assign(draft,defaults);enabled.checked=true;message.value=draft.message;duration.value=.8;start.value=end.value=file.value='';update();status.textContent='已恢复默认内容，点击“发布配置”后生效。';});
 const lock=value=>{busy=value;save.disabled=reset.disabled=file.disabled=clear.disabled=value;};
 file.addEventListener('change',async()=>{
  const selected=file.files[0];if(!selected)return;if(!['image/png','image/jpeg'].includes(selected.type)||selected.size>3*1024*1024){status.textContent='请选择不超过 3 MB 的 PNG 或 JPEG。';file.value='';return;}
  lock(true);status.textContent='正在上传背景…';try{
   const uploaded=await api('launch-screen/image',{method:'POST',headers:{'Content-Type':selected.type},body:selected});
   const probe=new Image();probe.src=uploaded.path;await probe.decode();draft.backgroundImage=uploaded;update();status.textContent='背景已上传，点击“发布配置”后生效。';
  }catch(e){status.textContent=e.message;}finally{lock(false);}
 });
 form.addEventListener('submit',async event=>{
  event.preventDefault();if(busy)return;lock(true);status.textContent='正在发布配置…';try{
   const value=input=>input.value?Date.parse(input.value+'+08:00'):null;
   const body={revision:draft.revision,enabled:enabled.checked,message:message.value,displayDurationMs:Math.round(Number(duration.value)*1000),startsAt:value(start),endsAt:value(end),backgroundImage:draft.backgroundImage};
   draft=await api('launch-screen',{method:'PUT',body:JSON.stringify(body)});message.value=draft.message;update();status.textContent='配置已发布。App 下载完成后，在下次启动时生效。';
  }catch(e){status.textContent=e.message;}finally{lock(false);}
 });
 update();layout.append(form,previewPanel);root.append(layout);return root;
};
