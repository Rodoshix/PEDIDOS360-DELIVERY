import { effect, inject, Injectable, signal } from '@angular/core';
import { ApiError, ApiService } from './api.service';
import { AuthService } from './auth.service';
import { CONFIG } from './config';
export interface Cart {restauranteId:number|null;total:number;moneda:string;items:{productoId:number;nombre:string;cantidad:number;precioUnitario:number;subtotal:number}[];}
export interface Order {pedidoId:number;estado:string;total:number;}
const positive=(id:number)=>Number.isSafeInteger(id)&&id>0;
@Injectable({providedIn:'root'})
export class CommerceService {
  private api=inject(ApiService);private auth=inject(AuthService);private config=inject(CONFIG);
  cart=signal<Cart|null>(null);orders=signal<Order[]>([]);busy=signal(false);error=signal('');message=signal('');direction=signal('');
  order=signal<Order|null>(null);clearPending=signal(false);uncertain=signal(false);payment=signal<{pagoId:number;estado:string}|null>(null);
  constructor(){let previous=this.auth.account()?.homeAccountId;effect(()=>{const current=this.auth.account()?.homeAccountId;if(current===previous)return;previous=current;this.cart.set(null);this.orders.set([]);this.payment.set(null);this.order.set(null);this.clearPending.set(false);this.direction.set('');this.message.set('');this.error.set('');});}
  private storageKey(name:string){return `p360-angular:${this.auth.account()?.homeAccountId || 'anonymous'}:${name}`;}
  private readPending(name:string){return sessionStorage.getItem(this.storageKey(name));}
  private setPending(name:string,value:string){sessionStorage.setItem(this.storageKey(name),value);}
  private removePending(name:string){sessionStorage.removeItem(this.storageKey(name));}
  async run(work:()=>Promise<void>){if(this.busy())return;this.busy.set(true);this.error.set('');try{await work();}catch(e){this.error.set(e instanceof Error?e.message:'Operación no confirmada.');}finally{this.busy.set(false);}}
  loadCart(){return this.run(async()=>{const c=await this.api.request<Cart>('GET','/carrito');if(!c||!Array.isArray(c.items)||c.moneda!=='CLP'||!Number.isSafeInteger(c.total)||c.total<0||c.items.some(i=>!positive(i.productoId)||!Number.isInteger(i.cantidad)||i.cantidad<1||typeof i.nombre!=='string'||i.subtotal!==i.cantidad*i.precioUnitario)||c.total!==c.items.reduce((n,i)=>n+i.subtotal,0))throw new Error('Carrito inválido.');this.cart.set(c);});}
  loadOrders(){return this.run(async()=>{const rows=await this.api.request<Order[]>('GET','/pedidos/me');if(!Array.isArray(rows)||rows.some(o=>!positive(o.pedidoId)||typeof o.estado!=='string'||!Number.isSafeInteger(o.total)))throw new Error('Pedidos inválidos.');this.orders.set(rows);});}
  checkout(){return this.run(async()=>{
    if(this.readPending('checkout')){this.uncertain.set(true);throw new Error('Ya existe un intento de pedido. Consulta tus pedidos; no se enviará otro POST.');}
    const c=this.cart(), address=this.direction().trim();
    if(!c||!c.items.length||!c.restauranteId||address.length<5||address.length>250)throw new Error('Consulta el carrito e ingresa una dirección válida.');
    const operationKey=this.storageKey('checkout');sessionStorage.setItem(operationKey,'uncertain');
    try{const created=await this.api.request<Order>('POST','/pedidos',{restauranteId:c.restauranteId,direccionEntrega:address,items:c.items.map(i=>({productoId:i.productoId,cantidad:i.cantidad}))});
      if(!created||!positive(created.pedidoId))throw new Error('Resultado de pedido no confirmado.');
      this.order.set(created);sessionStorage.setItem(operationKey,String(created.pedidoId));
    }catch(e){if(e instanceof ApiError && [400,401,403,404,409].includes(e.status))sessionStorage.removeItem(operationKey);else this.uncertain.set(true);throw e;}
    if(this.config.checkoutMode==='HTTP'){this.clearPending.set(true);await this.clearAfterOrder();}
    else this.message.set('Pedido registrado. El vaciado se procesa separadamente; consulta el carrito para conocer su estado.');
  });}
  private async clearAfterOrder(){await this.api.request('DELETE','/carrito');this.clearPending.set(false);this.message.set('Pedido registrado y vaciado HTTP confirmado.');}
  retryClear(){if(this.config.checkoutMode!=='HTTP'||!this.order()||!this.clearPending())return Promise.resolve();return this.run(()=>this.clearAfterOrder());}
  loadPayment(id:number){return this.run(async()=>{if(!positive(id))throw new Error('Pedido inválido.');const rows=await this.api.request<{pagoId:number;estado:string}[]>('GET',`/pagos/pedido/${id}`);if(!Array.isArray(rows)||rows.some(p=>!positive(p.pagoId)||typeof p.estado!=='string'))throw new Error('Pagos inválidos.');this.payment.set(rows.length?rows[rows.length-1]:null);this.message.set(rows.length?'Pago consultado.':'No hay pagos registrados en la consulta.');});}
  pay(id:number,method:string){return this.run(async()=>{if(!positive(id)||!['TARJETA','EFECTIVO'].includes(method))throw new Error('Pago inválido.');
    const slot=`payment:${id}`, saved=this.readPending(slot);
    if(saved){throw new Error('Existe un intento de pago. Consulta su estado; no se creará otro pago.');}
    const key=crypto.randomUUID(), operationKey=this.storageKey(slot);sessionStorage.setItem(operationKey,JSON.stringify({key,method}));
    try{const result=await this.api.request<{pagoId:number;estado:string}>('POST','/pagos',{pedidoId:id,metodo:method},{'Idempotency-Key':key});if(!result||!positive(result.pagoId))throw new Error('Resultado incierto.');this.payment.set(result);this.message.set('Pago registrado.');}
    catch(e){if(e instanceof ApiError&&[400,401,403,404,409].includes(e.status))sessionStorage.removeItem(operationKey);throw e;}
  });}
}
