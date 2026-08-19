import type { Notification } from '@web/types'

export type DesktopNavigationIntent =
  | { kind: 'workspace'; targetRoute?: string }
  | { kind: 'quick-timeline' }
  | { kind: 'fatigue-survey'; localDate?: string; notificationId?: number }
  | { kind: 'route'; targetRoute: string; notificationId?: number }
  | { kind: 'refresh' }

export type StoredNotificationNavigation = DesktopNavigationIntent & { userId?: number }

export function notificationNavigationIntent(item: Notification): StoredNotificationNavigation {
  if (item.type === 'fatigue_survey' || item.targetRoute?.startsWith('/fatigue/survey')) {
    return {
      kind: 'fatigue-survey',
      localDate: item.localDate || undefined,
      notificationId: item.id,
      userId: item.userId,
    }
  }

  const targetRoute = item.targetRoute
    || (item.relatedType === 'schedule' && item.relatedId ? `/schedules/${item.relatedId}` : '')
    || (item.relatedType === 'team_task' && item.relatedId ? `/tasks/${item.relatedId}` : '')
    || '/notifications'
  return { kind: 'route', targetRoute, notificationId: item.id, userId: item.userId }
}

export function normalizeDesktopNavigationIntent(payload: unknown): DesktopNavigationIntent | null {
  if (payload === 'workspace') return { kind: 'workspace' }
  if (payload === 'quick-timeline') return { kind: 'quick-timeline' }
  if (payload === 'fatigue-survey') return { kind: 'fatigue-survey' }
  if (!payload || typeof payload !== 'object' || !('kind' in payload)) return null
  const intent = payload as DesktopNavigationIntent
  if (intent.kind === 'workspace' || intent.kind === 'quick-timeline' || intent.kind === 'fatigue-survey' || intent.kind === 'refresh') return intent
  if (intent.kind === 'route' && typeof intent.targetRoute === 'string' && intent.targetRoute.startsWith('/')) return intent
  return null
}
