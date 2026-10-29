/**
 * 各家云翻译 API 的签名工具。
 *
 * 八家大厂，五种签名方式：
 *   百度        MD5(appid + q + salt + secret)          —— WebCrypto 没有 MD5，用纯 JS 实现
 *   腾讯云      TC3-HMAC-SHA256                          —— 三段派生密钥
 *   火山引擎    V4 签名（HMAC-SHA256，四段派生）
 *   阿里云      RPC 风格 HMAC-SHA1
 *   Google / DeepL / Azure / Yandex   直接 API Key（Yandex 是 Authorization: Api-Key）
 *   自定义 API   Bearer Token
 *
 * 全部在浏览器 / Node 22 里可跑：HMAC 走 WebCrypto（node 22 自带 crypto.subtle）。
 */

/* ── 编码工具 ─────────────────────────────────────── */

const encoder = new TextEncoder();

/** @param {string} s */
export function utf8(s) {
  return encoder.encode(s);
}

/** @param {Uint8Array} bytes */
export function toHex(bytes) {
  let out = '';
  for (const b of bytes) out += b.toString(16).padStart(2, '0');
  return out;
}

/** @param {Uint8Array} bytes */
export function toBase64(bytes) {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

/**
 * RFC3986 百分号编码：阿里云签名要求 * 不转义、~ 不转义、空格转 %20。
 * @param {string} s
 */
export function encodeRfc3986(s) {
  return encodeURIComponent(s)
    .replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase())
    .replace(/%7E/g, '~');
}

/**
 * HMAC。hash 取 'SHA-256' | 'SHA-1'。
 * @param {'SHA-256'|'SHA-1'} hash
 * @param {Uint8Array} key
 * @param {Uint8Array} message
 * @returns {Promise<Uint8Array>}
 */
export async function hmac(hash, key, message) {
  const cryptoKey = await crypto.subtle.importKey('raw', key, { name: 'HMAC', hash }, false, ['sign']);
  const sig = await crypto.subtle.sign('HMAC', cryptoKey, message);
  return new Uint8Array(sig);
}

/**
 * SHA-256 十六进制摘要。
 * @param {string} text
 */
export async function sha256Hex(text) {
  const digest = await crypto.subtle.digest('SHA-256', utf8(text));
  return toHex(new Uint8Array(digest));
}

/* ── MD5（百度签名专用） ────────────────────────────── */

const MD5_SHIFT = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
];

/** T[i] = floor(abs(sin(i+1)) * 2^32) */
const MD5_T = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) >>> 0);

/** @param {number} x @param {number} n */
function rotl(x, n) {
  return ((x << n) | (x >>> (32 - n))) >>> 0;
}

/**
 * MD5 摘要（纯 JS 实现，浏览器与 Node 通用）。
 * @param {string} input
 * @returns {string} 32 位小写十六进制
 */
export function md5(input) {
  const bytes = utf8(input);
  const bitLen = bytes.length * 8;
  const total = ((bytes.length + 8) >> 6) * 64 + 64;

  const buf = new Uint8Array(total);
  buf.set(bytes);
  buf[bytes.length] = 0x80;

  const view = new DataView(buf.buffer);
  view.setUint32(total - 8, bitLen >>> 0, true);
  view.setUint32(total - 4, Math.floor(bitLen / 4294967296), true);

  let a0 = 0x67452301;
  let b0 = 0xefcdab89;
  let c0 = 0x98badcfe;
  let d0 = 0x10325476;

  const M = new Uint32Array(16);

  for (let off = 0; off < total; off += 64) {
    for (let j = 0; j < 16; j++) M[j] = view.getUint32(off + j * 4, true);

    let A = a0;
    let B = b0;
    let C = c0;
    let D = d0;

    for (let i = 0; i < 64; i++) {
      let F;
      let g;
      if (i < 16) {
        F = (B & C) | (~B & D);
        g = i;
      } else if (i < 32) {
        F = (D & B) | (~D & C);
        g = (5 * i + 1) % 16;
      } else if (i < 48) {
        F = B ^ C ^ D;
        g = (3 * i + 5) % 16;
      } else {
        F = C ^ (B | ~D);
        g = (7 * i) % 16;
      }

      F = (F + A + MD5_T[i] + M[g]) >>> 0;
      A = D;
      D = C;
      C = B;
      B = (B + rotl(F, MD5_SHIFT[i])) >>> 0;
    }

    a0 = (a0 + A) >>> 0;
    b0 = (b0 + B) >>> 0;
    c0 = (c0 + C) >>> 0;
    d0 = (d0 + D) >>> 0;
  }

  const out = new DataView(new ArrayBuffer(16));
  out.setUint32(0, a0, true);
  out.setUint32(4, b0, true);
  out.setUint32(8, c0, true);
  out.setUint32(12, d0, true);
  return toHex(new Uint8Array(out.buffer));
}

/* ── 腾讯云 TC3-HMAC-SHA256 ────────────────────────── */

/**
 * 腾讯云 API v3 签名。
 * @param {Object} args
 * @param {string} args.secretId
 * @param {string} args.secretKey
 * @param {string} args.action      例如 TextTranslate
 * @param {string} args.version     例如 2018-03-21
 * @param {string} args.region      例如 ap-guangzhou
 * @param {string} args.host        例如 tmt.tencentcloudapi.com
 * @param {string} args.payload     JSON 请求体
 * @param {string} [args.service]    默认 tmt
 * @returns {Promise<Record<string,string>>} 请求头
 */
export async function tencentTC3({ secretId, secretKey, action, version, region, host, payload, service = 'tmt' }) {
  const timestamp = Math.floor(Date.now() / 1000);
  const date = new Date(timestamp * 1000).toISOString().slice(0, 10);
  const contentType = 'application/json; charset=utf-8';

  const canonicalHeaders = `content-type:${contentType}\nhost:${host}\nx-tc-action:${action.toLowerCase()}\n`;
  const signedHeaders = 'content-type;host;x-tc-action';
  const hashedPayload = await sha256Hex(payload);

  const canonicalRequest = ['POST', '/', '', canonicalHeaders, signedHeaders, hashedPayload].join('\n');
  const credentialScope = `${date}/${service}/tc3_request`;
  const stringToSign = ['TC3-HMAC-SHA256', timestamp, credentialScope, await sha256Hex(canonicalRequest)].join('\n');

  const secretDate = await hmac('SHA-256', utf8(secretKey), utf8(date));
  const secretService = await hmac('SHA-256', secretDate, utf8(service));
  const secretSigning = await hmac('SHA-256', secretService, utf8('tc3_request'));
  const signature = toHex(await hmac('SHA-256', secretSigning, utf8(stringToSign)));

  return {
    Authorization: `TC3-HMAC-SHA256 Credential=${secretId}/${credentialScope}, SignedHeaders=${signedHeaders}, Signature=${signature}`,
    'Content-Type': contentType,
    Host: host,
    'X-TC-Action': action,
    'X-TC-Version': version,
    'X-TC-Timestamp': String(timestamp),
    'X-TC-Region': region
  };
}

/* ── 火山引擎 V4 签名 ──────────────────────────────── */

/**
 * 火山引擎 V4 签名。
 * @param {Object} args
 * @param {string} args.accessKeyId
 * @param {string} args.secretKey
 * @param {string} args.region      例如 cn-north-1
 * @param {string} args.service     例如 translate
 * @param {string} args.host        例如 open.volcengineapi.com
 * @param {string} args.body        JSON 请求体
 * @param {Record<string,string>} args.query 查询参数
 * @returns {Promise<Record<string,string>>} 请求头
 */
export async function volcV4({ accessKeyId, secretKey, region, service, host, body, query }) {
  const xDate = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  const date = xDate.slice(0, 8);
  const payloadHash = await sha256Hex(body);

  const canonicalHeaders = [
    `content-type:application/json; charset=utf-8`,
    `host:${host}`,
    `x-content-sha256:${payloadHash}`,
    `x-date:${xDate}`
  ].join('\n') + '\n';

  const signedHeaders = 'content-type;host;x-content-sha256;x-date';

  const canonicalQuery = Object.keys(query)
    .sort()
    .map((k) => `${encodeRfc3986(k)}=${encodeRfc3986(query[k])}`)
    .join('&');

  const canonicalRequest = ['POST', '/', canonicalQuery, canonicalHeaders, signedHeaders, payloadHash].join('\n');
  const credentialScope = `${date}/${region}/${service}/request`;
  const stringToSign = ['HMAC-SHA256', xDate, credentialScope, await sha256Hex(canonicalRequest)].join('\n');

  const kDate = await hmac('SHA-256', utf8(secretKey), utf8(date));
  const kRegion = await hmac('SHA-256', kDate, utf8(region));
  const kService = await hmac('SHA-256', kRegion, utf8(service));
  const kSigning = await hmac('SHA-256', kService, utf8('request'));
  const signature = toHex(await hmac('SHA-256', kSigning, utf8(stringToSign)));

  return {
    Authorization: `HMAC-SHA256 Credential=${accessKeyId}/${credentialScope}, SignedHeaders=${signedHeaders}, Signature=${signature}`,
    'Content-Type': 'application/json; charset=utf-8',
    Host: host,
    'X-Date': xDate,
    'X-Content-Sha256': payloadHash
  };
}

/* ── 阿里云 RPC 风格（HMAC-SHA1） ──────────────────── */

/**
 * 阿里云 RPC 风格签名。
 * @param {Object} args
 * @param {string} args.accessKeyId
 * @param {string} args.accessKeySecret
 * @param {Record<string,string>} args.params 业务参数
 * @param {string} [args.version]
 * @returns {Promise<Record<string,string>>} 可直接作为表单体提交
 */
export async function aliyunRpc({ accessKeyId, accessKeySecret, params, version = '2018-10-12' }) {
  const all = {
    Format: 'JSON',
    Version: version,
    AccessKeyId: accessKeyId,
    SignatureMethod: 'HMAC-SHA1',
    SignatureNonce: `${Date.now()}${Math.floor(Math.random() * 1000000)}`,
    SignatureVersion: '1.0',
    Timestamp: new Date().toISOString().replace(/\.\d+Z$/, 'Z'),
    ...params
  };

  const canonical = Object.keys(all)
    .sort()
    .map((k) => `${encodeRfc3986(k)}=${encodeRfc3986(String(all[k]))}`)
    .join('&');

  const stringToSign = `POST&${encodeRfc3986('/')}&${encodeRfc3986(canonical)}`;
  const signature = toBase64(await hmac('SHA-1', utf8(`${accessKeySecret}&`), utf8(stringToSign)));

  return { ...all, Signature: signature };
}
