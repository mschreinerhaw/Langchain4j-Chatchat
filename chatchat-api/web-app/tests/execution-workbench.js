import { createApp, h, reactive } from 'vue';
import ChatMessageList from '../src/components/ChatMessageList.vue';
import '../src/styles/app.css';
import '../src/styles/layout.css';
import '../src/styles/base.css';
import '../src/styles/pages/chat-assistant.css';
const state=reactive({messages:[]});
window.setWorkbenchFixture=message=>{state.messages=[message];};
createApp({render:()=>h(ChatMessageList,{messages:state.messages,displayUserId:'验收用户'})}).mount('#app');
