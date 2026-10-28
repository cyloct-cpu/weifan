#!/usr/bin/env node
/**
 * 多浏览器打包：node scripts/build.mjs
 *
 * 一份源码 → 三个可用产物：
 *   dist/chrome   Chrome / Edge / Brave / Opera / Vivaldi（Chromium 系，MV3 service worker）
 *   dist/firefox  Firefox 121+（MV3 事件页 background.scripts，需 gecko id）
 *   dist/safari   Safari 16.4+（结构与 Chrome 相同，但必须用 Xcode 包一层 App 才能装）
 *
 * 为什么需要这一步（Firefox 不能用同一份 manifest）：
 * - Chrome 只认 background.service_worker；
 * - Firefox 的 MV3 后台是**事件页**，用 background.scripts，且必须有 browser_specific_settings.gecko.id；
 * - Safari 要求 xcrun safari-web-extension-converter 把扩展塞进一个 macOS/iOS App。
 *
 * 零依赖，只用 node 内置模块。
 */

import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const DIST = join(ROOT, 'dist');

const GECKO_ID = 'weifan@weifan.local';

/** @param {{background: any}} manifest */
function toFirefox(manifest) {
  const m = structuredClone(manifest);
  m.background = {
    scripts: [manifest.background.service_worker],
    type: manifest.background.type
  };
  m.browser_specific_settings = {
    gecko: {
      id: GECKO_ID,
      strict_min_version: '121.0'
    }
  };
  return m;
}

/** Safari 的 manifest 与 Chrome 同构，差异在打包方式 */
function toSafari(manifest) {
  return structuredClone(manifest);
}

const TARGETS = [
  {
    name: 'chrome',
    label: 'Chrome / Edge / Brave / Opera / Vivaldi',
    transform: (m) => structuredClone(m)
  },
  {
    name: 'firefox',
    label: 'Firefox 121+',
    transform: toFirefox
  },
  {
    name: 'safari',
    label: 'Safari 16.4+（需 Xcode 包装）',
    transform: toSafari
  }
];

async function main() {
  const base = JSON.parse(await readFile(join(ROOT, 'manifest.base.json'), 'utf8'));
  await rm(DIST, { recursive: true, force: true });

  for (const target of TARGETS) {
    const outDir = join(DIST, target.name);
    await mkdir(outDir, { recursive: true });
    await cp(join(ROOT, 'src'), join(outDir, 'src'), { recursive: true });

    const manifest = target.transform(base);
    await writeFile(join(outDir, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n', 'utf8');

    if (target.name === 'safari') {
      await writeFile(join(outDir, 'SAFARI-README.txt'), SAFARI_NOTE, 'utf8');
    }
    console.log(`✓ ${target.name.padEnd(8)} ${target.label}`);
  }

  console.log(`\n产物目录：${DIST}`);
  console.log('Chrome 版加载方式：chrome://extensions → 开发者模式 → 加载已解压 → 选 dist/chrome');
}

const SAFARI_NOTE = `Safari 版需要两步（第二步必须有 Xcode）：

1) 生成本目录（已完成）
   node scripts/build.mjs

2) 用 Xcode 把扩展包进一个 App：
   xcrun safari-web-extension-converter ./dist/safari \\
     --project-location ./safari-project \\
     --app-name "微翻" \\
     --bundle-identifier com.weifan.safari

   然后在 Xcode 里选你的 Team，Run 一次（会要求开启 Safari 的「允许未签名的扩展」
   或到 Safari 设置 → 扩展 里勾选）。分发还需要 Apple Developer 账号。

为什么不能像 Chrome 那样直接加载：
Safari 不接受"未打包的扩展目录"，扩展必须作为 App 的 embedded extension 存在。
`;

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
