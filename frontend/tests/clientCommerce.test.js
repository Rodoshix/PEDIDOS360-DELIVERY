import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createServer } from 'vite'
import { renderToStaticMarkup } from 'react-dom/server'
import { ApiAccessError } from '../src/auth/ApiAccessError.js'

const pedido = { pedidoId: 700, usuarioId: 10, restauranteId: 20, direccionEntrega: 'Av. Uno', estado: 'CREADO', moneda: 'CLP', total: 5500,
  fechaCreacion: '2026-10-09T12:00:00Z', lineas: [{ lineaId: 1, productoId: 101, cantidad: 1, precioUnitario: 5500, subtotal: 5500 }] }
const pago = { pagoId: 801, pedidoId: 700, usuarioId: 10, monto: 5500, moneda: 'CLP', metodo: 'TARJETA', estado: 'APROBADO', fecha: '2026-10-09T12:00:00Z' }

// Panels/handlers/controllers/adapters reales; hooks, router, sesión y HTTP controlados.
async function fixture(kind, { mode = 'HTTP', empty = false, payment = null, getFailure = false, writeFailure = false, deleteFailure = false } = {}) {
  const key = '__phase6_' + kind, slots = [], effects = [], calls = [], navigation = []
  let index = 0, focuses = 0, failRead = getFailure, failWrite = writeFailure
  const h = {
    useState(initial) { const n=index++; if (!(n in slots)) slots[n]=typeof initial==='function'?initial():initial
      return [slots[n], value => { slots[n]=typeof value==='function'?value(slots[n]):value }] },
    useRef(initial) { return h.useState(()=>({current:initial}))[0] },
    useEffect(effect,deps) {const n=index++; if(!slots[n] || deps.some((x,i)=>x!==slots[n][i]))effects.push(effect);slots[n]=deps},
    useSyncExternalStore(_subscribe,snapshot) {return snapshot()},
    navigate: id => navigation.push(id),
    client: {
      async get(path) {calls.push({method:'GET',path});if(failRead)throw new ApiAccessError('API_ERROR',503)
        return {status:200,data:path==='/carrito'?{restauranteId:20,items:[{productoId:101,nombre:'Producto de prueba',cantidad:1,subtotal:5500}]}:
          path==='/pedidos/me'?(empty?[]:[pedido]):path.startsWith('/pagos/')?(payment?[payment]:[]):pedido} },
      async post(path,body,options) {calls.push({method:'POST',path,body,key:options?.headers?.['Idempotency-Key']});if(failWrite)throw new ApiAccessError('API_TIMEOUT')
        return {status:201,data:path==='/pedidos'?pedido:{...pago,metodo:body.metodo,estado:body.metodo==='EFECTIVO'?'PENDIENTE':'APROBADO'}}},
      async delete(path) {calls.push({method:'DELETE',path});if(deleteFailure)throw new ApiAccessError('API_TIMEOUT');return {status:204}},
    },
  }
  globalThis[key]=h
  const target={checkout:'pedidos/RealConfirmarPedidoPanel.jsx',history:'pedidos/RealMisPedidosPanel.jsx',detail:'pedidos/RealPedidoDetallePanel.jsx',payment:'pagos/RealPagoPanel.jsx'}[kind]
  const server=await createServer({define:{'import.meta.env.VITE_PEDIDOS_CARRITO_MODE':JSON.stringify(mode)},cacheDir:'node_modules/.vite-phase6',optimizeDeps:{noDiscovery:true,include:[]},
    server:{middlewareMode:true,hmr:false,ws:false,watch:null},appType:'custom',plugins:[{name:'phase6-fixtures',enforce:'pre',
      resolveId(id){if(id.startsWith('virtual:phase6-'))return id},
      load(id){if(id==='virtual:phase6-hooks')return `export const {useState,useRef,useEffect,useSyncExternalStore}=globalThis.${key};`
        if(id==='virtual:phase6-router')return `export const Link='a';export const useNavigate=()=>globalThis.${key}.navigate;export const useLocation=()=>({pathname:'/test',search:'',hash:''});`
        if(id==='virtual:phase6-session')return 'export const useAuthSession=()=>({busy:false,login(){},authorizeApi(){}});'
        if(id==='virtual:phase6-http')return `export default globalThis.${key}.client;`},
      transform(source,id){if(!id.replaceAll('\\','/').endsWith(target))return
        return source.replace("from 'react'","from 'virtual:phase6-hooks'").replace("from 'react-router'","from 'virtual:phase6-router'")
          .replace("from '../../auth/useAuthSession.js'","from 'virtual:phase6-session'").replaceAll("import('../../services/httpClient.js')","import('virtual:phase6-http')")},
    }]})
  const Panel=(await server.ssrLoadModule('/src/features/'+target)).default
  const render=()=>{index=0;const tree=Panel({pedidoId:700});for(const n of nodes(tree))if(n.props?.ref)n.props.ref.current={focus(){focuses++}};for(const effect of effects.splice(0))effect();return tree}
  render();await new Promise(resolve=>setTimeout(resolve,20));render();await new Promise(resolve=>setTimeout(resolve,10))
  return {render,calls,navigation,focuses:()=>focuses,recover:()=>{failRead=false;failWrite=false},
    close:async()=>{await server.close();delete globalThis[key]}}
}
function nodes(tree){if(!tree||typeof tree!=='object')return [];if(Array.isArray(tree))return tree.flatMap(nodes);return [tree,...nodes(tree.props?.children)]}
function text(tree){if(typeof tree==='string'||typeof tree==='number')return String(tree);if(Array.isArray(tree))return tree.map(text).join(' ');return tree?.props?text(tree.props.children):''}
const button=(tree,label)=>nodes(tree).find(n=>n.type==='button'&&text(n)===label)
async function submitCheckout(f){nodes(f.render()).find(n=>n.type==='input').props.onChange({target:{value:'Dirección conservada'}});const submit=nodes(f.render()).find(n=>n.type==='form').props.onSubmit;await Promise.all([submit({preventDefault(){}}),submit({preventDefault(){}})]);return submit}

test('Checkout: UI etiquetada, POST único y DELETE exclusivo tras 201',async()=>{
  const f=await fixture('checkout',{deleteFailure:true});try{let html=renderToStaticMarkup(f.render());assert.match(html,/for="checkout-direccion"/);assert.match(html,/Productos del carrito/)
    const handler=await submitCheckout(f);assert.deepEqual(f.calls.map(x=>x.method),['GET','POST','DELETE']);await button(f.render(),'Reintentar vaciar carrito').props.onClick();await handler({preventDefault(){}})
    assert.deepEqual(f.calls.map(x=>x.method),['GET','POST','DELETE','DELETE'])}finally{await f.close()}
})
test('Checkout: incertidumbre anuncia bloqueo, foco y nunca repite POST',async()=>{
  const f=await fixture('checkout',{writeFailure:true});try{await submitCheckout(f);assert.match(text(f.render()),/puede haberse creado/);assert.ok(f.focuses()>0);
    // El formulario se retira tras incertidumbre: no invocar un callback de DOM ya desmontado.
    assert.equal(nodes(f.render()).some(n=>n.type==='form'),false);assert.equal(f.calls.filter(x=>x.method==='POST').length,1);assert.equal(f.calls.some(x=>x.method==='DELETE'),false)}finally{await f.close()}
})
test('Checkout RabbitMQ: 201 no anuncia vaciado, no DELETE ni otro pedido',async()=>{
  const f=await fixture('checkout',{mode:'RABBITMQ'});try{const handler=await submitCheckout(f);assert.match(text(f.render()),/todavía no está confirmado/);assert.equal(button(f.render(),'Reintentar vaciar carrito'),undefined);await button(f.render(),'Consultar carrito').props.onClick();await handler({preventDefault(){}});assert.deepEqual(f.calls.map(x=>x.method),['GET','POST','GET'])}finally{await f.close()}
})
test('Pago: EFECTIVO conserva selección, un POST y estado pendiente',async()=>{
  const f=await fixture('payment');try{nodes(f.render()).find(n=>n.type==='input'&&n.props.value==='EFECTIVO').props.onChange();const handler=nodes(f.render()).find(n=>n.type==='form').props.onSubmit;await Promise.all([handler({preventDefault(){}}),handler({preventDefault(){}})]);const writes=f.calls.filter(x=>x.method==='POST');assert.equal(writes.length,1);assert.equal(writes[0].body.metodo,'EFECTIVO');assert.ok(writes[0].key);assert.match(renderToStaticMarkup(f.render()),/Pendiente de cobro/)}finally{await f.close()}
})
test('Pago incierto: reintento explícito mantiene clave y no genera cobro automático',async()=>{
  const f=await fixture('payment',{writeFailure:true});try{await nodes(f.render()).find(n=>n.type==='form').props.onSubmit({preventDefault(){}});const first=f.calls.find(x=>x.method==='POST');assert.ok(first.key);assert.match(text(f.render()),/conserva la clave/);assert.ok(f.focuses()>0);f.recover();await button(f.render(),'Reintentar pago').props.onClick();assert.deepEqual(f.calls.filter(x=>x.method==='POST').map(x=>x.key),[first.key,first.key])}finally{await f.close()}
})
for(const estado of ['APROBADO','PENDIENTE','RECHAZADO'])test(`Pago: presentación ${estado}, monto y acción existente`,async()=>{
  const f=await fixture('payment',{payment:{...pago,estado}});try{const html=renderToStaticMarkup(f.render());assert.match(html,/5\.500/);assert.match(html,new RegExp(estado==='APROBADO'?'Aprobado':estado==='PENDIENTE'?'Pendiente de cobro':'Rechazado'));assert.equal(Boolean(button(f.render(),'Elegir otro método')),estado==='RECHAZADO')}finally{await f.close()}
})
for(const kind of ['history','detail'])test(`${kind}: lectura propia, navegación y ausencia de acciones ADMIN`,async()=>{
  const f=await fixture(kind);try{const html=renderToStaticMarkup(f.render());assert.equal(f.calls[0].path,kind==='history'?'/pedidos/me':'/pedidos/700');assert.match(html,/Pedido #700/);assert.doesNotMatch(html,/Pasar a|Aprobar|Cancelar pedido/);assert.equal(f.calls.some(x=>x.method==='POST'),false);
    if(kind==='history'){nodes(f.render()).find(n=>n.props?.onVerDetalle).props.onVerDetalle(700);assert.deepEqual(f.navigation,['/pedidos/700'])}
  }finally{await f.close()}
})
test('Historial vacío conserva acceso al catálogo sin escrituras',async()=>{
  const f=await fixture('history',{empty:true});try{const html=renderToStaticMarkup(f.render());assert.match(html,/Todavía no tienes pedidos/);assert.match(html,/Explorar restaurantes/);assert.deepEqual(f.calls.map(x=>x.method),['GET'])}finally{await f.close()}
})
for(const kind of ['history','detail'])test(`${kind}: 503 con foco y recuperación GET`,async()=>{
  const f=await fixture(kind,{getFailure:true});try{const alert=nodes(f.render()).find(x=>x.props?.role==='alert');assert.equal(alert.props.tabIndex,-1);assert.ok(f.focuses()>0);f.recover();await button(f.render(),kind==='history'?'Reintentar consulta':'Reintentar').props.onClick();assert.deepEqual(f.calls.map(x=>x.method),['GET','GET'])}finally{await f.close()}
})
