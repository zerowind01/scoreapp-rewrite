/**
 * netdisk-library-demo.html 的业务逻辑回归测试。
 *
 * 为什么单独一个文件：这条链路（绑定文件夹 → 首页入库 → 编辑元数据）的语义
 * 很容易被"顺手改坏"——尤其是「用户改过的元数据不能被同步覆盖」这条，
 * 一旦破掉，用户每次同步都会丢自己填的东西，而他未必马上发现。
 *
 * 做法与 netdisk-test.js 同一套路：抽出 <script> 丢进最小 DOM 桩求值，再打纯函数。
 */
const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "netdisk-library-demo.html"), "utf8");
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
    code +
      "\n;return { isPdfName, normPath, joinPath, titleFromFileName, metaKeyOf," +
      " defaultMetaOf, applyMeta, syncLibrary, saveMetaDraft, toggleBound, isBound," +
      " boundSummary, needDownload, formatAgo, syncStatusText, fetchBoundDir, TREE, LOCAL," +
      " itemsForTab, tabTitle, TAB_LOCAL, TAB_NET, dlLabel, dlBytes, dlSize," +
      " COVER_PALETTES, coverPalette, coverFor };"
  )(documentStub, windowStub, windowStub.localStorage);
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

// ---------------- 1. 文件名 → 默认标题 ----------------
eq("去扩展名", mod.titleFromFileName("拜厄 No.1.pdf"), "拜厄 No.1");
eq("大写 .PDF 也去", mod.titleFromFileName("MOON.PDF"), "MOON");
eq("目录名里的点不误伤", mod.titleFromFileName("Op.27 No.2.pdf"), "Op.27 No.2");
eq("没有扩展名就原样", mod.titleFromFileName("欢乐颂"), "欢乐颂");
eq("空串", mod.titleFromFileName(""), "");

eq("小写 pdf 算", mod.isPdfName("月光.pdf"), true);
eq("大写算", mod.isPdfName("MOON.PDF"), true);
eq("txt 不算", mod.isPdfName("目录.txt"), false);

// ---------------- 2. 身份键 ----------------
eq("身份键是规范化路径", mod.metaKeyOf("/dav/乐谱/月光.pdf/"), "/dav/乐谱/月光.pdf");
eq(
  "不同目录下的同名各自独立",
  mod.metaKeyOf("/dav/钢琴/拜厄.pdf") === mod.metaKeyOf("/dav/声乐/拜厄.pdf"),
  false
);

// ---------------- 3. 默认元数据 ----------------
const dm = mod.defaultMetaOf({ name: "月光.pdf", path: "/dav/乐谱/月光.pdf", size: 881869 });
eq("默认标题来自文件名", dm.title, "月光");
eq("作曲家占位", dm.composer, "佚名");
eq("类型占位", dm.type, "未编目");
eq("乐器占位", dm.instrument, "未分类");
eq("时期占位", dm.period, "未指定");
eq("难度占位", dm.level, "—");
eq("来源是网盘", dm.source, "网盘");
eq("体积带上了", dm.size, 881869);

// ---------------- 4. 应用用户改过的元数据 ----------------
const merged = mod.applyMeta(dm, { composer: "贝多芬", title: "月光奏鸣曲" });
eq("改过的标题生效", merged.title, "月光奏鸣曲");
eq("改过的作曲家生效", merged.composer, "贝多芬");
eq("没改的仍是默认", merged.type, "未编目");
eq("改元数据不动身份键", merged.key, dm.key);
eq("meta 为 null 时原样返回", mod.applyMeta(dm, null).title, "月光");
eq("meta 里的空串不覆盖", mod.applyMeta(dm, { composer: "" }).composer, "佚名");
eq("applyMeta 不改原对象", dm.composer, "佚名");

// ---------------- 5. 同步：以远端为准，但不动用户改的 ----------------
const REMOTE = [
  {
    dir: "/dav/乐谱",
    files: [
      { name: "月光.pdf", path: "/dav/乐谱/月光.pdf", size: 881869 },
      { name: "拜厄.pdf", path: "/dav/乐谱/拜厄.pdf", size: 245760 },
      { name: "目录.txt", path: "/dav/乐谱/目录.txt", size: 120 },
    ],
  },
];
const r1 = mod.syncLibrary([], {}, REMOTE);
eq("非 PDF 不进库（只进 2 份）", r1.items.length, 2);
eq("新增计数", r1.added, 2);
eq("首次同步没有移除", r1.removed, 0);

const r2 = mod.syncLibrary(r1.items, {}, REMOTE);
eq("同样的远端再同步不重复加", r2.items.length, 2);
eq("二次同步新增为 0", r2.added, 0);

let metas = mod.saveMetaDraft({}, "/dav/乐谱/月光.pdf", {
  title: "月光奏鸣曲",
  composer: "贝多芬",
  instrument: "",
  type: "",
  period: "",
  level: "",
});
const r3 = mod.syncLibrary(r2.items, metas, REMOTE);
eq("同步不覆盖用户改的标题", r3.items[0].title, "月光奏鸣曲");
eq("同步不覆盖用户改的作曲家", r3.items[0].composer, "贝多芬");
eq("没改过的条目仍是默认", r3.items[1].composer, "佚名");

const r4 = mod.syncLibrary(r3.items, metas, [{ dir: "/dav/乐谱", files: [] }]);
eq("远端消失的条目被移除", r4.items.length, 0);
eq("移除计数", r4.removed, 2);
ok("条目消失但元数据留着（改名/误删容错）", Object.keys(metas).length === 1);

const r5 = mod.syncLibrary(r3.items, metas, [
  { dir: "/dav/乐谱", files: [{ name: "月光.pdf", path: "/dav/乐谱/月光.pdf", size: 999 }] },
  { dir: "/dav/备份", files: [{ name: "月光.pdf", path: "/dav/乐谱/月光.pdf", size: 999 }] },
]);
eq("两个绑定目录命中同一份只进一次", r5.items.length, 1);

const r6 = mod.syncLibrary([], {}, [
  { dir: "/dav/乐谱", files: [{ name: "月光.pdf", path: "/dav/乐谱/月光.pdf", size: 555 }] },
]);
eq("体积以远端为准", r6.items[0].size, 555);

// ---------------- 6. 编辑草稿落盘 ----------------
const m1 = mod.saveMetaDraft({}, "/a.pdf", { composer: "贝多芬", title: "", type: "" });
eq("只存非空字段", Object.keys(m1["/a.pdf"]), ["composer"]);
const m2 = mod.saveMetaDraft(m1, "/a.pdf", { composer: "" });
eq("字段改空 = 退回默认", m2["/a.pdf"], undefined);
eq("全空时整个 key 删掉", Object.keys(m2).length, 0);
const m3 = mod.saveMetaDraft({ "/b.pdf": { title: "拜厄" } }, "/a.pdf", { composer: "车尔尼" });
eq("不影响别的条目", m3["/b.pdf"].title, "拜厄");
eq("saveMetaDraft 不改原对象", Object.keys(m1).length, 1);

// ---------------- 7. 绑定 ----------------
let b = [];
b = mod.toggleBound(b, "/dav/乐谱");
eq("绑定后有一条", b.length, 1);
ok("已绑定判定", mod.isBound(b, "/dav/乐谱"));
b = mod.toggleBound(b, "/dav/伴奏");
eq("可以绑多个", b.length, 2);
b = mod.toggleBound(b, "/dav/乐谱");
eq("再点一次是解绑", b.length, 1);
ok("解绑后判定为假", !mod.isBound(b, "/dav/乐谱"));
ok("末尾斜杠不算两个", mod.isBound(["/dav/乐谱"], "/dav/乐谱/"));
eq("绑定摘要", mod.boundSummary(["/a", "/b"]), "已绑定 2 个文件夹");
eq("没绑定时的摘要", mod.boundSummary([]), "还没绑定文件夹");
const before = ["/dav/乐谱"];
mod.toggleBound(before, "/dav/伴奏");
eq("toggleBound 不改原数组", before.length, 1);

// ---------------- 8. 要不要下载 ----------------
const it = { key: "/dav/乐谱/月光.pdf" };
ok("没缓存就要下载", mod.needDownload(it, {}));
ok("有缓存就不下", !mod.needDownload(it, { "/dav/乐谱/月光.pdf": true }));
ok("缓存里是别的谱子照样要下", mod.needDownload(it, { "/dav/乐谱/拜厄.pdf": true }));

// ---------------- 9. 时间人话 ----------------
eq("从没同步过", mod.formatAgo(0), "从没同步过");
eq("刚刚", mod.formatAgo(30 * 1000), "刚刚");
eq("分钟前", mod.formatAgo(5 * 60 * 1000), "5 分钟前");
eq("小时前", mod.formatAgo(2 * 60 * 60 * 1000), "2 小时前");
eq("天前", mod.formatAgo(3 * 24 * 60 * 60 * 1000), "3 天前");

// ---------------- 10. 同步条文案 ----------------
eq("同步中", mod.syncStatusText({ busy: true, boundCount: 1, syncedAt: 1, ageMs: 0 }), "正在同步…");
eq(
  "没绑定时提示去绑",
  mod.syncStatusText({ busy: false, boundCount: 0, syncedAt: 0, ageMs: 0 }),
  "还没绑定文件夹，点右上「绑定文件夹」"
);
eq(
  "没同步过提示立即同步",
  mod.syncStatusText({ busy: false, boundCount: 1, syncedAt: 0, ageMs: 0 }),
  "还没同步过，点「立即同步」"
);
const errText = mod.syncStatusText({
  busy: false,
  boundCount: 1,
  error: "连不上网盘",
  syncedAt: 1,
  ageMs: 5 * 60 * 1000,
});
ok("失败要说清显示的是多久前的列表", errText.indexOf("同步失败") >= 0 && errText.indexOf("5 分钟前") >= 0, errText);
const okText = mod.syncStatusText({
  busy: false,
  boundCount: 1,
  boundPaths: ["/dav/乐谱"],
  syncedAt: 1,
  ageMs: 30 * 1000,
});
ok("正常态要带上已同步时间", okText.indexOf("已同步") >= 0 && okText.indexOf("刚刚") >= 0, okText);

// ---------------- 11. 只收当前层 ----------------
const listed = mod.fetchBoundDir("/dav/夸克网盘/乐谱");
ok("拉取到条目", listed.length > 0);
ok(
  "子目录不作为条目进库（只收当前层 PDF）",
  listed.filter((f) => f.name === "钢琴").length === 0
);
const withSub = mod.syncLibrary([], {}, [{ dir: "/dav/夸克网盘/乐谱", files: listed }]);
ok(
  "同步后非 PDF 一个都没进",
  withSub.items.filter((i) => !mod.isPdfName(i.remotePath)).length === 0
);

// ---------------- 12. 本机谱不受影响 ----------------
eq("本机样例三份", mod.LOCAL.length, 3);
ok("本机条目来源是本地导入", mod.LOCAL.every((s) => s.source === "本地导入"));

// ---------------- 13. 本机 / 网盘 两个标签页 ----------------
const NET3 = [
  { key: "/dav/乐谱/月光.pdf", title: "月光", remotePath: "/dav/乐谱/月光.pdf" },
  { key: "/dav/乐谱/拜厄.pdf", title: "拜厄", remotePath: "/dav/乐谱/拜厄.pdf" },
];
eq("网盘页只给网盘条目", mod.itemsForTab(mod.TAB_NET, NET3, mod.LOCAL).length, 2);
eq("本机页只给本机条目", mod.itemsForTab(mod.TAB_LOCAL, NET3, mod.LOCAL).length, 3);
eq("本机页拿到的就是 LOCAL 本身", mod.itemsForTab(mod.TAB_LOCAL, NET3, mod.LOCAL), mod.LOCAL);
eq("两个标签不是同一个值", mod.TAB_LOCAL === mod.TAB_NET, false);
eq("标签带份数（网盘）", mod.tabTitle(mod.TAB_NET, 2, 3), "网盘 2");
eq("标签带份数（本机）", mod.tabTitle(mod.TAB_LOCAL, 2, 3), "本机 3");
ok(
  "网盘条目都带远端路径（否则不知道去哪下载）",
  mod.itemsForTab(mod.TAB_NET, NET3, mod.LOCAL).every((i) => !!i.remotePath)
);
ok(
  "本机条目都不带远端路径",
  mod.itemsForTab(mod.TAB_LOCAL, NET3, mod.LOCAL).every((i) => !i.remotePath)
);

// ---------------- 进度文案：跑满之后不许倒回「正在下载…」 ----------------
eq("字节收完说人话", mod.dlLabel(100, 6291456, 6291456), "已下载，正在打开…");
eq("服务端少报长度也算收完", mod.dlLabel(137, 6291456, 6291456), "已下载，正在打开…");
eq("下载中同时给百分比和字节", mod.dlLabel(37, 2097152, 6291456), "37%  2.0 MB / 6.0 MB");
eq("服务端没给总长度就只说已收", mod.dlLabel(-1, 1048576, 0), "正在下载…  已收 1.0 MB");
eq("一点都还没收到", mod.dlLabel(-1, 0, 0), "正在下载…");
eq("0% 也报百分比", mod.dlLabel(0, 0, 0), "0%");
eq("KB 级也看得见变化", mod.dlLabel(-1, 524288, 6291456), "正在下载…  512 KB / 6.0 MB");

// ---------------- 封面卡：配色稳定 + 占位符过滤 ----------------
const pal0 = mod.coverPalette("/dav/乐谱/月光.pdf");
ok("配色落在色板范围内", pal0 >= 0 && pal0 < mod.COVER_PALETTES.length);
eq("同一份谱子永远同一组颜色", mod.coverPalette("/dav/乐谱/月光.pdf"), pal0);
eq("色板十组", mod.COVER_PALETTES.length, 10);
(function () {
  const seen = new Set();
  for (let i = 1; i <= 520; i++) seen.add(mod.coverPalette("/dav/quark/乐谱/谱" + i + ".pdf"));
  ok("520 份的库不至于挤在一两格里", seen.size >= 6, "只落到 " + seen.size + " 组");
})();
const bareCover = mod.coverFor({
  title: "月光", composer: "佚名", type: "未编目", instrument: "未分类",
  remotePath: "/dav/乐谱/月光.pdf",
});
eq("占位作曲家不印", bareCover.composer, null);
eq("占位类型/乐器不印", bareCover.sub, null);
const fullCover = mod.coverFor({
  title: "月光", composer: "贝多芬", type: "奏鸣曲", instrument: "钢琴",
  remotePath: "/dav/乐谱/月光.pdf",
});
eq("真作曲家印上", fullCover.composer, "贝多芬");
eq("类型·乐器 拼进副行", fullCover.sub, "奏鸣曲 · 钢琴");
ok("配色跟远端路径走", fullCover.palette === mod.coverPalette("/dav/乐谱/月光.pdf"));
ok("空作曲家也不印", mod.coverFor({ remotePath: "/x.pdf", composer: "", type: "未编目", instrument: "未分类" }).composer === null);

// ---------------- 结果 ----------------
console.log(`\n通过 ${pass} 项，失败 ${fails.length} 项`);
if (fails.length) {
  console.log("\n失败明细：");
  fails.forEach((f) => console.log("  ✗ " + f));
  process.exit(1);
}
console.log("通过 ✓");
