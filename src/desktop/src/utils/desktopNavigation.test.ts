import { describe, expect, it } from 'vitest'
import type { Notification } from '@web/types'
import { normalizeDesktopNavigationIntent, notificationNavigationIntent } from './desktopNavigation'

function notification(overrides: Partial<Notification> = {}): Notification {
  return {
    id: 91,
    userId: 7,
    type: 'schedule_reminder',
    title: '提醒',
    content: '内容',
    relatedType: null,
    relatedId: null,
    reminderId: null,
    localDate: '2026-08-17',
    targetRoute: '',
    dataRevision: null,
    isRead: false,
    createdAt: '2026-08-17T10:00:00Z',
    ...overrides,
  }
}

describe('desktop notification navigation', () => {
  it('opens fatigue survey notifications in the quick survey view', () => {
    expect(notificationNavigationIntent(notification({ type: 'fatigue_survey', targetRoute: '/fatigue/survey' }))).toEqual({
      kind: 'fatigue-survey',
      localDate: '2026-08-17',
      notificationId: 91,
      userId: 7,
    })
  })

  it('uses notification route metadata for regular notifications', () => {
    expect(notificationNavigationIntent(notification({ targetRoute: '/schedules/42' }))).toEqual({
      kind: 'route',
      targetRoute: '/schedules/42',
      notificationId: 91,
      userId: 7,
    })
  })

  it('falls back to related entities and then the notification center', () => {
    expect(notificationNavigationIntent(notification({ relatedType: 'team_task', relatedId: 33 })).targetRoute).toBe('/tasks/33')
    expect(notificationNavigationIntent(notification()).targetRoute).toBe('/notifications')
  })

  it('normalizes tray events and rejects malformed routes', () => {
    expect(normalizeDesktopNavigationIntent('quick-timeline')).toEqual({ kind: 'quick-timeline' })
    expect(normalizeDesktopNavigationIntent({ kind: 'route', targetRoute: '/fatigue/survey' })).toEqual({ kind: 'route', targetRoute: '/fatigue/survey' })
    expect(normalizeDesktopNavigationIntent({ kind: 'route', targetRoute: 'https://example.com' })).toBeNull()
    expect(normalizeDesktopNavigationIntent({ kind: 'unknown' })).toBeNull()
  })
})
