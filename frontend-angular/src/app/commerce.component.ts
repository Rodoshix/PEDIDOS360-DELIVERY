import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CommerceService } from './commerce.service';
@Component({selector:'app-commerce',imports:[FormsModule],template:`<h1>Carrito, pedidos y pagos</h1><p>Consulta el estado antes de volver a enviar una operación.</p><div class="actions"><button (click)="s.loadCart()" [disabled]="s.busy()">Consultar / reintentar carrito</button><button (click)="s.loadOrders()" [disabled]="s.busy()">Mis pedidos</button></div>
@if(s.busy()){<p role="status">Procesando…</p>}@if(s.error()){<p role="alert" tabindex="-1">{{s.error()}}</p>}<p role="status">{{s.message()}}</p>
@if(s.cart();as cart){<h2>Carrito</h2><ul>@for(item of cart.items;track item.productoId){<li>{{item.nombre}} — {{item.cantidad}} × {{item.precioUnitario}} CLP</li>}@empty{<li>Tu carrito está vacío.</li>}</ul><p>Total: {{cart.total}} CLP</p>
<label for="direction">Dirección de entrega</label><input id="direction" [ngModel]="s.direction()" (ngModelChange)="s.direction.set($event)" maxlength="250" autocomplete="street-address">
<button (click)="s.checkout()" [disabled]="s.busy() || !!s.order() || s.uncertain() || !cart.items.length">Registrar pedido</button>}
@if(s.order();as order){<p>Pedido #{{order.pedidoId}} registrado.</p>}@if(s.clearPending()){<button (click)="s.retryClear()" [disabled]="s.busy()">Reintentar únicamente vaciado HTTP</button>}
<h2>Mis pedidos</h2><ul>@for(order of s.orders();track order.pedidoId){<li>Pedido #{{order.pedidoId}} — {{order.estado}} — {{order.total}} CLP <button (click)="pedidoId=order.pedidoId;s.loadPayment(order.pedidoId)" [disabled]="s.busy()">Consultar pagos</button></li>}@empty{<li>No hay pedidos consultados.</li>}</ul>
<h2>Pago de pedido</h2><label for="pedido">ID del pedido</label><input id="pedido" type="number" min="1" [(ngModel)]="pedidoId"><label for="method">Método</label><select id="method" [(ngModel)]="method"><option>TARJETA</option><option>EFECTIVO</option></select><div class="actions"><button (click)="s.loadPayment(pedidoId)" [disabled]="s.busy()">Consultar / reintentar GET</button><button (click)="s.pay(pedidoId,method)" [disabled]="s.busy()">Registrar pago</button></div>@if(s.payment();as payment){<p>Pago #{{payment.pagoId}} — {{payment.estado}}</p>}`})
export class CommerceComponent {s=inject(CommerceService);pedidoId=0;method='TARJETA';}
