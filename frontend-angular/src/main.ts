import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { PublicClientApplication } from '@azure/msal-browser';
import { CONFIG, PublicConfig, validateConfig } from './app/config';
import { AUTH_CLIENT } from './app/auth.service';
import { AppComponent, HomeComponent } from './app/app.component';
import { CatalogComponent } from './app/catalog.component';
import { ProfileComponent } from './app/profile.component';
import { CommerceComponent } from './app/commerce.component';
async function main(){
  const response=await fetch('/config.json',{cache:'no-store'});if(!response.ok)throw new Error('configuration');
  const config=validateConfig(await response.json() as PublicConfig,location.origin);
  const msal=new PublicClientApplication({auth:{clientId:config.clientId,authority:`https://login.microsoftonline.com/${config.tenantId}`,redirectUri:config.redirectUri,postLogoutRedirectUri:config.redirectUri},cache:{cacheLocation:'sessionStorage'},system:{loggerOptions:{piiLoggingEnabled:false,loggerCallback:()=>{}}}});
  await msal.initialize();const result=await msal.handleRedirectPromise();
  const accounts=msal.getAllAccounts().filter(a=>a.tenantId.toLowerCase()===config.tenantId.toLowerCase());
  msal.setActiveAccount(result?.account?.tenantId.toLowerCase()===config.tenantId.toLowerCase()?result.account:accounts.length===1?accounts[0]:null);
  await bootstrapApplication(AppComponent,{providers:[{provide:CONFIG,useValue:config},{provide:AUTH_CLIENT,useValue:msal},provideHttpClient(),provideRouter([{path:'',component:HomeComponent},{path:'perfil',component:ProfileComponent},{path:'restaurantes',component:CatalogComponent},{path:'compras',component:CommerceComponent},{path:'**',redirectTo:''}])]});
}
main().catch(()=>{document.body.textContent='Pedidos360: configuración o inicio de sesión no disponible. Revisa la configuración pública y recarga.';});
