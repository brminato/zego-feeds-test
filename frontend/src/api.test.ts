import { describe,it,expect,vi,afterEach } from 'vitest';
import { authorization,request } from './api';
afterEach(()=>vi.unstubAllGlobals());
describe('API client',()=>{
 it('encodes credentials',()=>expect(authorization('a@b.com','pass')).toBe('Basic YUBiLmNvbTpwYXNz'));
 it('reports authentication failure',async()=>{vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:401}));await expect(request('bad','/me')).rejects.toThrow('incorrect');});
 it('sends JSON and authorization',async()=>{const fetch=vi.fn().mockResolvedValue({ok:true,json:async()=>({id:1})});vi.stubGlobal('fetch',fetch);await request('Basic abc','/articles','POST',{title:'Hello'});expect(fetch).toHaveBeenCalledWith('/api/articles',expect.objectContaining({method:'POST',body:'{"title":"Hello"}',headers:expect.objectContaining({Authorization:'Basic abc'})}));});
});
