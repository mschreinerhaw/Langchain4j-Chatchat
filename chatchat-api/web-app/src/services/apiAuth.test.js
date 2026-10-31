// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiRequest, getStoredAuthSession, storeAuthSession, AUTH_REQUIRED_EVENT, fetchAuthorizedAgents, fetchDomainSkills } from './api.js';

beforeEach(() => {
  const values = new Map();
  vi.stubGlobal('localStorage',{getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,String(value)),removeItem:key=>values.delete(key)});
});
afterEach(() => vi.unstubAllGlobals());
describe('authentication response ownership', () => {
  it('uses caller catalogs for role users and preserves the dedicated Skills management route', async () => {
    const fetcher = vi.fn(async () => ({ok:true,status:200,json:async()=>({code:200,data:{}})}));
    vi.stubGlobal('fetch', fetcher);
    storeAuthSession({token:'caller',user:{permissionCodes:['workspace:chat:interact']}});
    await fetchAuthorizedAgents(); await fetchDomainSkills({keyword:'audit',page:0});
    expect(String(fetcher.mock.calls[0][0])).toContain('/interactions/resources/agents');
    expect(String(fetcher.mock.calls[1][0])).toContain('/interactions/resources/skills?keyword=audit&page=0');
    storeAuthSession({token:'manager',user:{permissionCodes:['capability:data-science']}});
    await fetchDomainSkills();
    expect(String(fetcher.mock.calls[2][0])).toContain('/data-science/domain-skills');
  });
  it.each([401,200])('does not let a stale HTTP %s response clear a newer login', async status => {
    storeAuthSession({token:'expired'});
    let respond;
    vi.stubGlobal('fetch',vi.fn(()=>new Promise(resolve=>{respond=resolve;})));
    const request=apiRequest('/data/workbench');
    storeAuthSession({token:'new-login'});
    respond({ok:status===200,status,json:async()=>({code:401,message:'Login required'})});
    await expect(request).rejects.toThrow('Login required');
    expect(getStoredAuthSession().token).toBe('new-login');
  });
  it('clears the matching expired session and asks for login',async()=>{
    storeAuthSession({token:'expired'});const listener=vi.fn();window.addEventListener(AUTH_REQUIRED_EVENT,listener);
    vi.stubGlobal('fetch',vi.fn(async()=>({ok:false,status:401,json:async()=>({code:401,message:'Login required'})})));
    try {await expect(apiRequest('/enterprise/auth/me')).rejects.toThrow('Login required');expect(getStoredAuthSession()).toBeNull();expect(listener).toHaveBeenCalled();}
    finally {window.removeEventListener(AUTH_REQUIRED_EVENT,listener);}
  });
  it('keeps a valid session on resource permission denial or gateway failure',async()=>{
    storeAuthSession({token:'valid'});
    for(const status of [403,502]){
      vi.stubGlobal('fetch',vi.fn(async()=>({ok:false,status,json:async()=>({code:status,message:'Resource unavailable'})})));
      await expect(apiRequest('/agents/workshop/agent')).rejects.toThrow();
      expect(getStoredAuthSession().token).toBe('valid');
    }
  });
});
