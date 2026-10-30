import { http } from './http'
import type { DirectoryEntry, Role, User } from './types'

export const authApi = {
  me: () => http.get<User>('/auth/me').then((r) => r.data),
  directory: () => http.get<DirectoryEntry[]>('/auth/users/directory').then((r) => r.data),
  users: () => http.get<User[]>('/auth/users').then((r) => r.data),
  createUser: (body: { email: string; password: string; fullName: string; role: Role }) =>
    http.post<User>('/auth/users', body).then((r) => r.data),
}
