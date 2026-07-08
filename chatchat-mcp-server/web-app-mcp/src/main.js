import { createApp } from 'vue';
import 'bootstrap/dist/css/bootstrap.min.css';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import {
  Bell,
  Coin,
  Connection,
  Cpu,
  DataLine,
  FolderOpened,
  Lock,
  Plus,
  Refresh,
  Search,
  Setting,
  SwitchButton,
  Tickets,
  User
} from '@element-plus/icons-vue';
import App from './App.vue';
import './styles/common.css';
import './styles/tailwind.css';

const app = createApp(App);

app.use(ElementPlus);
Object.entries({
  Bell,
  Coin,
  Connection,
  Cpu,
  DataLine,
  FolderOpened,
  Lock,
  Plus,
  Refresh,
  Search,
  Setting,
  SwitchButton,
  Tickets,
  User
}).forEach(([key, component]) => app.component(key, component));

app.mount('#app');
