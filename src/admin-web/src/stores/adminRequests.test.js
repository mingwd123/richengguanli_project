import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { useAdminStore } from './admin'

const response = data => ({ json: async () => ({ code: 0, data }) })
const page = (id, summary = {}) => ({ list: [{ id }], total: 200, page: 1, size: 20, summary })
describe('admin request isolation', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', { getItem: () => null })
    vi.stubGlobal('document', { documentElement: { dataset: {} } })
    setActivePinia(createPinia())
  })
  afterEach(() => { vi.unstubAllGlobals() })
  it('ignores stale responses even when transport ignores AbortSignal', async () => {
    const pending = []
    vi.stubGlobal('fetch', vi.fn(() => new Promise(resolve => pending.push(resolve))))
    const store = useAdminStore()
    store.token = 'test'
    const first = store.fetchList({ keyword: 'old' })
    const second = store.fetchList({ keyword: 'new' })
    pending[1](response(page(2)))
    await second
    pending[0](response(page(1)))
    await first
    expect(store.page.list).toEqual([{ id: 2 }])
    expect(store.loading).toBe(false)
  })
  it('invalidates requests when navigating away from resource views', async () => {
    let finish
    vi.stubGlobal('fetch', () => new Promise(resolve => { finish = resolve }))
    const store = useAdminStore()
    store.token = 'test'
    const request = store.fetchList()
    store.invalidateRequests()
    finish(response(page(1)))
    await request
    expect(store.page.list).toEqual([])
  })
  it('allows clearing a search and uses complete server summaries', async () => {
    const fetch = vi.fn().mockResolvedValue(response(page(1, { active: 180, pending: 20 })))
    vi.stubGlobal('fetch', fetch)
    const store = useAdminStore()
    store.token = 'test'
    store.searchKeyword = 'previous'
    await store.fetchList({ keyword: '' })
    expect(fetch.mock.calls[0][0]).not.toContain('keyword')
    expect(store.stats).toEqual({ total: 200, active: 180, pending: 20 })
  })
  it('prevents an old detail from overwriting a newer selection', async () => {
    const pending = []
    vi.stubGlobal('fetch', () => new Promise(resolve => pending.push(resolve)))
    const store = useAdminStore()
    const first = store.fetchUserDetail(1)
    const second = store.fetchTeamDetail(2)
    pending[1](response({ id: 2 }))
    await second
    pending[0](response({ id: 1 }))
    await first
    expect(store.currentDetail).toEqual({ id: 2 })
  })
})
