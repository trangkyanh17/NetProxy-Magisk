import { test } from 'node:test'
import assert from 'node:assert/strict'
import { decodeCtlResult } from '../src/contract.ts'
import { createPoller } from '../src/polling.ts'

test('JSON 与进程退出状态必须同时成功，结构化失败保留', () => {
  const success = { schema: 1, ok: true, code: 'service.status', message: '服务状态', data: { state: 'ready' } }
  assert.deepEqual(decodeCtlResult({ out: JSON.stringify(success), err: '', code: 0 }), success)
  assert.equal(decodeCtlResult({ out: JSON.stringify(success), err: 'terminated', code: 1 }).ok, false)
  const failure = { ...success, ok: false, code: 'subscription.runtime_sync_failed', message: '运行时同步失败' }
  assert.deepEqual(decodeCtlResult({ out: JSON.stringify(failure), err: 'extra', code: 1 }), failure)
  assert.equal(decodeCtlResult({ out: '{"schema":2}', err: '', code: 0 }).code, 'transport.invalid_json')
  assert.equal(decodeCtlResult({ out: '', err: 'denied', code: 1 }).message, 'denied')
})

test('慢请求不重叠，隐藏时暂停，过期响应不可覆盖当前状态', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const pending = []
  const results = []
  const poller = createPoller(() => new Promise(resolve => pending.push(resolve)), value => results.push(value))
  const settle = async () => { for (let i = 0; i < 5; i++) await Promise.resolve() }
  poller.setActive(true)
  t.mock.timers.tick(30_000)
  assert.equal(pending.length, 1)
  poller.setActive(false)
  poller.setActive(true)
  assert.equal(pending.length, 1)
  pending.shift()('expired')
  await settle()
  assert.deepEqual(results, [])
  assert.equal(pending.length, 1)
  pending.shift()('ready')
  await settle()
  assert.deepEqual(results, ['ready'])
  t.mock.timers.tick(4999)
  assert.equal(pending.length, 0)
  t.mock.timers.tick(1)
  assert.equal(pending.length, 1)
  poller.refresh()
  poller.refresh()
  pending.shift()('before-command')
  await settle()
  assert.deepEqual(results, ['ready'])
  assert.equal(pending.length, 1)
  poller.setActive(false)
  pending.shift()('hidden')
  await settle()
  t.mock.timers.tick(30_000)
  assert.equal(pending.length, 0)
})


test('轮询在空闲后降频，交互后立即恢复活跃节奏', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let now = 0
  const pending = []
  const results = []
  const settle = async () => { for (let i = 0; i < 5; i++) await Promise.resolve() }
  const poller = createPoller(
    () => new Promise(resolve => pending.push(resolve)),
    value => results.push(value),
    { activeInterval: 5000, idleInterval: 20000, idleAfter: 30000, now: () => now },
  )

  poller.setActive(true)
  assert.equal(pending.length, 1)
  pending.shift()('initial')
  await settle()

  t.mock.timers.tick(4999)
  assert.equal(pending.length, 0)
  t.mock.timers.tick(1)
  assert.equal(pending.length, 1)

  now = 30000
  pending.shift()('active')
  await settle()
  t.mock.timers.tick(19999)
  assert.equal(pending.length, 0)
  t.mock.timers.tick(1)
  assert.equal(pending.length, 1)

  now = 50000
  pending.shift()('idle')
  await settle()
  now = 51000
  poller.markInteraction()
  assert.equal(pending.length, 0)
  t.mock.timers.tick(4999)
  assert.equal(pending.length, 0)
  t.mock.timers.tick(1)
  assert.equal(pending.length, 1)
  pending.shift()('interaction')
  await settle()
  assert.deepEqual(results, ['initial', 'active', 'idle', 'interaction'])
})

test('隐藏中的在途响应作废，重新可见只刷新一次', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const pending = []
  const results = []
  const settle = async () => { for (let i = 0; i < 5; i++) await Promise.resolve() }
  const poller = createPoller(() => new Promise(resolve => pending.push(resolve)), value => results.push(value))

  poller.setActive(true)
  assert.equal(pending.length, 1)
  poller.setActive(false)
  t.mock.timers.tick(60000)
  assert.equal(pending.length, 1)
  poller.setActive(true)
  assert.equal(pending.length, 1)
  pending.shift()('stale')
  await settle()
  assert.deepEqual(results, [])
  assert.equal(pending.length, 1)
  pending.shift()('fresh')
  await settle()
  assert.deepEqual(results, ['fresh'])
  poller.setActive(false)
  t.mock.timers.tick(60000)
  assert.equal(pending.length, 0)
})
