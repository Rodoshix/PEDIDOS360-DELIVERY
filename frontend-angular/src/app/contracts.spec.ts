import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { signal } from '@angular/core';
import { CONFIG, validateConfig, PublicConfig } from './config';
import { AUTH_CLIENT, AuthService } from './auth.service';
import { ApiService, ApiError } from './api.service';
import { CommerceService } from './commerce.service';
import { CatalogComponent } from './catalog.component';
import { ProfileComponent } from './profile.component';

const config:PublicConfig={apiBase:'https://api.example.test/api',clientId:'11111111-1111-1111-1111-111111111111',tenantId:'22222222-2222-2222-2222-222222222222',redirectUri:'http://localhost:4200',scope:'api://33333333-3333-3333-3333-333333333333/access_as_user',checkoutMode:'HTTP'};
const account={tenantId:config.tenantId,homeAccountId:'home',localAccountId:'local'};
const cart={restauranteId:1,total:1000,moneda:'CLP',items:[{productoId:2,nombre:'Producto',cantidad:1,precioUnitario:1000,subtotal:1000}]};
describe('Public configuration is closed and HTTP by default',()=>{
  it('accepts API Manager HTTPS and a registered local redirect',()=>expect(validateConfig(config,'http://localhost:4200').checkoutMode).toBe('HTTP'));
  for(const override of [{apiBase:'http://remote/api'},{redirectUri:'https://foreign.test'},{apiBase:'https://user:password@api.example.test/api'},{scope:'openid'},{clientId:'bad'},{checkoutMode:'bad'},{apiBase:'https://api.example.test/api?token=bad'}]){
    it(`rejects invalid public option ${Object.keys(override)[0]}`,()=>expect(()=>validateConfig({...config,...override} as PublicConfig,'http://localhost:4200')).toThrow());
  }
});
describe('MSAL delegated access token and account binding',()=>{
  let sdk:any, auth:AuthService;
  beforeEach(()=>{sdk={getActiveAccount:vi.fn(()=>account),addEventCallback:vi.fn(),acquireTokenSilent:vi.fn(async()=>({account,accessToken:'access-fixture',idToken:'id-fixture',tokenType:'Bearer',expiresOn:new Date(Date.now()+60000),scopes:[config.scope]})),loginRedirect:vi.fn(),logoutRedirect:vi.fn()};TestBed.configureTestingModule({providers:[{provide:CONFIG,useValue:config},{provide:AUTH_CLIENT,useValue:sdk}]});auth=TestBed.inject(AuthService);});
  it('uses silent delegated scope; never sends an ID token',async()=>{expect(await auth.token()).toBe('access-fixture');expect(sdk.acquireTokenSilent.mock.calls[0][0].scopes).toEqual([config.scope]);});
  for(const patch of [{accessToken:'id-fixture'},{expiresOn:new Date(0)},{scopes:['wrong']},{tokenType:'Other'},{account:{...account,homeAccountId:'other'}}]){
    it(`rejects untrusted token result ${Object.keys(patch)[0]}`,async()=>{const original=await sdk.acquireTokenSilent();sdk.acquireTokenSilent.mockResolvedValue({...original,...patch});await expect(auth.token()).rejects.toThrow();});
  }
  it('rejects foreign tenant before acquiring a token',async()=>{sdk.getActiveAccount.mockReturnValue({...account,tenantId:'other'});await expect(auth.token()).rejects.toThrow();expect(sdk.acquireTokenSilent).not.toHaveBeenCalled();});
  it('starts login through the real SDK interface, with delegated scope',async()=>{await auth.login();expect(sdk.loginRedirect).toHaveBeenCalledWith({scopes:[config.scope]});});
});
describe('HTTP API trust boundary',()=>{
  let http:HttpTestingController,api:ApiService,auth:any;
  beforeEach(()=>{auth={token:vi.fn(async()=>'access-fixture'),binding:vi.fn(()=> 'account-a')};TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),{provide:CONFIG,useValue:config},{provide:AuthService,useValue:auth}]});api=TestBed.inject(ApiService);http=TestBed.inject(HttpTestingController);});
  it('only calls the configured API, with Bearer access token',async()=>{const pending=api.request('GET','/usuarios/me');await Promise.resolve();await Promise.resolve();const req=http.expectOne(config.apiBase+'/usuarios/me');expect(req.request.headers.get('Authorization')).toBe('Bearer access-fixture');req.flush({id:1});await expect(pending).resolves.toEqual({id:1});http.verify();});
  it('cannot send tokens to an arbitrary destination',async()=>{await expect(api.request('GET','https://foreign.test')).rejects.toThrow();expect(auth.token).not.toHaveBeenCalled();});
  it('discards responses after account changes',async()=>{const pending=api.request('GET','/usuarios/me');await Promise.resolve();await Promise.resolve();const req=http.expectOne(config.apiBase+'/usuarios/me');auth.binding.mockReturnValue('account-b');req.flush({id:1});await expect(pending).rejects.toMatchObject({status:401});});
  it('sanitizes remote errors',async()=>{const pending=api.request('GET','/carrito');await Promise.resolve();await Promise.resolve();http.expectOne(config.apiBase+'/carrito').flush({secret:'never-visible'},{status:503,statusText:'sentinel'});await expect(pending).rejects.not.toThrow('never-visible');});
});
describe('Checkout and payments preserve single writes',()=>{
  let api:any,s:CommerceService,auth:any;
  const setup=(mode:'HTTP'|'RABBITMQ'='HTTP')=>{api={request:vi.fn()};auth={account:signal(account)};TestBed.configureTestingModule({providers:[{provide:ApiService,useValue:api},{provide:AuthService,useValue:auth},{provide:CONFIG,useValue:{...config,checkoutMode:mode}}]});s=TestBed.inject(CommerceService);TestBed.tick();};
  beforeEach(()=>{sessionStorage.clear();});
  it('GET 503 recovery preserves direction and never writes',async()=>{setup();s.direction.set('Dirección preservada');api.request.mockRejectedValueOnce(new ApiError(503)).mockResolvedValueOnce(cart);await s.loadCart();expect(s.error()).toBeTruthy();await s.loadCart();expect(s.cart()).toEqual(cart);expect(s.direction()).toBe('Dirección preservada');expect(api.request.mock.calls.map((c:any)=>c[0])).toEqual(['GET','GET']);});
  it('HTTP 201 then failed DELETE retries only DELETE',async()=>{setup();s.cart.set(cart);s.direction.set('Calle 123');api.request.mockResolvedValueOnce({pedidoId:7,estado:'CREADO',total:1000}).mockRejectedValueOnce(new ApiError(503)).mockResolvedValueOnce(null);await s.checkout();expect(s.clearPending()).toBe(true);await s.retryClear();await s.checkout();expect(api.request.mock.calls.map((c:any)=>c[0])).toEqual(['POST','DELETE','DELETE']);expect(s.clearPending()).toBe(false);});
  it('RabbitMQ 201 never deletes or promises an empty cart',async()=>{setup('RABBITMQ');s.cart.set(cart);s.direction.set('Calle 123');api.request.mockResolvedValueOnce({pedidoId:7,estado:'CREADO',total:1000});await s.checkout();await s.retryClear();expect(api.request).toHaveBeenCalledTimes(1);expect(s.message()).toContain('separadamente');expect(s.clearPending()).toBe(false);});
  it('uncertain POST cannot be repeated by recovery',async()=>{setup();s.cart.set(cart);s.direction.set('Calle 123');api.request.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(cart);await s.checkout();await s.loadCart();await s.checkout();expect(api.request.mock.calls.map((c:any)=>c[0])).toEqual(['POST','GET']);expect(s.uncertain()).toBe(true);});
  it('GET payment retry never changes Idempotency-Key or creates another payment',async()=>{setup();api.request.mockRejectedValueOnce(new ApiError(0)).mockRejectedValueOnce(new ApiError(503)).mockResolvedValueOnce([{pagoId:9,estado:'APROBADO'}]);await s.pay(7,'TARJETA');const original=sessionStorage.getItem('p360-angular:home:payment:7');await s.loadPayment(7);await s.loadPayment(7);await s.pay(7,'TARJETA');expect(sessionStorage.getItem('p360-angular:home:payment:7')).toBe(original);expect(api.request.mock.calls.map((c:any)=>c[0])).toEqual(['POST','GET','GET']);expect(api.request.mock.calls[0][3]['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);expect(s.payment()?.pagoId).toBe(9);});
  it('rejects invalid cart totals before checkout',async()=>{setup();api.request.mockResolvedValue({...cart,total:1});await s.loadCart();expect(s.cart()).toBeNull();expect(s.error()).toBeTruthy();});
  it('clears private UI state on account change',()=>{setup();s.cart.set(cart);s.orders.set([{pedidoId:1,total:1000,estado:'CREADO'}]);auth.account.set({...account,homeAccountId:'other'});TestBed.tick();expect(s.cart()).toBeNull();expect(s.orders()).toEqual([]);});
});
describe('Real component templates with contract fixtures',()=>{
  it('shows empty catalog, then authenticated products and add result',async()=>{const api={request:vi.fn().mockResolvedValueOnce([]).mockResolvedValueOnce([{id:1,nombre:'Restaurante'}]).mockResolvedValueOnce([{id:2,restauranteId:1,nombre:'Producto',precio:1000,disponible:true}]).mockResolvedValueOnce({})};TestBed.configureTestingModule({providers:[{provide:ApiService,useValue:api}]});const fixture=TestBed.createComponent(CatalogComponent);await fixture.componentInstance.load();fixture.detectChanges();expect(fixture.nativeElement.textContent).toContain('Sin restaurantes');await fixture.componentInstance.load();await fixture.componentInstance.productsFor(1);fixture.detectChanges();expect(fixture.nativeElement.textContent).toContain('Producto');await fixture.componentInstance.add(2);expect(api.request.mock.calls[3]).toEqual(['POST','/carrito/items',{productoId:2,cantidad:1}]);});
  it('renders the actual profile DTO without exposing identity proof',async()=>{TestBed.configureTestingModule({providers:[{provide:ApiService,useValue:{request:vi.fn(async()=>({nombre:'Demo',email:'demo@example.test',activo:true,pruebaIdentidad:'never-render'}))}}]});const fixture=TestBed.createComponent(ProfileComponent);await fixture.componentInstance.load();fixture.detectChanges();expect(fixture.nativeElement.textContent).toContain('demo@example.test');expect(fixture.nativeElement.textContent).not.toContain('never-render');});
});
