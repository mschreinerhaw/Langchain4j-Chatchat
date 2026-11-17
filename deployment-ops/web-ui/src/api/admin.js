import { get, post } from './client';
export const adminApi = {
  audit: (signal) => get('/audit', signal),
  users: (signal) => get('/users', signal),
  saveUser: (user) => post('/users', user),
  entries: (signal) => get('/entries', signal),
  saveEntry: (entry) => post('/entries', entry),
};
