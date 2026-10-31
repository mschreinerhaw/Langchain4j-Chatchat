// @vitest-environment jsdom
import {createApp,h,nextTick} from 'vue';
import {it,expect} from 'vitest';
import AppConfirmDialog from '../../components/AppConfirmDialog.vue';
import {useAppConfirmation} from './useAppConfirmation';

it('resolves only an explicit choice, renders application styling and cancels pending navigation',async()=>{
 const host=document.createElement('div');document.body.append(host);let choices;
 const app=createApp({setup(){choices=useAppConfirmation();return ()=>choices.confirmation.value?
  h(AppConfirmDialog,{...choices.confirmation.value,onConfirm:()=>choices.settleConfirmation(true),onCancel:()=>choices.settleConfirmation(false)}):null;}});
 app.mount(host);
 try {
  const accepted=choices.confirmAction('确认修改角色？');await nextTick();
  expect(document.querySelector('.app-confirm-dialog').textContent).toContain('确认修改角色');
  document.querySelector('.app-confirm-primary').click();await expect(accepted).resolves.toBe(true);await nextTick();
  const cancelled=choices.confirmAction('确认撤权？');await nextTick();
  document.querySelector('.app-confirm-dialog footer button').click();await expect(cancelled).resolves.toBe(false);await nextTick();
  const escaped=choices.confirmAction('退出确认');await nextTick();
  document.querySelector('.app-confirm-dialog').dispatchEvent(new Event('cancel',{cancelable:true}));
  await expect(escaped).resolves.toBe(false);await nextTick();
  const abandoned=choices.confirmAction('尚未决定');app.unmount();await expect(abandoned).resolves.toBe(false);
 }finally{host.remove();}
});
