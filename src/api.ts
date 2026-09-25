export type User = { id: number; username: string; email: string; role: string; status: string; points: number; reputation: number; message_privacy: string; notify_unfollow: boolean; notify_mention: boolean; school: string; grade: string; major: string; subject_preference: string; public_school: boolean; public_grade: boolean }
export type Board = { id: number; name: string; slug: string; description: string }
export type Tag = { id: number; name: string }
export type Post = { id: number; board_id: number; author_id: number; username: string; board_name: string; title: string; content: string; type: string; image_path?: string; cover_path?: string; attachment_path?: string; is_solved: boolean; is_featured: boolean; is_top: boolean; view_count: number; reply_count: number; favorite_count: number; created_at: string; tags?: Tag[]; favorited?: boolean; status?: string }
export type Reply = { id: number; post_id: number; author_id: number; username: string; content: string; image_path?: string; quote_content?: string; is_accepted: boolean; created_at: string }
let csrf = ''
export async function api<T = any>(path: string, options: RequestInit = {}): Promise<T> {
  if (!csrf || options.method && options.method !== 'GET') { const response = await fetch('/api/csrf', { credentials: 'same-origin' }); if (response.ok) csrf = (await response.json()).token }
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  if (options.method && options.method !== 'GET') headers.set('X-XSRF-TOKEN', csrf)
  const response = await fetch('/api' + path, { ...options, headers, credentials: 'same-origin' })
  if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.message || `请求失败 (${response.status})`) }
  return response.status === 204 || response.headers.get('content-length') === '0' ? undefined as T : response.json()
}
export const send = <T = any>(path: string, method: string, value?: object) => api<T>(path, { method, body: value ? JSON.stringify(value) : undefined })
export async function upload(file: File) { const form = new FormData(); form.append('file', file); return api<{ path: string }>('/uploads', { method: 'POST', body: form }) }
export function level(points: number) { return points >= 1000 ? '学霸' : points >= 500 ? '达人' : points >= 200 ? '学长' : points >= 50 ? '学友' : '新生' }
export function date(value: string) { return new Date(value).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' }) }
