/**
 * 主题判定：三档 —— 昼 / 夜 / 跟随系统（跟随系统时可另设夜间时段）。
 *
 * 为什么不用纯 CSS 的 prefers-color-scheme：手动指定夜间时，它会跟系统打架。
 * 所以改成 **JS 算出最终是昼是夜，写到 <html data-theme="dark"> 上**，CSS 只认这个属性。
 *
 * Android 端同规格的实现见 android/.../core/Theme.kt。
 */

/** 三档模式 */
export const THEME_MODES = [
  { value: 'light', label: '昼' },
  { value: 'dark', label: '夜' },
  { value: 'auto', label: '跟随系统' }
];

/** 顶栏昼夜图标的循环顺序与文案（昼 → 夜 → 跟随系统 → 昼） */
export const THEME_ORDER = ['light', 'dark', 'auto'];
export const THEME_LABELS = { light: '昼', dark: '夜', auto: '跟随系统' };

/**
 * 循环到下一档；不认识的值（含空）当作『跟随系统』的下一档 —— 夜。
 * @param {string} mode
 */
export function nextThemeMode(mode) {
  const index = THEME_ORDER.indexOf(mode);
  if (index < 0) return 'dark';
  return THEME_ORDER[(index + 1) % THEME_ORDER.length];
}

export const DEFAULT_SCHEDULE = { enabled: false, start: '22:00', end: '07:00' };

/** localStorage 里的镜像键（供首屏内联脚本同步读取，避免闪白） */
const MIRROR_KEY = 'weifan.theme';

/**
 * "22:30" → 分钟数；不合法返回 null
 * @param {string} hhmm
 */
export function toMinutes(hhmm) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(hhmm ?? '').trim());
  if (!m) return null;
  const hours = Number(m[1]);
  const minutes = Number(m[2]);
  if (!Number.isFinite(hours) || !Number.isFinite(minutes)) return null;
  if (hours > 23 || minutes > 59) return null;
  return hours * 60 + minutes;
}

/**
 * 最终是昼还是夜。
 *
 * @param {'light'|'dark'|'auto'} mode
 * @param {{enabled?: boolean, start?: string, end?: string}} [schedule] 自定义夜间时段
 * @param {boolean} systemDark 系统是不是暗色
 * @param {Date} [now]
 * @returns {'light'|'dark'}
 */
export function effectiveTheme(mode, schedule, systemDark, now = new Date()) {
  if (mode === 'light') return 'light';
  if (mode === 'dark') return 'dark';

  // 跟随系统：设了自定义时段就按时段来（跨零点也认），否则跟系统
  if (schedule?.enabled) {
    const start = toMinutes(schedule.start ?? DEFAULT_SCHEDULE.start);
    const end = toMinutes(schedule.end ?? DEFAULT_SCHEDULE.end);
    if (start !== null && end !== null) {
      const current = now.getHours() * 60 + now.getMinutes();
      const inWindow = start <= end
        ? current >= start && current < end
        : current >= start || current < end;
      return inWindow ? 'dark' : 'light';
    }
  }
  return systemDark ? 'dark' : 'light';
}

/**
 * 应用主题：写到 <html data-theme>，并镜像到 localStorage
 * （内联脚本在首屏同步读它，做到切换无闪烁）。
 * @param {'light'|'dark'} theme
 */
export function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(MIRROR_KEY, theme);
  } catch {
    // 无痕模式可能不让写，忽略
  }
}

/** 系统当前是不是暗色 */
export function systemPrefersDark() {
  return globalThis.matchMedia?.('(prefers-color-scheme: dark)')?.matches ?? false;
}

/**
 * 首屏同步上色：只读 localStorage 里的镜像。
 * 各页面在 <head> 里放一段内联脚本调它 —— 模块脚本是延迟执行的，
 * 等它跑到时页面已经画过一次了，会有闪白。
 */
export function paintFromMirror() {
  try {
    const cached = localStorage.getItem(MIRROR_KEY);
    if (cached === 'light' || cached === 'dark') {
      document.documentElement.dataset.theme = cached;
    }
  } catch {
    // 读不到就先用浅色
  }
}

/**
 * 从设置里解析出主题并应用。
 * @param {{themeMode?: string, themeSchedule?: object}} settings
 * @returns {'light'|'dark'}
 */
export function applyFromSettings(settings) {
  const theme = effectiveTheme(
    settings?.themeMode ?? 'auto',
    settings?.themeSchedule,
    systemPrefersDark()
  );
  applyTheme(theme);
  return theme;
}

/**
 * 按设置应用主题，并在需要时自动重算：
 * - 系统主题变了（auto 模式）
 * - 自定义时段跨过边界（每分钟检查）
 *
 * @param {{themeMode?: string, themeSchedule?: object}} settings
 * @returns {() => void} 取消监听
 */
export function watchTheme(settings) {
  const media = globalThis.matchMedia?.('(prefers-color-scheme: dark)');
  let systemDark = media?.matches ?? false;

  const update = () => {
    applyTheme(effectiveTheme(settings?.themeMode ?? 'auto', settings?.themeSchedule, systemDark));
  };
  update();

  media?.addEventListener?.('change', (event) => {
    systemDark = event.matches;
    update();
  });

  // 自定义时段跨过边界时自己切（每分钟看一眼）
  const timer = setInterval(update, 60_000);
  return () => clearInterval(timer);
}
