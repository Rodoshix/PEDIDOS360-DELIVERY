import { InjectionToken } from '@angular/core';
export interface PublicConfig { apiBase:string; clientId:string; tenantId:string; redirectUri:string; scope:string; checkoutMode:'HTTP'|'RABBITMQ'; }
export const CONFIG=new InjectionToken<PublicConfig>('public config');
export function validateConfig(value:PublicConfig, origin:string):PublicConfig {
  const uuid=/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
  if (!value || !uuid.test(value.clientId) || !uuid.test(value.tenantId)) throw new Error('Configuración pública incompleta.');
  const api=new URL(value.apiBase), redirect=new URL(value.redirectUri);
  for (const url of [api,redirect]) {
    if ((url.protocol!=='https:' && !(url.protocol==='http:' && url.hostname==='localhost')) || url.username || url.password || url.search || url.hash)
      throw new Error('Configuración de URL no permitida.');
  }
  const scope=value.scope.match(/^api:\/\/([0-9a-f-]+)\/access_as_user$/i);
  if (!scope || !uuid.test(scope[1]) || scope[1].toLowerCase()===value.clientId.toLowerCase() || redirect.origin!==origin
      || !api.pathname.endsWith('/api') || !['HTTP','RABBITMQ'].includes(value.checkoutMode)) throw new Error('Configuración de API o sesión inválida.');
  return Object.freeze({...value,apiBase:api.href.replace(/\/$/,'')});
}
