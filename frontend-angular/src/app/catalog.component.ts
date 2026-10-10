import { Component, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
interface Restaurant {id:number;nombre:string;estado:string;}
interface Product {id:number;nombre:string;precio:number;disponible:boolean;restauranteId:number;}
@Component({selector:'app-catalog',template:`<h1>Restaurantes</h1><p>Encuentra productos disponibles y agrégalos a tu carrito.</p><button (click)="load()" [disabled]="busy()">{{busy()?'Consultando…':'Consultar restaurantes'}}</button>
@if(error()){<p role="alert" tabindex="-1">{{error()}}</p>}
<section class="listing" aria-label="Restaurantes">@for(r of restaurants();track r.id){<article><h2>{{r.nombre}}</h2><p>{{r.estado}}</p><button (click)="productsFor(r.id)" [disabled]="busy()">Ver productos</button></article>}@empty{<p>Sin restaurantes consultados o disponibles.</p>}</section>
@if(selected()){<h2>Productos</h2><section class="listing">@for(p of products();track p.id){<article><h3>{{p.nombre}}</h3><p>{{p.precio}} CLP</p><button (click)="add(p.id)" [disabled]="busy() || !p.disponible">Agregar una unidad</button></article>}@empty{<p>No hay productos disponibles.</p>}</section>}
<p role="status">{{message()}}</p>`})
export class CatalogComponent {
  private api=inject(ApiService); restaurants=signal<Restaurant[]>([]); products=signal<Product[]>([]); selected=signal(0);busy=signal(false);error=signal('');message=signal('');
  async run(work:()=>Promise<void>){if(this.busy())return;this.busy.set(true);this.error.set('');try{await work();}catch(e){this.error.set(e instanceof Error?e.message:'No se pudo consultar.');}finally{this.busy.set(false);}}
  load(){return this.run(async()=>{const rows=await this.api.request<Restaurant[]>('GET','/restaurantes');if(!Array.isArray(rows)||rows.some(r=>!Number.isSafeInteger(r.id)||r.id<=0||typeof r.nombre!=='string'))throw new Error('Respuesta de catálogo inválida.');this.restaurants.set(rows);});}
  productsFor(id:number){return this.run(async()=>{const rows=await this.api.request<Product[]>('GET',`/productos/restaurante/${id}`);if(!Array.isArray(rows)||rows.some(p=>p.restauranteId!==id||!Number.isSafeInteger(p.id)||p.id<=0||typeof p.nombre!=='string'||typeof p.disponible!=='boolean'||!Number.isSafeInteger(p.precio)||p.precio<0))throw new Error('Respuesta de productos inválida.');this.selected.set(id);this.products.set(rows);});}
  add(id:number){return this.run(async()=>{await this.api.request('POST','/carrito/items',{productoId:id,cantidad:1});this.message.set('Producto agregado. Consulta el carrito para verificarlo.');});}
}
