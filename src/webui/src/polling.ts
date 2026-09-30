export interface PollerOptions {
  activeInterval?: number
  idleInterval?: number
  idleAfter?: number
  now?: () => number
}

export function createPoller<T>(
  request: () => Promise<T>,
  publish: (value: T) => void,
  intervalOrOptions: number | PollerOptions = {},
) {
  const options = typeof intervalOrOptions === 'number'
    ? { activeInterval: intervalOrOptions }
    : intervalOrOptions
  const activeInterval = options.activeInterval ?? 5000
  const idleInterval = options.idleInterval ?? 20000
  const idleAfter = options.idleAfter ?? 30000
  const now = options.now ?? Date.now
  let active = false
  let inFlight = false
  let requested = false
  let revision = 0
  let lastInteraction = now()
  let timer: ReturnType<typeof setTimeout> | undefined

  function nextInterval() {
    return Math.max(0, now() - lastInteraction) >= idleAfter ? idleInterval : activeInterval
  }

  function refresh() {
    revision++
    clearTimeout(timer)
    if (!active) return
    if (inFlight) { requested = true; return }
    inFlight = true
    requested = false
    const current = revision
    void request().then(value => {
      if (active && current === revision) publish(value)
    }).catch(() => {
      // 一次读取失败不覆盖已有状态，也不终止后续轮询。
    }).finally(() => {
      inFlight = false
      if (!active) return
      if (requested) refresh()
      else timer = setTimeout(refresh, nextInterval())
    })
  }

  function markInteraction() {
    lastInteraction = now()
    clearTimeout(timer)
    if (!active || inFlight) return
    timer = setTimeout(refresh, activeInterval)
  }

  return {
    refresh,
    markInteraction,
    setActive(value: boolean) {
      if (active === value) return
      active = value
      revision++
      clearTimeout(timer)
      if (active) {
        lastInteraction = now()
        refresh()
      }
    },
  }
}
