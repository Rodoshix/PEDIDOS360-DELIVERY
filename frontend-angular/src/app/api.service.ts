import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom, timeout } from 'rxjs';
import { CONFIG } from './config';
import { AuthService } from './auth.service';
export class ApiError extends Error {
  constructor(public status:number) { super(({400:'Solicitud inválida.',401:'Inicia sesión nuevamente.',403:'No tienes permiso para esta operación.',404:'Recurso no disponible.',409:'La operación entra en conflicto.'} as Record<number,string>)[status] || 'No se pudo confirmar la operación. Consulta su estado antes de volver a enviar.'); }
}
@Injectable({providedIn:'root'})
export class ApiService {
  private http=inject(HttpClient); private auth=inject(AuthService); private config=inject(CONFIG);
  async request<T>(method:string,path:string,body?:unknown,headers:Record<string,string>={}):Promise<T> {
    // Callers select known relative paths, never a destination receiving a bearer token.
    if (!/^\/[A-Za-z0-9/_-]+$/.test(path)) throw new ApiError(400);
    const binding=this.auth.binding(), token=await this.auth.token();
    try {
      const result=await firstValueFrom(this.http.request<T>(method,this.config.apiBase+path,{body,headers:{...headers,Authorization:`Bearer ${token}`}}).pipe(timeout(8000)));
      if (binding!==this.auth.binding()) throw new ApiError(401);
      return result;
    } catch(error) { throw error instanceof ApiError ? error : new ApiError(error instanceof HttpErrorResponse ? error.status : 0); }
  }
}
