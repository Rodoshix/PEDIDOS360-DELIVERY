import { Component, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
@Component({selector:'app-profile',template:`<h1>Mi perfil</h1><p>Consulta tu identidad local autenticada mediante la API.</p><button (click)="load()" [disabled]="busy()">{{busy()?'Consultando…':'Consultar perfil'}}</button>@if(error()){<p role="alert" tabindex="-1">{{error()}}</p>}@if(profile()){<dl><dt>Nombre</dt><dd>{{profile()?.nombre}}</dd><dt>Correo</dt><dd>{{profile()?.email}}</dd><dt>Estado</dt><dd>{{profile()?.activo?'Activo':'Inactivo'}}</dd></dl>}`})
export class ProfileComponent {
  private api=inject(ApiService); busy=signal(false);error=signal('');profile=signal<{nombre:string;email:string;activo:boolean}|null>(null);
  async load(){if(this.busy())return;this.busy.set(true);this.error.set('');try{const p=await this.api.request<{nombre:string;email:string;activo:boolean}>('GET','/usuarios/me');if(!p||typeof p.nombre!=='string'||typeof p.email!=='string'||typeof p.activo!=='boolean')throw new Error('Respuesta de perfil inválida.');this.profile.set({nombre:p.nombre,email:p.email,activo:p.activo});}catch(e){this.error.set(e instanceof Error?e.message:'Consulta fallida.');}finally{this.busy.set(false);}}
}
