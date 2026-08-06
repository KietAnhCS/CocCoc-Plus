export function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

export function prettyUrl(url: string): string {
  try {
    const parsed = new URL(url)
    const segments = parsed.pathname.split('/').filter(Boolean).slice(0, 3)
    return [parsed.hostname.replace(/^www\./, ''), ...segments].join(' › ')
  } catch {
    return url
  }
}

function hash32(text: string): number {
  let hash = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return hash >>> 0
}

export function siteGradient(url: string): string {
  const hue = hash32(hostOf(url)) % 360
  return `linear-gradient(135deg, hsl(${hue} 72% 52%), hsl(${(hue + 38) % 360} 74% 44%))`
}

export function siteInitial(url: string): string {
  const letter = hostOf(url)
    .replace(/[^a-z0-9]/gi, '')
    .charAt(0)
  return (letter || '?').toUpperCase()
}
