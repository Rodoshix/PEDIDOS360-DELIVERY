import { inject, Injectable, signal, InjectionToken } from '@angular/core';
import { AccountInfo, CacheLookupPolicy, EventType, IPublicClientApplication } from '@azure/msal-browser';
import { CONFIG } from './config';
export const AUTH_CLIENT=new InjectionToken<IPublicClientApplication>('MSAL');
const key=(a:AccountInfo|null)=>a ? `${a.tenantId}:${a.homeAccountId}:${a.localAccountId}` : null;
@Injectable({providedIn:'root'})
export class AuthService {
  private client=inject(AUTH_CLIENT); private config=inject(CONFIG); private generation=0;
  readonly account=signal<AccountInfo|null>(null); readonly interacting=signal(false);
  constructor() {
    this.client.addEventCallback(event=>{
      if ((event.eventType===EventType.LOGOUT_START || event.eventType===EventType.ACTIVE_ACCOUNT_CHANGED)) { this.generation++; this.account.set(event.eventType===EventType.LOGOUT_START?null:this.client.getActiveAccount()); }
    });
    this.account.set(this.client.getActiveAccount());
  }
  async login() {
    if (this.interacting()) return;
    this.interacting.set(true);
    try { await this.client.loginRedirect({scopes:[this.config.scope]}); } finally { this.interacting.set(false); }
  }
  async logout() { this.generation++; this.account.set(null); await this.client.logoutRedirect({account:this.client.getActiveAccount()}); }
  binding() { return `${this.generation}:${key(this.client.getActiveAccount())}`; }
  async token():Promise<string> {
    const account=this.client.getActiveAccount(), binding=this.binding();
    if (this.interacting() || !account || account.tenantId.toLowerCase()!==this.config.tenantId.toLowerCase()) throw new Error('Inicia sesión para continuar.');
    try {
      const result=await this.client.acquireTokenSilent({account,scopes:[this.config.scope],cacheLookupPolicy:CacheLookupPolicy.AccessTokenAndRefreshToken});
      if (this.binding()!==binding || key(result.account)!==key(account) || !result.accessToken || result.accessToken===result.idToken
          || result.tokenType.toLowerCase()!=='bearer' || !(result.expiresOn && result.expiresOn.getTime()>Date.now())
          || !result.scopes.some(s=>s===this.config.scope || s==='access_as_user')) throw new Error('La sesión cambió o venció.');
      return result.accessToken;
    } catch { throw new Error('La sesión requiere autenticación. Vuelve a iniciar sesión.'); }
  }
}
