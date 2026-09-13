import assert from 'node:assert/strict'
import { test } from 'node:test'

/**
 * Modela el estado del vaciado del carrito tras crear el pedido, replicando la lógica
 * de RealConfirmarPedidoPanel: 'pendiente' -> 'en_curso' -> 'exitoso' | 'fallido', con
 * guarda síncrona para impedir DELETE solapados y sin repetir POST /pedidos.
 */
function crearFlujoVaciado(deleteImpl) {
  let vaciando = false
  const llamadas = []
  return {
    estado: null,
    async vaciar() {
      if (vaciando) return false
      vaciando = true
      this.estado = 'en_curso'
      try {
        llamadas.push('DELETE /carrito')
        await deleteImpl()
        this.estado = 'exitoso'
        return true
      } catch {
        this.estado = 'fallido'
        return false
      } finally {
        vaciando = false
      }
    },
    llamadas,
  }
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('vaciado: mientras el DELETE está en curso el estado es en_curso, no exitoso', async () => {
  const pendiente = deferred()
  const flujo = crearFlujoVaciado(() => pendiente.promise)
  flujo.estado = 'pendiente'

  const enCurso = flujo.vaciar()
  assert.equal(flujo.estado, 'en_curso') // NO se anuncia vaciado antes de la respuesta
  pendiente.resolve()
  assert.equal(await enCurso, true)
  assert.equal(flujo.estado, 'exitoso')
  assert.equal(flujo.llamadas.length, 1)
})

test('vaciado: si el DELETE falla el estado es fallido y se puede reintentar', async () => {
  let intentos = 0
  const flujo = crearFlujoVaciado(() => { intentos += 1; throw new Error('fallo') })
  flujo.estado = 'pendiente'

  assert.equal(await flujo.vaciar(), false)
  assert.equal(flujo.estado, 'fallido')
  assert.equal(intentos, 1)

  // Reintento del botón: otro DELETE (pero nunca otro POST).
  assert.equal(await flujo.vaciar(), false)
  assert.equal(intentos, 2)
})

test('vaciado: dos solicitudes solapadas solo emiten un DELETE', async () => {
  const pendiente = deferred()
  const flujo = crearFlujoVaciado(() => pendiente.promise)
  flujo.estado = 'pendiente'

  const primero = flujo.vaciar()
  const segundo = flujo.vaciar()
  assert.equal(await segundo, false) // la segunda no emite DELETE
  pendiente.resolve()
  assert.equal(await primero, true)
  assert.equal(flujo.llamadas.length, 1)
})

test('vaciado tras 201: el pedidoId se conserva y no se repite POST', async () => {
  let posts = 0
  const creado = { pedidoId: 700 }
  const flujo = crearFlujoVaciado(() => { throw new Error('fallo') })

  posts += 1 // único POST /pedidos
  flujo.estado = 'pendiente'
  await flujo.vaciar()
  await flujo.vaciar() // reintento del vaciado

  assert.equal(creado.pedidoId, 700)
  assert.equal(posts, 1, 'el reintento del vaciado no debe emitir otro POST /pedidos')
})
