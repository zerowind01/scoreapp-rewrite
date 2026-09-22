/**
 * netdisk-demo.html 的业务逻辑回归测试。
 *
 * 为什么单独一个文件：网盘浏览页是这一版功能的**权威规格**，
 * 它一旦跑偏，Compose 侧照着抄也跟着错（stepper-test.js 就是为了同一个理由才拆出来的）。
 *
 * 做法与 regression-test.js / stepper-test.js 同一套路：
 * 把 <script> 抽出来丢进最小 DOM 桩里求值，再把纯业务函数取出来单独打。
 */
const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "netdisk-demo.html"), "utf8");
const code = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)]
  .map((m) => m[1])
  .join("\n");

// ---------------- 最小 DOM 桩 ----------------
const elStub = () => ({
  onclick: null,
  onchange: null,
  oninput: null,
  textContent: "",
  innerHTML: "",
  value: "",
  style: new Proxy({}, { get: () => "", set: () => true }),
  classList: { add() {}, remove() {}, toggle() {}, contains: () => false },
  addEventListener() {},
  appendChild() {},
  removeChild() {},
  querySelector: () => elStub(),
  querySelectorAll: () => [],
  setAttribute() {},
  getAttribute: () => null,
  focus() {},
  click() {},
  children: [],
  dataset: {},
});

const created = new Map();
const documentStub = {
  getElementById(id) {
    if (!created.has(id)) created.set(id, elStub());
    return created.get(id);
  },
  createElement: () => elStub(),
  querySelector: () => elStub(),
  querySelectorAll: () => [],
  addEventListener() {},
  body: elStub(),
  documentElement: elStub(),
};
const windowStub = {
  addEventListener() {},
  localStorage: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
  matchMedia: () => ({ matches: false, addEventListener() {} }),
  location: { hash: "", href: "" },
};

let mod;
try {
  mod = new Function(
    "document",
    "window",
    "localStorage",
    "TextEncoder",
    "btoa",
    code +
      "\n;return { isPdfName, baseName, normPath, parsePropfind, sortEntries," +
      " pdfEntries, skippedCount, joinPath, parentPath, errorText, basicAuth," +
      " cacheKeyOf, evictCache, TREE, dlLabel, dlBytes, dlSize };"
  )(documentStub, windowStub, windowStub.localStorage, TextEncoder, (s) =>
    Buffer.from(s, "binary").toString("base64")
  );
} catch (e) {
  console.error("页面脚本执行失败：", e && e.stack ? e.stack : e);
  process.exit(1);
}

// ---------------- 断言小工具 ----------------
let pass = 0;
const fails = [];
function eq(name, actual, expected) {
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a === b) pass++;
  else fails.push(`${name}\n    期望 ${b}\n    实际 ${a}`);
}
function ok(name, cond, extra) {
  if (cond) pass++;
  else fails.push(`${name}${extra ? "\n    " + extra : ""}`);
}

// ---------------- 1. 只认 PDF ----------------
eq("小写 .pdf 算乐谱", mod.isPdfName("月光.pdf"), true);
eq("大写 .PDF 也算（网盘上大小写很随意）", mod.isPdfName("MOON.PDF"), true);
eq(".txt 不算", mod.isPdfName("说明.txt"), false);
eq(".pdfx 不算", mod.isPdfName("假货.pdfx"), false);
eq("空串不算", mod.isPdfName(""), false);

// ---------------- 2. 路径与名字 ----------------
eq("取最后一段", mod.baseName("/dav/钢琴/"), "钢琴");
eq("解 %E9%92%A2%E7%90%B4 转义", mod.baseName("/dav/%E9%92%A2%E7%90%B4"), "钢琴");
eq("解 %20 空格", mod.baseName("/dav/a%20b.pdf"), "a b.pdf");
eq("没有斜杠时整个就是名字", mod.baseName("a.pdf"), "a.pdf");
eq("规范化去掉末尾斜杠", mod.normPath("/dav/"), "/dav");
eq("规范化会解转义", mod.normPath("/dav/%E9%92%A2%E7%90%B4"), "/dav/钢琴");

// ---------------- 3. PROPFIND 解析 ----------------
const XML = `<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/dav/</d:href>
    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/%E9%92%A2%E7%90%B4/</d:href>
    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/moon.pdf</d:href>
    <d:propstat><d:prop><d:getcontentlength>881869</d:getcontentlength></d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/notes.txt</d:href>
    <d:propstat><d:prop><d:getcontentlength>120</d:getcontentlength></d:prop></d:propstat>
  </d:response>
</d:multistatus>`;

const parsed = mod.parsePropfind(XML, "/dav");
eq("PROPFIND 解析出 3 条（目录自身被跳过）", parsed.length, 3);
eq("第一条是目录 钢琴", parsed[0].name, "钢琴");
eq("钢琴是目录", parsed[0].dir, true);
eq("moon.pdf 不是目录", parsed[1].dir, false);
eq("大小解析出来了", parsed[1].size, 881869);
eq("路径原样保留（拿去继续导航）", parsed[1].path, "/dav/moon.pdf");

const withSelf = mod.parsePropfind(XML, "/nope");
eq("self 传错时目录自身不会被跳过（说明过滤靠的是 self）", withSelf.length, 4);

eq(
  "目录自身那条必须被跳过，否则列表里永远多一个幽灵项",
  mod.parsePropfind(XML, "/dav/").length,
  3
);
eq("空响应解析出空列表", mod.parsePropfind("", "/dav").length, 0);
eq("坏 XML 不炸，返回空", mod.parsePropfind("<not-xml", "/dav").length, 0);

// ---------------- 3.5 AList 真实响应形态（真机踩出来的坑） ----------------
// AList 对目录返回的是 `<D:collection xmlns:D="DAV:"/>` —— collection 标签
// 带命名空间属性。判定正则若只认裸 `<D:collection/>`，目录会被误判成文件，
// 症状是「列表全空，只剩一行『已跳过 N 个非 PDF』」（1.16 真机实锤）。
const ALIST_XML = `<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection xmlns:D="DAV:"/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/%E5%A4%B8%E5%85%8B%E7%BD%91%E7%9B%98/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection xmlns:D="DAV:"/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/%E5%A4%B8%E5%85%8B%E7%BD%91%E7%9B%98/%E6%9C%88%E5%85%89.pdf</D:href>
    <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>881869</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
</D:multistatus>`;
const alist = mod.parsePropfind(ALIST_XML, "/dav");
eq("AList 形态：解析出 2 条（根自身跳过）", alist.length, 2);
eq("AList 形态：带属性的 collection 被判成目录", alist[0].dir, true);
eq("AList 形态：名字正确解出「夸克网盘」", alist[0].name, "夸克网盘");
eq("AList 形态：文件大小解析", alist[1].size, 881869);
eq("AList 形态：目录不再被算进跳过数", mod.skippedCount(alist), 0);
eq("AList 形态：挑出 PDF", mod.pdfEntries(alist).map((e) => e.name), ["月光.pdf"]);

// ---------------- 4. 排序：目录在前 ----------------
const mixed = [
  { name: "moon.pdf", dir: false },
  { name: "钢琴", dir: true },
  { name: "apple.pdf", dir: false },
  { name: "拜厄", dir: true },
];
const sorted = mod.sortEntries(mixed);
eq("目录排在最前", [sorted[0].dir, sorted[1].dir], [true, true]);
eq("目录内部按名", sorted[0].name < sorted[1].name, true);
eq("文件排在目录后", [sorted[2].dir, sorted[3].dir], [false, false]);
eq("文件按名", [sorted[2].name, sorted[3].name], ["apple.pdf", "moon.pdf"]);
eq("排序不改原数组", mixed[0].name, "moon.pdf");

// ---------------- 5. 挑 PDF / 统计跳过 ----------------
const all = mod.parsePropfind(XML, "/dav");
eq("只要 PDF", mod.pdfEntries(all).map((e) => e.name), ["moon.pdf"]);
eq("被跳过的非 PDF 数量", mod.skippedCount(all), 1);
eq("全是 PDF 时跳过数为 0", mod.skippedCount([{ name: "a.pdf", dir: false }]), 0);
eq("目录不算被跳过", mod.skippedCount([{ name: "d", dir: true }]), 0);

// ---------------- 6. 路径拼接与回退 ----------------
eq("拼接会转义中文", mod.joinPath("/dav", "钢琴"), "/dav/%E9%92%A2%E7%90%B4");
eq("拼接不会重复斜杠", mod.joinPath("/dav/", "a"), "/dav/a");
eq("上一级", mod.parentPath("/dav/a/b", "/dav"), "/dav/a");
eq("上一级到根就停在根", mod.parentPath("/dav/a", "/dav"), "/dav");
eq("已经在根就返回 null", mod.parentPath("/dav", "/dav"), null);
eq("根带斜杠也算同一个根", mod.parentPath("/dav/", "/dav"), null);

// ---------------- 7. 错误信息翻人话 ----------------
ok("401 说清楚是账号密码", /账号或密码/.test(mod.errorText(401)), mod.errorText(401));
ok("404 点明要带 /dav", /\/dav/.test(mod.errorText(404)), mod.errorText(404));
ok("超时提示公网地址", /公网|超时/.test(mod.errorText("timeout")), mod.errorText("timeout"));
ok("403 说没权限", /权限/.test(mod.errorText(403)), mod.errorText(403));
ok("未知状态码带上数字", /500/.test(mod.errorText(500)), mod.errorText(500));
ok("未知错误也有兜底文案", mod.errorText("??").length > 0);

// ---------------- 8. 认证头 ----------------
eq("Basic 头算对了", mod.basicAuth("admin", "1234"), "Basic YWRtaW46MTIzNA==");
ok("Basic 头带前缀", mod.basicAuth("u", "p").indexOf("Basic ") === 0);
ok(
  "中文密码不炸（btoa 吃不下非 ASCII，必须先过 UTF-8 字节）",
  mod.basicAuth("admin", "密码").indexOf("Basic ") === 0
);

// ---------------- 9. 缓存：只留最近 1 份 ----------------
eq("缓存 key 用规范化路径", mod.cacheKeyOf("/dav/a%20b.pdf"), "/dav/a b.pdf");
const c1 = { "/dav/a.pdf": { name: "a" }, "/dav/b.pdf": { name: "b" } };
eq("淘汰后只留指定的那份", Object.keys(mod.evictCache(c1, "/dav/b.pdf")), ["/dav/b.pdf"]);
eq("留下的内容还在", mod.evictCache(c1, "/dav/b.pdf")["/dav/b.pdf"].name, "b");
eq("key 不在缓存里就全清", Object.keys(mod.evictCache(c1, "/dav/z.pdf")), []);
eq("缓存最多只有一份", Object.keys(mod.evictCache(c1, "/dav/a.pdf")).length, 1);

// ---------------- 10. 演示数据自检 ----------------
ok("演示网盘有根目录", Array.isArray(mod.TREE[""]));
ok("根目录里有目录也有文件", mod.TREE[""].some((e) => e.dir) && mod.TREE[""].some((e) => !e.dir));
ok("每一层都是数组", Object.keys(mod.TREE).every((k) => Array.isArray(mod.TREE[k])));

// ---------------- 进度文案：跑满之后不许倒回「正在下载…」 ----------------
eq("字节收完说人话", mod.dlLabel(100, 6291456, 6291456), "已下载，正在打开…");
eq("服务端少报长度也算收完", mod.dlLabel(137, 6291456, 6291456), "已下载，正在打开…");
eq("下载中同时给百分比和字节", mod.dlLabel(37, 2097152, 6291456), "37%  2.0 MB / 6.0 MB");
eq("服务端没给总长度就只说已收", mod.dlLabel(-1, 1048576, 0), "正在下载…  已收 1.0 MB");
eq("一点都还没收到", mod.dlLabel(-1, 0, 0), "正在下载…");
eq("0% 也报百分比", mod.dlLabel(0, 0, 0), "0%");
eq("KB 级也看得见变化", mod.dlLabel(-1, 524288, 6291456), "正在下载…  512 KB / 6.0 MB");

// ---------------- 汇总 ----------------
if (fails.length) {
  console.log(`\n失败 ${fails.length} 项：`);
  fails.forEach((f) => console.log("  ✗ " + f));
}
console.log(`\n通过 ${pass} 项，失败 ${fails.length} 项`);
if (fails.length) {
  console.log("失败 ✗");
  process.exit(1);
}
console.log("通过 ✓");
