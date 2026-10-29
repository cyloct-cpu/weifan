#!/usr/bin/env node
/**
 * 语法检查：node scripts/check.mjs
 *
 * 把 src/ 下所有 .js 过一遍 `node --check`。
 * 单独成文件是为了让 CI 不用装依赖（npm ci）也能跑。
 */

import { readdir, stat } from 'node:fs/promises';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const run = promisify(execFile);
const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));

/** @param {string} dir @returns {Promise<string[]>} */
async function collect(dir) {
  /** @type {string[]} */
  const files = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      // vendor 是第三方产物，不检查
      if (entry.name === 'vendor') continue;
      files.push(...(await collect(full)));
    } else if (entry.name.endsWith('.js') || entry.name.endsWith('.mjs')) {
      files.push(full);
    }
  }
  return files;
}

const targets = [
  ...(await collect(join(ROOT, 'src'))),
  join(ROOT, 'scripts', 'build.mjs'),
  join(ROOT, 'tests', 'selftest.mjs')
];

let failed = 0;
for (const file of targets) {
  try {
    await run(process.execPath, ['--check', file]);
  } catch (error) {
    failed += 1;
    console.error(`FAIL ${relative(ROOT, file)}\n${error.stderr || error.message}`);
  }
}

console.log(`${targets.length - failed}/${targets.length} 个文件语法正确`);
process.exit(failed === 0 ? 0 : 1);
