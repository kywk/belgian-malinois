import { vi, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import App from '../App.vue'
import DocumentDetail from './DocumentDetail.vue'
import http from '../services/http.js'

vi.mock('../services/http', () => ({ default: { get: vi.fn(), put: vi.fn() } }))
vi.mock('../stores/auth', () => ({ useAuthStore: () => ({
  userId: 'reviewer', isAuthenticated: true, canActTasks: true, hasPermission: () => false,
}) }))

it('切換待辦 URL 會卸載前案附件，核決送往新 task', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/tasks/:taskId', component: DocumentDetail },
    { path: '/tasks', component: { template: '<div>待辦</div>' } },
  ] })
  const blob = new Blob(['photo'], { type: 'image/png' })
  const revoke = vi.fn()
  vi.stubGlobal('URL', class extends URL {
    static createObjectURL() { return 'blob:first-photo' }
    static revokeObjectURL = revoke
  })
  const tasks = ['A', 'B'].map(id => ({
    taskId: id, taskName: '任務' + id, assignee: 'reviewer', formKey: 'review',
    businessKey: 'recruitment/app/sub-' + id + '/review', reviewEvidenceAvailable: true,
  }))
  http.get.mockImplementation(async path => {
    if (path === '/api/tasks') return { data: tasks }
    if (path.startsWith('/api/countersign/')) return { data: [] }
    if (path.endsWith('/form')) return { data: { mode: 'view', schemaRef: { key: 'review', version: 1 }, data: {} } }
    if (path.endsWith('/documents/doc-A')) return { data: blob }
    const id = path.split('/')[3]
    return { data: { applicationId: 'app', submissionId: 'sub-' + id, revision: 1, documents: [{
      documentId: 'doc-' + id, fileName: id + '.png', version: '1', sizeBytes: blob.size, contentType: blob.type, source: 'MOCK',
    }] } }
  })
  http.put.mockResolvedValue({ data: { status: 'ok' } })
  await router.push('/tasks/A')
  const view = mount(App, { global: { plugins: [ElementPlus, router], stubs: {
    UfRenderer: true, ApprovalTimeline: true, CommentPanel: true, CountersignDialog: true, OrgSelector: true,
    ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot/><slot name="footer"/></div>' },
  } } })
  const button = text => view.findAll('button').find(el => el.text() === text)
  try {
    await flushPromises()
    await button('核閱附件').trigger('click')
    await flushPromises()
    expect(view.get('img').attributes('alt')).toBe('A.png')
    await router.push('/tasks/B')
    await flushPromises()
    expect(view.text()).toContain('B.png')
    expect(view.text()).not.toContain('A.png')
    expect(revoke).toHaveBeenCalledWith('blob:first-photo')
    await button('同意').trigger('click')
    await button('確認').trigger('click')
    await flushPromises()
    expect(http.put).toHaveBeenCalledOnce()
    expect(http.put).toHaveBeenCalledWith('/api/tasks/B', { action: 'complete', variables: [{ name: 'approved', value: true }] })
    expect(router.currentRoute.value.path).toBe('/tasks')
  } finally {
    view.unmount()
    vi.unstubAllGlobals()
  }
})
