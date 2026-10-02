import { onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAdminStore } from '../stores/admin'
import { queryPage } from '../utils/analytics'

const filterKeys = ['keyword', 'status', 'dateFrom', 'dateTo', 'sort', 'page', 'size']

export function useResourceList(resource) {
  const store = useAdminStore()
  const route = useRoute()
  const router = useRouter()
  const path = route.path
  store.activeResource = resource
  let stop
  function load() {
    if (route.path !== path) return
    store.activeResource = resource
    store.searchKeyword = String(route.query.keyword || '')
    store.filterStatus = String(route.query.status || '')
    store.filterDateFrom = String(route.query.dateFrom || '')
    store.filterDateTo = String(route.query.dateTo || '')
    const [key, order] = String(route.query.sort || 'createdAt,desc').split(',')
    store.sortKey = key
    store.sortOrder = order === 'asc' ? 'asc' : 'desc'
    return store.fetchList({ page: queryPage(route.query.page), size: [10, 20, 50, 100].includes(Number(route.query.size)) ? Number(route.query.size) : 20 })
  }
  function navigate(values) {
    const query = { ...route.query }
    for (const [key, value] of Object.entries(values)) {
      if (value == null || value === '') delete query[key]
      else query[key] = String(value)
    }
    if (filterKeys.every(key => query[key] === route.query[key])) return load()
    return router.push({ query })
  }
  onMounted(() => {
    stop = watch(() => [route.path, ...filterKeys.map(key => route.query[key])], load, { immediate: true })
  })
  onBeforeUnmount(() => stop?.())
  return {
    onSearch: params => navigate({ ...params, page: 1 }),
    onReset: () => navigate(Object.fromEntries(filterKeys.map(key => [key, null]))),
    onPageChange: page => navigate({ page }),
    onSizeChange: size => navigate({ size, page: 1 }),
    onSortChange: sort => navigate({ sort, page: 1 }),
    refresh: load,
  }
}
