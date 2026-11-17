import { ref, computed } from 'vue';
import { get, post } from '../api/client';
const user = ref(null);
let initialized = false;
window.addEventListener('ops-session-expired', () => {
  user.value = null;
});
export function useSession() {
  return {
    user,
    admin: computed(() => user.value?.role === 'admin'),
    async restore() {
      if (!initialized) {
        try {
          user.value = await get('/me');
        } catch (error) {
          if (error.status !== 401) throw error;
        }
        initialized = true;
      }
      return user.value;
    },
    async login(credentials) {
      await post('/login', credentials);
      user.value = await get('/me');
      initialized = true;
    },
    async logout() {
      await post('/logout');
      user.value = null;
    },
  };
}
