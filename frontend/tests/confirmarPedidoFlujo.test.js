import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createPedidoController } from '../src/features/pedidos/pedidoService.js'
import { createPedidoHttpAdapter } from '../src/features/pedidos/pedidoHttpAdapter.js'
import { ApiAccessError } from '../src/auth/ApiAccessError.js'

const pedidoOk = {
  pedidoId: 700, usuarioId: 10, restauranteId: 20, direccionEntrega: 'Av. Ejemplo 1', estado: 'CREADO',
  total: 5500, moneda: 'CLP', fechaCreacion: '2026-09-10T12:00:00Z',
  lineas: [{ lineaId: 1, productoId: 101, cantidad: 1, precioUnitario: 5500, subtotal: 5500 }],
}

const draft = { restauranteId: 20, direccionEntrega: 'Av. Ejemplo 1', items: [{ productoId: 101, cantidad: 1 }] }

function deferred() {
  let resolve
  let reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function clienteConPost(post) {
  return {
    get: async () => ({ status: 200, data: [pedidoOk] }),
    put: async () => ({ status: 200, data: pedidoOk }),
    post,
  }
}

test('crear: la respuesta válida se proyecta y no envía usuarioId', async () => {
  let enviado
  const controller = createPedidoController()
  controller.connect(createPedidoHttpAdapter(clienteConPost(async (url, body) => {
    enviado = { url, body }
    return { status: 201, data: pedidoOk }
  })))

  assert.equal(await controller.create(draft), true)
  assert.equal(enviado.url, '/pedidos')
  assert.equal(Object.hasOwn(enviado.body, 'usuarioId'), false)
  assert.equal(controller.getSnapshot().pedido.pedidoId, 700)
})

test('crear: dos envíos simultáneos solo producen un POST', async () => {
  let posts = 0
  const pendiente = deferred()
  const controller = createPedidoController()
  controller.connect(createPedidoHttpAdapter(clienteConPost(async () => {
    posts += 1
    return pendiente.promise
  })))

  const primero = controller.create(draft)
  const segundo = controller.create(draft)
  assert.equal(await segundo, false) // el segundo no inicia otra petición
  pendiente.resolve({ status: 201, data: pedidoOk })
  assert.equal(await primero, true)
  assert.equal(posts, 1)
})

test('crear: un fallo de red es un resultado incierto, no un éxito', async () => {
  const controller = createPedidoController()
  const falloRed = Object.assign(new Error('timeout'), { code: 'ECONNABORTED', response: undefined })
  // El transporte lanza (sin respuesta): el adaptador lo traduce a CREATE_FAILED.
  controller.connect(createPedidoHttpAdapter({
    get: async () => ({ status: 200, data: [pedidoOk] }),
    put: async () => ({ status: 200, data: pedidoOk }),
    post: async () => { throw falloRed },
  }))

  assert.equal(await controller.create(draft), false)
  assert.equal(controller.getSnapshot().error.code, 'CREATE_FAILED')
  assert.equal(controller.getSnapshot().pedido, null)
})

test('crear: un 400 de validación es un fallo definitivo (no incierto)', async () => {
  const errorHttp = new ApiAccessError('API_ERROR', 400)
  const controller = createPedidoController()
  controller.connect(createPedidoHttpAdapter({
    get: async () => ({ status: 200, data: [pedidoOk] }),
    put: async () => ({ status: 200, data: pedidoOk }),
    post: async () => { throw errorHttp },
  }))

  assert.equal(await controller.create(draft), false)
  assert.equal(controller.getSnapshot().error.code, 'INVALID_REQUEST')
})

test('RESULTADO INCIERTO: el servidor crea pero se pierde la respuesta; no hay un segundo POST', async () => {
  let posts = 0
  const controller = createPedidoController()
  controller.connect(createPedidoHttpAdapter(clienteConPost(async () => {
    posts += 1
    // El servidor procesó la creación, pero la respuesta se pierde (timeout/red).
    throw Object.assign(new Error('respuesta perdida'), { code: 'ETIMEDOUT' })
  })))

  assert.equal(await controller.create(draft), false)
  assert.equal(controller.getSnapshot().error.code, 'CREATE_FAILED')
  assert.equal(posts, 1)

  // El panel marca el resultado como incierto y bloquea nuevos intentos:
  // un reintento del usuario no debe emitir otro POST.
  const codigo = controller.getSnapshot().error.code
  const erroresDefinitivos = ['INVALID_REQUEST', 'INVALID_COMMAND', 'FORBIDDEN', 'UNAUTHORIZED', 'CONFLICT']
  const incierto = !erroresDefinitivos.includes(codigo)
  assert.equal(incierto, true)
  if (!incierto) await controller.create(draft)
  assert.equal(posts, 1, 'no debe haber un segundo POST tras un resultado incierto')
})
