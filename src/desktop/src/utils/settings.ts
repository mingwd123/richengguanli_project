export function clampOpacity(value: number) {
  if (!Number.isFinite(value)) return 96
  return Math.min(100, Math.max(78, Math.round(value)))
}
