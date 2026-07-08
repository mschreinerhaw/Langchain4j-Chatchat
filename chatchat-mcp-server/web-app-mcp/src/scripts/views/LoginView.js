import { login } from '../../services/session';

import '../../styles/views/login.css';

export default {
  name: 'LoginView',
  emits: ['authenticated'],
  data() {
    return {
      username: '',
      password: '',
      remember: false,
      error: '',
      busy: false
    };
  },
  methods: {
    async submit() {
      this.error = '';
      this.busy = true;
      try {
        const user = await login(this.username, this.password);
        this.$emit('authenticated', user);
      } catch (error) {
        this.error = error.message || '登录失败';
      } finally {
        this.busy = false;
      }
    }
  }
};



