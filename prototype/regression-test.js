/**
 * 原型逻辑回归测试。
 *
 * 用最小 DOM 桩把 <script> 跑起来，再对纯业务函数做断言。
 * 这些函数都从模块级 `state` 读取当前条件，因此测试通过改 state 来驱动，
 * 与真实交互路径一致（而不是给函数传参，避免测到不存在的重载）。
 */
const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "scoreapp-prototype.html"), "utf8");
const code = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)]
  .map((m) => m[1])
  .join("\n");

// ---------------- 最小 DOM 桩 ----------------
const ctx = new Proxy(
  {},
  {
    get(t, k) {
      if (k in t) return t[k];
      if (k === "measureText") return () => ({ width: 42 });
      return () => {};
    },
    set(t, k, v) { t[k] = v; return true; },
  },
);

function makeEl(id) {
  return {
    id,
    textContent: "",
    innerHTML: "",
    value: "",
    selectionStart: 0,
    dataset: {},
    // 真实 DOM 的 style 有 setProperty：底栏气泡改 --i 走的就是这条路
    style: { setProperty() {}, removeProperty() {}, getPropertyValue() { return ""; } },
    classList: {
      _s: new Set(),
      add(c) { this._s.add(c); },
      remove(c) { this._s.delete(c); },
      contains(c) { return this._s.has(c); },
      toggle(c) { this._s.has(c) ? this._s.delete(c) : this._s.add(c); },
    },
    addEventListener() {},
    removeEventListener() {},
    appendChild() {},
    querySelectorAll() { return []; },
    // 原先返回 null：任何「先查元素再改样式」的代码在测试里都会炸。
    // 返回一个一次性桩元素即可（与 getElementById 不同，这里的查询结果不需要跨调用稳定）。
    querySelector() { return makeEl("q"); },
    focus() {},
    blur() {},
    setSelectionRange() {},
    getAttribute() { return null; },
    setAttribute() {},
    getContext() { return ctx; },
    getBoundingClientRect() { return { width: 100, height: 134, top: 0, left: 0 }; },
    scrollIntoView() {},
  };
}

/**
 * 给真实 DOM 桩补上一个最小「元素树」。
 *
 * 编辑页的作曲家补全要按输入实时过滤候选，而候选既可能以「独立浮层」
 * 渲染、也可能退化成内联面板；两种形态的检查都得能查得到节点，
 * 因此桩元素需要一个真的能挂子节点的容器，而不是一个空壳。
 */
function makeChild(tag, attrs){
  const el = makeEl(tag);
  el.tagName = tag;
  el.attrs = attrs || {};
  el.getAttribute = (k) => (k in el.attrs ? el.attrs[k] : null);
  el.children = [];
  el.appendChild = (c) => { el.children.push(c); return c; };
  el.querySelectorAll = (sel) => {
    const out = [];
    const wantCls = (sel.match(/^\\.([\\w-]+)$/) || [])[1];
    const wantTag = /^[a-z]+$/i.test(sel) ? sel.toLowerCase() : null;
    const walk = (n) => {
      for (const c of n.children || []) {
        const cls = (c.attrs && c.attrs.class) || "";
        if ((wantCls && cls.split(/\\s+/).includes(wantCls)) ||
            (wantTag && (c.tagName || "").toLowerCase() === wantTag)) out.push(c);
        walk(c);
      }
    };
    walk(el);
    return out;
  };
  return el;
}

const els = new Map();
const document = {
  getElementById(id) {
    if (!els.has(id)) els.set(id, makeEl(id));
    return els.get(id);
  },
  querySelectorAll() { return []; },
  querySelector() { return null; },
  addEventListener() {},
  createElement(tag) { return makeChild(tag); },
  body: makeEl("body"),
  documentElement: makeEl("html"),
};

const window = {
  devicePixelRatio: 2,
  addEventListener() {},
  requestAnimationFrame() { return 0; },
  setTimeout() { return 0; },
  matchMedia() { return { matches: false, addEventListener() {} }; },
  localStorage: { getItem() { return null; }, setItem() {} },
};

const exportTail = `
;globalThis.__T = {
  SCORES, SETS, state,
  shortName, initialOf, hashStr, avatarColor,
  matchesQuery, matchesFilters, visibleScores, sortScores, groupScores,
  facetCount, pendingResultCount, facetValues, distinct, composerGroups,
  setMembers, setsOfScore, scoreById,
  SURNAME_INITIAL, ALIAS,
  normalizeDraft, metaLine, fmtDate, importScore,
  openDetail, renderMoreSheet, renderBottomNav, renderMain,
  openSheet, renderSheet, renderEditSheet, closeSheet,
  composerSuggestions, composerMatchRange,
  DIM_LABEL, topValue,
  probeCoverDraw,
  // 文件链路（导入 / 落盘 / 打开 / 分享）
  FILE_STORE, PDF_FILES, ALBUM_COUNT, BUNDLED_PDF, PAGE_RATIO, RENDER_W,
  pdfPathOf, resolvePdfPath, fileExists, pdfReady, fmtBytes,
  pdfImportTitle, importFileName, albumImportTitle, imagesToPdf, albumImageBroken,
  shareFileName, shareSummary,
  // 封面渲染文件首页（对译 Thumb.kt / ThumbCache）
  THUMB_WIDTH, THUMB_MAX_UPSCALE, THUMB_CACHE_MAX, THUMB_CACHE, CoverLoad, coverState,
  loadCoverBitmap, renderFirstPage, resolveCoverState, thumbCachePut, newCoverBitmap,
  parsePdf, pdfPageContent, rawInflate, execPdfContent,
  // 阅读器顶栏文案（对译 ReaderBarText.kt）
  READER_LOADING_TEXT, READER_FAILED_TEXT, READER_EMPTY_TEXT,
  readerSubtitle, readerBadge,
};

/**
 * 用替身 Canvas 上下文捕获 drawCover 的绘制调用，供回归断言检查。
 * 返回记录到的文本（含坐标/字号/对齐）与装饰弧数量。
 */
function probeCoverDraw(score){
  const W = 240, H = 310;         // 与原型实际缩略图 canvas 像素尺寸一致
  const texts = [], arcs = [];
  const ctx = {
    canvas:{width:W, height:H},
    font:"", textAlign:"", fillStyle:"", strokeStyle:"", lineWidth:1, globalAlpha:1,
    clearRect(){}, fillRect(){}, strokeRect(){}, beginPath(){}, stroke(){}, moveTo(){}, lineTo(){},
    save(){}, restore(){}, translate(){}, scale(){}, closePath(){}, arc(x,y,r,s,e){ arcs.push({x,y,r,s,e}); },
    createLinearGradient(){ return { addColorStop(){} }; },
    fillText(t,x,y){ texts.push({ text:t, args:[t,x,y], size:fontSize(this.font), align:this.textAlign }); },
    measureText(t){ return { width: fontWidth(t, fontSize(this.font)) }; },
  };
  const fontSize = (f) => { const m=/(\\d+(?:\\.\\d+)?)px/.exec(f||""); return m ? parseFloat(m[1]) : 0; };
  // 粗估文本宽度：CJK 按 1em、其余按 0.55em，够用于溢出断言
  const fontWidth = (t, s) => [...t].reduce((n,ch)=> n + (ch.charCodeAt(0) > 0x2E80 ? s : s*0.55), 0);
  drawCover(ctx, W, H, score);
  return { w:W, h:H, kx:W/100, ky:H/100, texts, arcs:arcs.length };
}
`;

new Function(
  "document", "window", "setTimeout", "clearTimeout", "requestAnimationFrame", "navigator", "location",
  code + exportTail,
)(document, window, () => 0, () => {}, () => 0, { userAgent: "node" }, { href: "" });

const T = globalThis.__T;
if (!T) { console.error("无法导出内部符号，测试中止"); process.exit(1); }
const S = T.state;

// ---------------- 断言工具 ----------------
let pass = 0;
const fails = [];
function eq(label, actual, expected) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a === e) pass++;
  else fails.push(`${label}\n      期望 ${e}\n      实际 ${a}`);
}
function ok(label, cond) { cond ? pass++ : fails.push(label); }

function resetState() {
  S.query = "";
  S.filters = { composer: new Set(), type: new Set(), instrument: new Set() };
  S.pending = { composer: new Set(), type: new Set(), instrument: new Set() };
  S.sort = "composer";
  S.groupBy = "composer";
}

// ---------------- 1. 数据规模 ----------------
eq("样例乐谱数量", T.SCORES.length, 3);
eq("谱单数量", T.SETS.length, 2);
ok("存在封面型缩略图", T.SCORES.some((s) => s.thumbKind === "cover"));
ok("存在雕版型缩略图", T.SCORES.some((s) => s.thumbKind === "engrave"));
eq("每首乐谱都有 thumbSeed", T.SCORES.filter((s) => typeof s.thumbSeed !== "number").length, 0);

// ---------------- 2. 姓名处理 ----------------
eq("shortName 取中点后一段", T.shortName("路德维希·凡·贝多芬"), "贝多芬");
eq("shortName 无中点时原样返回", T.shortName("肖邦"), "肖邦");
eq("initialOf 命中人工字母表（肖邦→X）", T.initialOf("弗雷德里克·肖邦"), "X");
eq("initialOf 未命中字母表时汉字视为字母（不归 #）", T.initialOf("张某某"), "张");
eq("initialOf 非字母开头归 #", T.initialOf("123 无名氏"), "#");
eq(
  "字母表覆盖全部别名（每个姓氏都有索引字母）",
  Object.keys(T.ALIAS).filter((k) => !T.SURNAME_INITIAL[k]).length,
  0,
);

// ---------------- 3. 头像配色确定性 ----------------
eq("同人同色", T.avatarColor("弗雷德里克·肖邦"), T.avatarColor("弗雷德里克·肖邦"));
ok("不同人不同色", T.avatarColor("弗雷德里克·肖邦") !== T.avatarColor("弗朗茨·李斯特"));
ok("配色为 HSL(h, 46%, 46%) 形式", /^hsl\(\d+(\.\d+)?, 46%, 46%\)$/.test(T.avatarColor("巴赫")));

// ---------------- 4. 搜索 ----------------
resetState();
eq("空查询返回全量", T.visibleScores().length, T.SCORES.length);
S.query = "月光";
ok("搜索命中标题", T.visibleScores().length > 0);
S.query = "贝多芬";
ok("搜索命中作曲家", T.visibleScores().length > 0);
S.query = "BWV";
const upper = T.visibleScores().length;
S.query = "bwv";
eq("搜索大小写不敏感", T.visibleScores().length, upper);
S.query = "zzzz不存在";
eq("搜索无结果返回空", T.visibleScores().length, 0);
resetState();

// ---------------- 5. 筛选：维度内并集、维度间交集 ----------------
const chopinFull = T.SCORES.find((s) => s.composer.includes("肖邦")).composer;
const chopinCount = T.SCORES.filter((s) => s.composer === chopinFull).length;
const vivaldiFull = T.SCORES.find((s) => s.composer.includes("维瓦尔第")).composer;
const vivaldiCount = T.SCORES.filter((s) => s.composer === vivaldiFull).length;
ok("样例中含肖邦作品", chopinCount > 0);
ok("样例中含维瓦尔第作品", vivaldiCount > 0);

S.filters.composer = new Set([chopinFull]);
eq("单作曲家筛选", T.visibleScores().length, chopinCount);

S.filters.composer = new Set([chopinFull, vivaldiFull]);
eq("同维度多选取并集", T.visibleScores().length, chopinCount + vivaldiCount);

const chopinNocturne = T.SCORES.filter((s) => s.composer === chopinFull && s.type === "夜曲").length;
S.filters.type = new Set(["夜曲"]);
eq("跨维度取交集", T.visibleScores().length, chopinNocturne);

S.filters.type = new Set(["夜曲", "圆舞曲"]);
const chopinNightWaltz = T.SCORES.filter(
  (s) => s.composer === chopinFull && (s.type === "夜曲" || s.type === "圆舞曲"),
).length;
eq("同维度并集 + 跨维度交集组合", T.visibleScores().length, chopinNightWaltz);
resetState();

// 对照项要挑「在约束下真的存在」的取值。曲库精简到 3 首后，
// 单看肖邦只剩「夜曲」一种类型，凑不出第二个可对照的取值，
// 因此把约束放宽到「肖邦 + 维瓦尔第」，用「夜曲 / 协奏曲」互为对照。
const twoComposers = (type) =>
  T.SCORES.filter(
    (s) => (s.composer === chopinFull || s.composer === vivaldiFull) && s.type === type,
  ).length;

// ---------------- 6. 分面计数：同维度已选不参与计数 ----------------
S.pending.composer = new Set([chopinFull, vivaldiFull]);
eq(
  "分面计数受其它维度约束",
  T.facetCount("type", "夜曲"),
  twoComposers("夜曲"),
);
eq(
  "同维度其它选项计数不被自身已选清零",
  T.facetCount("type", "协奏曲"),
  twoComposers("协奏曲"),
);
ok("上一条计数大于 0（否则无法继续多选）", T.facetCount("type", "协奏曲") > 0);

// 关键回归：在 type 维度已选「夜曲」时，type 维度下的「协奏曲」计数不应归零
S.pending.type = new Set(["夜曲"]);
eq(
  "本维度已选条件不参与本维度计数（否则多选后全部归零）",
  T.facetCount("type", "协奏曲"),
  twoComposers("协奏曲"),
);
ok("上一条计数仍大于 0", T.facetCount("type", "协奏曲") > 0);
eq(
  "本维度已选项自身的计数等于其命中数",
  T.facetCount("type", "夜曲"),
  T.SCORES.filter((s) => s.composer === chopinFull && s.type === "夜曲").length,
);
S.pending.type = new Set();

eq(
  "查询自身维度时不受自身已选约束",
  T.facetCount("composer", chopinFull),
  chopinCount,
);
eq(
  "不可达取值计数为 0",
  T.facetCount("type", "交响曲"),
  T.SCORES.filter((s) => s.composer === chopinFull && s.type === "交响曲").length,
);
S.pending.composer = new Set([chopinFull, vivaldiFull]);
eq(
  "待应用条件总数按暂存态计算",
  T.pendingResultCount(),
  chopinCount + vivaldiCount,
);
resetState();

// ---------------- 7. 分组排序：数量降序 → 组名升序 ----------------
const groups = T.groupScores(T.SCORES, "composer");
ok("分组非空", groups.length > 0);
let sortedOk = true;
for (let i = 1; i < groups.length; i++) {
  const p = groups[i - 1], c = groups[i];
  if (p.items.length < c.items.length) sortedOk = false;
  else if (p.items.length === c.items.length && p.key.localeCompare(c.key, "zh") > 0) sortedOk = false;
}
ok("分组按数量降序、同数量按组名升序", sortedOk);
eq("分组后总数守恒", groups.reduce((n, g) => n + g.items.length, 0), T.SCORES.length);
eq(
  "作曲家分组的标签是姓氏",
  groups.find((g) => g.key === chopinFull).label,
  T.shortName(chopinFull),
);
const typeGroups = T.groupScores(T.SCORES, "type");
eq("按类型分组总数守恒", typeGroups.reduce((n, g) => n + g.items.length, 0), T.SCORES.length);

// ---------------- 8. 排序 ----------------
S.sort = "pages";
const byPages = T.sortScores(T.SCORES);
let pagesOk = true;
for (let i = 1; i < byPages.length; i++) if (byPages[i - 1].pages < byPages[i].pages) pagesOk = false;
ok("按页数从多到少排序", pagesOk);

S.sort = "dateAdded";
const byDate = T.sortScores(T.SCORES);
let dateOk = true;
for (let i = 1; i < byDate.length; i++) if (byDate[i - 1].dateAdded < byDate[i].dateAdded) dateOk = false;
ok("按最近添加排序", dateOk);

S.sort = "title";
const byTitle = T.sortScores(T.SCORES);
let titleOk = true;
for (let i = 1; i < byTitle.length; i++) if (byTitle[i - 1].title.localeCompare(byTitle[i].title, "zh") > 0) titleOk = false;
ok("按标题排序", titleOk);
resetState();

// ---------------- 9. 作曲家索引 ----------------
const cg = T.composerGroups();
eq("作曲家分组数 = 去重作曲家数", cg.length, T.distinct("composer"));
eq("每位作曲家都有首字母", cg.filter((g) => !g.initial).length, 0);
ok("首字母均非空且长度为 1", cg.every((g) => g.initial.length === 1));
// A–Z 索引条上不应出现汉字：样例数据里的每位作曲家都应能在姓氏表里查到索引字母
ok("作曲家索引字母全部为 A–Z", cg.every((g) => /^[A-Z]$/.test(g.initial)));

// ---------------- 10. 分面取值 ----------------
eq("分面取值去重", new Set(T.facetValues("composer")).size, T.facetValues("composer").length);
eq("分面取值覆盖全部取值", T.facetValues("type").length, T.distinct("type"));

// ---------------- 11. 谱单 ----------------
eq("每个谱单都有种子", T.SETS.filter((s) => !Array.isArray(s.seeds) || !s.seeds.length).length, 0);
const orphanSeeds = T.SETS.flatMap((s) => s.seeds).filter(
  (seed) => !T.SCORES.some((x) => x.thumbSeed === seed),
);
eq("谱单种子全部能在乐谱库中解析", orphanSeeds.length, 0);

// 反查「乐谱 → 所属谱单」：详情页「分类归属」面板的数据来源
ok("每个谱单都能解析出成员", T.SETS.every((s) => T.setMembers(s).length > 0));
eq(
  "谱单成员总数与种子总数一致",
  T.SETS.reduce((n, s) => n + T.setMembers(s).length, 0),
  T.SETS.reduce((n, s) => n + s.seeds.length, 0),
);
const inSet = T.SCORES.find((s) => s.thumbSeed === 37);
eq("能反查到乐谱所属谱单", T.setsOfScore(inSet).map((s) => s.name), ["独奏会备选曲目"]);
const orphan = T.SCORES.find((s) => !T.SETS.some((x) => x.seeds.includes(s.thumbSeed)));
ok("存在不属于任何谱单的乐谱", !!orphan);
eq("未入谱单的乐谱反查为空", T.setsOfScore(orphan).length, 0);
ok(
  "归属反查自洽：谱单成员里能找回原乐谱",
  T.SCORES.every((s) => T.setsOfScore(s).every((set) => T.setMembers(set).includes(s))),
);

// ---------------- 12. 保存时的字段归一化 ----------------
// 原应用在保存回调里给空字段填固定占位值，避免空串在筛选分面里裂成额外的桶
const blank = T.normalizeDraft({
  title: "  标题  ", composer: "", type: "", instrument: "",
  period: "", level: "", source: "", pages: "",
});
eq("归一化：标题去空格", blank.title, "标题");
eq("归一化：空作曲家 → 佚名", blank.composer, "佚名");
eq("归一化：空曲目类型 → 未编目", blank.type, "未编目");
eq("归一化：空乐器 → 未分类", blank.instrument, "未分类");
eq("归一化：空时期 → 未指定", blank.period, "未指定");
eq("归一化：空难度 → —", blank.level, "—");
eq("归一化：空来源 → 本地导入", blank.source, "本地导入");
eq("归一化：非法页数回落为 1", blank.pages, 1);

const filled = T.normalizeDraft({
  title: "x", composer: "贝多芬", type: "奏鸣曲", instrument: "钢琴",
  period: "古典", level: "高级", source: "Henle 原版", pages: "14",
});
eq("归一化不覆盖已有取值", filled.source, "Henle 原版");
eq("归一化解析页数", filled.pages, 14);

// ---------------- 13. 详情页元信息行 ----------------
const sample = T.SCORES[0];
eq(
  "元信息行按「类型 · 乐器 · 时期 · 难度」拼接",
  T.metaLine(sample),
  [sample.type, sample.instrument, sample.period, sample.level].join(" · "),
);
eq(
  "元信息行剔除空白字段，不留悬空分隔符",
  T.metaLine({ type: "奏鸣曲", instrument: "", period: "古典", level: "" }),
  "奏鸣曲 · 古典",
);

// ---------------- 14. 添加时间格式化 ----------------
// 详情页「元数据」面板新增「添加时间」一行，直接展示 fmtDate(dateAdded)
eq("fmtDate 输出 yyyy-MM-dd HH:mm", T.fmtDate(new Date(2026, 8, 17, 9, 5).getTime()), "2026-09-17 09:05");
eq("fmtDate 个位月日补零", T.fmtDate(new Date(2026, 0, 3, 7, 8).getTime()), "2026-01-03 07:08");
ok("每首乐谱都带 dateAdded", T.SCORES.every((s) => typeof s.dateAdded === "number" && s.dateAdded > 0));

// ---------------- 15. 导入落库 ----------------
// 导入原先只 toast、不落库；现在必须真正写进 SCORES，并带上可分享的 filePath。
// 提示语由调用方负责（两条路径的成功文案不同），落库部分保持无副作用。
const beforeCount = T.SCORES.length;
const beforeTopId = T.SCORES[0].id;
T.importScore("pdf", 1, { title: "月光" });
eq("导入后乐谱数 +1", T.SCORES.length, beforeCount + 1);
const imported = T.SCORES[0];
ok("新导入的乐谱置顶", imported.id > beforeTopId);
ok("新乐谱分配了全新 id", !T.SCORES.slice(1).some((s) => s.id === imported.id));
ok("新乐谱带 filePath（分享/打开 PDF 可用）", typeof imported.filePath === "string" && imported.filePath.length > 0);
ok("新乐谱登记了添加时间", imported.dateAdded > 0);
ok("新乐谱 thumbSeed 可参与缩略图绘制的取值范围", Number.isInteger(imported.thumbSeed));
eq("导入后可被可见列表检索到（无筛选时）", T.visibleScores().length, T.SCORES.length);
ok("导入不会破坏谱单归属反查", T.SCORES.every((s) => T.setsOfScore(s).every((set) => T.setMembers(set).includes(s))));

// 落盘文件名与来源取值都按原应用口径：两条导入路径的来源都是「本地导入」，
// 原应用没有「相册导入」这个取值（ImportUtil + MainActivityKt 的图片回调）
eq("导入来源统一为本地导入", imported.source, "本地导入");
ok("落盘在 files/scores/ 下", imported.filePath.indexOf("files/scores/") === 0);
ok("落盘文件名符合 import_<标题净化>_<时间戳>.pdf",
  /^files\/scores\/import_.+_\d+\.pdf$/.test(imported.filePath));

const beforeAlbum = T.SCORES.length;
T.importScore("album", 6);
eq("相册导入同样落库", T.SCORES.length, beforeAlbum + 1);
eq("相册导入来源也是本地导入", T.SCORES[0].source, "本地导入");
eq("相册导入页数传入生效", T.SCORES[0].pages, 6);
eq("相册导入标题口径为「相册乐谱 · N 页」", T.SCORES[0].title, "相册乐谱 · 6 页");

// ---------------- 15.1 PDF 标题归一（ImportUtil.pdfTitle） ----------------
eq("去掉 .pdf 后缀", T.pdfImportTitle("肖邦_夜曲集.pdf"), "肖邦_夜曲集");
eq("后缀大小写不敏感", T.pdfImportTitle("Sonata.PDF"), "Sonata");
eq("只去掉结尾的后缀", T.pdfImportTitle("a.pdf.b.pdf"), "a.pdf.b");
eq("空显示名回退「导入乐谱」", T.pdfImportTitle(""), "导入乐谱");
eq("null 回退「导入乐谱」", T.pdfImportTitle(null), "导入乐谱");
eq("无后缀原名保留", T.pdfImportTitle("扫描件_0012"), "扫描件_0012");

// ---------------- 15.2 落盘文件名净化（ImportUtil.copyPdfToLocal） ----------------
// 非 [\w\u4e00-\u9fa5.-] 一律换 _，再截前 40 字，前缀 import_、后缀 _<时间戳>.pdf
eq("书名号被净化成下划线", T.importFileName("《夜曲集》Op.9", 1000), "import__夜曲集_Op.9_1000.pdf");
eq("中文与数字保留", T.importFileName("肖邦作品10", 2000), "import_肖邦作品10_2000.pdf");
eq("点与连字符保留", T.importFileName("a.b-c", 3000), "import_a.b-c_3000.pdf");
eq("空格被净化", T.importFileName("a b", 4000), "import_a_b_4000.pdf");
ok("超长标题截到 40 字", T.importFileName("长".repeat(80), 5000).indexOf("长".repeat(40)) > 0);
ok("超长标题不保留第 41 个字", T.importFileName("长".repeat(80), 5000).indexOf("长".repeat(41)) < 0);
eq("相册标题口径", T.albumImportTitle(6), "相册乐谱 · 6 页");

// ---------------- 15.3 相册合成结果归一（ImportUtil.imagesToPdf） ----------------
const emptyRes = T.imagesToPdf([]);
eq("一张都没选时的文案", emptyRes.error, "没有选择任何图片");
ok("一张都没选时不产出文件", emptyRes.path === null);

// 第 8、16、24 张（index 7/15/23）是模拟的坏图
ok("坏图判定按 8 张一循环", [7, 15, 23].every(T.albumImageBroken) && ![0, 6, 8].some(T.albumImageBroken));

const allBad = T.imagesToPdf([7, 15, 23]);
ok("全部读不出时不产出文件", allBad.path === null);
eq("全部读不出时报告原张数", allBad.sourceCount, 3);
eq("全部读不出时的文案", allBad.error, "所选 3 张图片都无法读取");

const partial = T.imagesToPdf([0, 1, 7]);
eq("跳过坏图后页数 = 可读张数", partial.pages, 2);
eq("部分失败仍产出文件", typeof partial.path, "string");
eq("部分失败时报告原始选择张数", partial.sourceCount, 3);
eq("部分失败的附加文案", partial.error, "有 1 张图片无法读取，已跳过");

const clean = T.imagesToPdf([0, 1, 2]);
ok("全部可读时没有附加文案", clean.error === null);
eq("全部可读时页数 = 张数", clean.pages, 3);
ok("合成产物落在 files/scores/ 下", clean.path.indexOf("files/scores/") === 0);

// ---------------- 15.4 分享文件名与正文（ShareUtil） ----------------
eq("分享文件名补 .pdf", T.shareFileName({ title: "月光" }), "月光.pdf");
eq("分享文件名替换 / 与 :", T.shareFileName({ title: "a/b:c" }), "a_b_c.pdf");
ok("分享文件名替换全部非法字符",
  T.shareFileName({ title: 'a\\b/c:d*e?f"g<h>i|j' }) === "a_b_c_d_e_f_g_h_i_j.pdf");
eq("标题为空时回退「乐谱」", T.shareFileName({ title: "   " }), "乐谱.pdf");
eq("无标题时回退「乐谱」", T.shareFileName({}), "乐谱.pdf");

const sumScore = {
  title: "《月光》", composer: "路德维希·范·贝多芬", type: "奏鸣曲", instrument: "钢琴",
  period: "古典", level: "高级", pages: 14, source: "Henle 原版",
};
eq("分享正文（含结尾署名）",
  T.shareSummary(sumScore),
  "《月光》\n路德维希·范·贝多芬\n奏鸣曲 · 钢琴 · 古典 · 高级\n共 14 页\n来源：Henle 原版\n—— 由「乐谱管理」分享");

const sumBare = { title: "无题", composer: "", type: "", instrument: "", period: "", level: "—", pages: 0, source: "—" };
eq("空字段整行剔除（作曲家/元信息/页数/来源）", T.shareSummary(sumBare), "无题\n—— 由「乐谱管理」分享");
eq("难度为 — 时不进元信息行",
  T.shareSummary({ title: "x", composer: "", type: "奏鸣曲", instrument: "", period: "", level: "—", pages: 0, source: "" }),
  "x\n奏鸣曲\n—— 由「乐谱管理」分享");
ok("分享正文不含未替换的占位符", T.shareSummary(sumScore).indexOf("undefined") < 0);

// ---------------- 15.5 文件实体判据（file.exists() && length() > 0） ----------------
// 内置 PDF 在启动时被预登记，开箱即可打开 / 分享
const bundled = T.SCORES.find((s) => s.assetPdf);
ok("存在带内置 PDF 的样例乐谱", !!bundled);
ok("内置 PDF 被登记为可用文件", T.pdfReady(bundled));
ok("内置 PDF 解析到 files/scores/ 下", T.resolvePdfPath(bundled) === `files/scores/${T.BUNDLED_PDF.name}`);
ok("没有 filePath / assetPdf 的乐谱不可打开", !T.pdfReady({ title: "x" }));
ok("文件大小为 0 视为不存在", !T.fileExists(T.FILE_STORE.get("__nope__")));

// ---------------- 16. 「我的」页元数据跳转 ----------------
// 这三行原先不可点（原型无 .link）/ 空回调（Compose onClick = {}），
// 点击后应跳到乐谱库并带上该维度筛选。
ok("作曲家维度有中文名词标签", T.DIM_LABEL.composer === "作曲家");
ok("曲目类型维度有中文名词标签", T.DIM_LABEL.type === "曲目类型");
ok("乐器维度有中文名词标签", T.DIM_LABEL.instrument === "乐器");

for (const dim of ["type", "instrument"]) {
  const top = T.topValue(dim);
  ok(`topValue(${dim}) 返回非空取值`, typeof top === "string" && top.length > 0);
  // 最高频取值必须真的在曲库里存在，且出现次数不少于任何其他取值
  const counts = {};
  for (const s of T.SCORES) counts[s[dim]] = (counts[s[dim]] || 0) + 1;
  const maxN = Math.max(...Object.values(counts));
  eq(`topValue(${dim}) 确为最高频取值`, counts[top], maxN);
}

// 跳转后可见列表应等于「仅按该取值筛选」的结果
for (const dim of ["type", "instrument"]) {
  const top = T.topValue(dim);
  T.state.filters = { composer: new Set(), type: new Set(), instrument: new Set() };
  T.state.filters[dim] = new Set([top]);
  eq(`按 ${dim}=${top} 筛选后条数正确`,
    T.visibleScores().length,
    T.SCORES.filter((s) => s[dim] === top).length);
}
// 复位，避免影响后续断言
T.state.filters = { composer: new Set(), type: new Set(), instrument: new Set() };

// ---------------- 17. 封面版式参数真正生效 ----------------
// 这组字段（coverTx/coverTy/coverTSize/coverTGap/coverSubX/coverSubY/coverDeco/coverFooter）
// 曾在两套实现里都只定义、不读取，导致样张里调好的排版完全没生效。
// 这里通过抓取 Canvas 的绘制调用来验证参数确实驱动了绘制。
{
  const cover = T.SCORES.find((s) => s.thumbKind === "cover");
  ok("存在封面型样张乐谱", !!cover);
  const draw = T.probeCoverDraw(cover);
  ok("封面绘制读取了 coverTx（文本左边界）", draw.texts.some((t) => t.args[1] === cover.coverTx * draw.kx));
  ok("封面绘制读取了 coverTy（标题基线）", draw.texts.some((t) => t.args[2] === cover.coverTy * draw.ky));
  // 字号应随 coverTSize 变化：把参数调大，绘制字号必须跟着变大
  const bigger = T.probeCoverDraw({ ...cover, coverTSize: cover.coverTSize * 2 });
  const sizeOf = (d) => Math.max(...d.texts.map((t) => t.size));
  ok("coverTSize 变大则绘制字号变大", sizeOf(bigger) > sizeOf(draw));
  // coverDeco="none" 应跳过装饰弧
  const noDeco = T.probeCoverDraw({ ...cover, coverDeco: "none" });
  ok("coverDeco='none' 时不绘制装饰弧", noDeco.arcs === 0);
  ok("默认会绘制装饰弧", draw.arcs > 0);
  // coverFooter 决定底部说明的对齐方式
  const footerOn = T.probeCoverDraw({ ...cover, coverFooter: true });
  const footerOff = T.probeCoverDraw({ ...cover, coverFooter: false });
  const alignsOf = (d) => d.texts.map((t) => t.align);
  ok("coverFooter=true 时底部说明右对齐", alignsOf(footerOn).includes("right"));
  ok("coverFooter=false 时底部说明左对齐", alignsOf(footerOff).includes("left"));
  // subY 超出参考系时应被夹回，避免底部说明掉出画布
  const bigSub = T.probeCoverDraw({ ...cover, coverSubY: 999 });
  const subTexts = bigSub.texts.filter((t) => t.text === cover.coverSub);
  ok("coverSubY 超界时被夹回画布内", subTexts.length > 0 && subTexts.every((t) => t.args[2] <= bigSub.h));
  // 任何文本都不应画出画布之外
  ok("封面文本均未溢出画布", draw.texts.every((t) => {
    const [x, y] = [t.args[1], t.args[2]];
    return x >= -0.5 && x <= draw.w + 0.5 && y >= -0.5 && y <= draw.h + 0.5;
  }), JSON.stringify(draw.texts.map((t) => [t.args[1], t.args[2]])));
}

// ---------------- 18. 封面渲染文件首页（对译 Thumb.kt / ThumbCache） ----------------
// 内置 PDF 现在是**一份真实可解析的 PDF 字节码**（tools/make_proto_pdf.py 生成），
// 所以「读首页 → 出位图 → Fit」这条链路在原型里是实的，不是声称。
{
  // 18.1 内置 PDF 的字节与结构
  ok("内置 PDF 带真实字节码", typeof T.BUNDLED_PDF.data === "string" && T.BUNDLED_PDF.data.length > 1000);

  const raw = Buffer.from(T.BUNDLED_PDF.data, "base64");
  const head = raw.slice(0, 8).toString("latin1");
  eq("字节以 %PDF- 开头", head.slice(0, 5), "%PDF-");
  ok("字节以 %%EOF 收尾", raw.toString("latin1").trimEnd().endsWith("%%EOF"));
  eq("尺寸口径仍是原应用那份（881869）", T.BUNDLED_PDF.size, 881869);

  // xref 表必须自洽：startxref 指向的字节能对上每个 obj 的偏移
  const lat = raw.toString("latin1");
  const xm = /startxref\s+(\d+)/.exec(lat);
  ok("字节里有 startxref", !!xm);
  const xp = parseInt(xm[1], 10);
  ok("startxref 指向 xref 表", lat.slice(xp, xp + 4) === "xref");
  const entries = [...lat.slice(xp).matchAll(/(\d{10}) (\d{5}) ([nf])/g)];
  eq("xref 表项数（含空闲项）", entries.length, 6);
  ok("xref 表每个在用项的偏移都指向对应的 obj", entries.every(([, off, , typ]) => {
    if (typ === "f") return true;
    const o = parseInt(off, 10);
    return /^\d+ 0 obj/.test(lat.slice(o, o + 14));
  }));

  // 18.2 内容流能解出来，且里面确实是「乐谱纸面」
  const u8 = Array.from(raw);
  const pdf = T.parsePdf(u8);
  const sb = T.pdfPageContent(pdf, 0);
  ok("能从字节里取到第 0 页内容流", !!sb);
  ok("内容流标了 FlateDecode", /FlateDecode/.test(sb.dict));

  const inflated = T.rawInflate(sb.bytes);
  ok("内容流能 inflate 出东西", inflated.length > 200);
  const stream = inflated.map((b) => String.fromCharCode(b)).join("");

  // 矩形（纸面底色 + 内描边）：`x y w h re`
  const reCount = (stream.match(/(^|\s)re(\s|$)/g) || []).length;
  ok("内容流里有矩形（纸面 + 描边）", reCount >= 2);
  // 线段：谱线 + 符干 + 小节线
  const mCount = (stream.match(/(^|\s)m(\s|$)/g) || []).length;
  const lCount = (stream.match(/(^|\s)l(\s|$)/g) || []).length;
  ok("内容流里有大量线段（五线谱）", mCount >= 25 && lCount >= 25);
  // 贝塞尔：符头
  ok("内容流里有贝塞尔曲线（符头）", /(\s)c(\s|$)/.test(stream));
  // 颜色算子
  ok("内容流里有 fill 颜色算子 rg", /(\s)rg(\s|$)/.test(stream));

  // 18.3 指令真的落到了 Canvas 上
  const ops = [];
  const ctx2 = {
    fillStyle: "", strokeStyle: "", lineWidth: 1,
    beginPath() {}, moveTo(x, y) { ops.push(["m", x, y]); },
    lineTo(x, y) { ops.push(["l", x, y]); },
    bezierCurveTo(a, b, c2, d, e, f) { ops.push(["c"]); },
    closePath() { ops.push(["z"]); },
    fill() { ops.push(["fill", this.fillStyle]); },
    stroke() { ops.push(["stroke", this.strokeStyle]); },
  };
  T.execPdfContent(stream, ctx2, (x, y) => [x, y]);
  const fills = ops.filter((o) => o[0] === "fill");
  const strokes = ops.filter((o) => o[0] === "stroke");
  const curves = ops.filter((o) => o[0] === "c");
  // 内容流里有多少落墨算子，就该有多少次对应调用 —— 少一次都是解析漏了算子
  const fOps = (stream.match(/(^|\s)f(\s|$)/g) || []).length;
  const sOps = (stream.match(/(^|\s)S(\s|$)/g) || []).length;
  eq("fill 调用次数 = 内容流里的 f 算子数", fills.length, fOps);
  eq("stroke 调用次数 = 内容流里的 S 算子数", strokes.length, sOps);
  ok("执行后有 fill 调用", fills.length > 0);
  ok("执行后有 stroke 调用", strokes.length > 0);
  ok("符头是真的贝塞尔曲线（不是折线近似）", curves.length > 0);
  const fillColors = new Set(fills.map((o) => o[1]));
  const strokeColors = new Set(strokes.map((o) => o[1]));
  ok("填充色里出现纸面暖白", [...fillColors].some((c) => /rgb\(25[0-5],24[0-9],24[0-9]\)/.test(c)));
  ok("描边色里出现谱线灰", [...strokeColors].some((c) => /rgb\(18[0-9],17[0-9],17[0-9]\)/.test(c)));
  // 描边色必须多于一种：谱线灰与墨色分开（若把 RG 写成 rg，这里只会有一种）
  ok("描边色至少两种（谱线灰 + 墨色）", strokeColors.size >= 2);
  ok("填充色与描边色不是同一个（纸面与墨色分开）",
    !([...fillColors].every((c) => strokeColors.has(c))));

  // 18.4 缩放口径：min(640 / 页宽, 1.4)
  eq("封面目标宽度 THUMB_WIDTH", T.THUMB_WIDTH, 640);
  eq("放大上限 THUMB_MAX_UPSCALE", T.THUMB_MAX_UPSCALE, 1.4);
  eq("A4 页宽（595）的缩放 = 640/595", Math.min(T.THUMB_WIDTH / 595, T.THUMB_MAX_UPSCALE), 640 / 595);
  ok("极窄页会被 1.4 上限夹住", Math.min(T.THUMB_WIDTH / 100, T.THUMB_MAX_UPSCALE) === 1.4);

  // 18.5 缓存口径（ThumbCache.MAX = 12582912，sizeOf = w*4*h）
  eq("封面缓存上限 12 MB", T.THUMB_CACHE_MAX, 12 * 1024 * 1024);
  ok("缓存按 w*4*h 估体积", T.THUMB_CACHE_MAX === 12582912);

  // 18.6 三态语义与 fallback 边界
  eq("三态：Idle=0", T.CoverLoad.Idle, 0);
  eq("三态：Loaded=1", T.CoverLoad.Loaded, 1);
  eq("三态：Failed=2", T.CoverLoad.Failed, 2);

  const bundled = T.SCORES.find((s) => s.assetPdf);
  const noFile = T.SCORES.find((s) => !s.assetPdf && !s.filePath);
  ok("存在带内置 PDF 的样例（走封面路径）", !!bundled);
  ok("存在没有文件的样例（走程序化绘制兜底）", !!noFile);

  // 有文件的乐谱：加载成功 → Loaded
  T.coverState.clear();
  T.THUMB_CACHE.clear();
  const stBundled = T.resolveCoverState(bundled);
  eq("内置 PDF 的封面加载态为 Loaded", stBundled, T.CoverLoad.Loaded);
  ok("加载后位图进了缓存", T.THUMB_CACHE.has(`files/scores/${T.BUNDLED_PDF.name}`));

  // 位图尺寸符合缩放口径：595 * 640/595 = 640
  const bmp = T.THUMB_CACHE.get(`files/scores/${T.BUNDLED_PDF.name}`);
  eq("位图宽度 = THUMB_WIDTH", bmp.width, Math.round(595 * Math.min(T.THUMB_WIDTH / 595, T.THUMB_MAX_UPSCALE)));

  // 没有文件的乐谱：renderFirstPage 返回 null（不假装能渲染）
  ok("没有文件时 renderFirstPage 返回 null", T.renderFirstPage(noFile) === null);
  ok("没有文件时也不进缓存", !T.THUMB_CACHE.has(T.resolvePdfPath(noFile)));

  // 用户导入的 PDF（有登记但无字节）：也算渲染失败 → Failed
  const fake = { id: 999001, title: "导入的谱", pages: 3, filePath: "files/scores/import_x_1.pdf" };
  T.FILE_STORE.set("files/scores/import_x_1.pdf", 4096);
  T.coverState.clear();
  eq("有文件但无字节可解时加载态为 Failed", T.resolveCoverState(fake), T.CoverLoad.Failed);
  ok("失败时不留缓存条目", !T.THUMB_CACHE.has("files/scores/import_x_1.pdf"));
  T.FILE_STORE.delete("files/scores/import_x_1.pdf");
}

// ---------------- 19. 阅读器顶栏文案四态（对译 ReaderBarText.kt，修正缺陷 ②③） ----------------
{
  eq("loading 副标题 = 读取中…", T.readerSubtitle("loading", 0), "读取中…");
  eq("error 副标题 = 无法打开", T.readerSubtitle("error", 0), "无法打开");
  // 缺陷 ③：空文档不得再显示「读取中…」
  eq("empty 副标题 = 没有页面（不再说读取中）", T.readerSubtitle("empty", 0), "没有页面");
  ok("空文档副标题 != 读取中", T.readerSubtitle("empty", 0) !== T.READER_LOADING_TEXT);
  eq("ready 副标题 = 共 N 页", T.readerSubtitle("ready", 14), "共 14 页");
  // ready 但页数为 0（不该出现，仍要有确定行为）
  eq("ready 且页数为 0 时退化为「没有页面」", T.readerSubtitle("ready", 0), "没有页面");

  // 四态两两不重复
  const subs = [
    T.readerSubtitle("loading", 0), T.readerSubtitle("error", 0),
    T.readerSubtitle("empty", 0), T.readerSubtitle("ready", 14),
  ];
  eq("四种状态的副标题互不重复", new Set(subs).size, 4);

  // 缺陷 ②：徽标只在就绪态且页数 > 0 时出现
  eq("loading 无徽标", T.readerBadge("loading", 0), null);
  eq("error 无徽标", T.readerBadge("error", 0), null);
  eq("empty 无徽标", T.readerBadge("empty", 0), null);
  eq("ready 有徽标", T.readerBadge("ready", 14), "14 页");
  eq("ready 但页数为 0 时无徽标", T.readerBadge("ready", 0), null);

  // 就绪态下副标题与徽标「说的是同一件事」——这是原应用的重复之处，
  // 本实现保留视觉（原样），但断言它们口径一致，避免真的打架。
  ok("就绪态副标题与徽标同指页数",
    T.readerSubtitle("ready", 14).includes("14") && T.readerBadge("ready", 14).includes("14"));
}

// ---------------- 20. 详情页操作区（B 组：分享与查看并排 / 去掉预览编辑角标） ----------------
{
  const score = T.SCORES[0];
  T.openDetail(score);
  const html = els.get("detailContent").innerHTML;

  ok("详情页同时有「查看乐谱」与「分享」两个动作",
    html.includes("查看乐谱") && html.includes("分享"));
  ok("两个动作并排在同一行容器 .actionrow 里",
    /class="actionrow"[\s\S]*查看乐谱[\s\S]*分享[\s\S]*<\/div>/.test(html));
  ok("「查看乐谱」走 openpdf（与「更多」里的旧入口同一动作）",
    /data-act="openpdf"[^>]*>[\s\S]*?查看乐谱/.test(html));
  // 预览图右上角原先叠了一个编辑角标，与「更多 → 编辑元数据」重复
  ok("详情页不再渲染预览图编辑角标", !html.includes("editbadge"));

  // 「更多」菜单：打开乐谱已提升为详情页按钮，不再重复占位
  S.moreTarget = score;
  T.renderMoreSheet();
  const sheet = els.get("sheetBody").innerHTML;
  ok("「更多」菜单去掉了「打开乐谱」", !sheet.includes("打开乐谱"));
  ok("「更多」菜单仍保留「编辑元数据」", sheet.includes("编辑元数据"));
  S.moreTarget = null;
}

// ---------------- 21. 底部导台焦点气泡（滑动动画的前提） ----------------
{
  const nav = els.get("bottomnav");
  T.renderBottomNav();
  const before = nav.innerHTML;
  ok("整条导台只有一颗焦点气泡", (before.match(/navbubble/g) || []).length === 1);
  ok("三个页签都在", (before.match(/class="navitem"/g) || []).length === 3);

  // 切页签时若整段 innerHTML 重刷，新元素没有「过渡起点」，
  // left 不会插值，动画就退化成闪现——所以结构必须保持不变，只改 --i。
  const keep = S.tab;
  S.tab = "me";
  T.renderMain();
  eq("切页签后导台结构不重建", nav.innerHTML, before);
  S.tab = keep;
  T.renderMain();
}

// ---------------- 22. 编辑页：作曲家自动补全 ----------------
{
  const score = T.SCORES[0];
  T.openSheet("edit", score);
  const html = els.get("sheetBody").innerHTML;

  // 常用作曲家原先摊成一排 chip，占掉半屏；现改为输入框 + 候选列表。
  // 其余字段（曲目类型/乐器…）仍保留 chip 行，这里只断言作曲家这一项被换掉。
  ok("作曲家字段改成带 data-ac 的输入框", /data-ac="composer"/.test(html));
  const composerField = html.slice(html.indexOf('data-ac="composer"'));
  const nextField = composerField.indexOf('data-edit="type"');
  ok("作曲家字段不再挂建议 chip", !composerField.slice(0, nextField).includes('data-act="sugg"'));
  ok("未聚焦时不渲染候选列表", !html.includes("aclist"));
  ok("字段标签不再写「点选下方常用姓氏」", !html.includes("点选下方常用姓氏"));

  // 打开编辑页必须把候选状态清干净，否则会带着上一次的关键词进来
  eq("打开编辑页时候选状态已复位", [S.acField, S.acQuery, S.acIndex], [null, "", -1]);

  // 空关键词 = 原来的「常用姓氏」快捷入口，收进了输入框
  eq("空关键词给出 6 条常用候选", T.composerSuggestions("").length, 6);

  // 输入「贝」应命中贝多芬；高亮区间落在「姓」那一段，而不是「路德维希」里的某个字
  const beethoven = "路德维希·范·贝多芬";
  ok("按中文输入能匹配到作曲家", T.composerSuggestions("贝").includes(beethoven));
  const r = T.composerMatchRange(beethoven, "贝");
  eq("匹配区间指向姓氏段", r, [beethoven.indexOf("贝"), beethoven.indexOf("贝") + 1]);
  ok("匹配区间确实命中「贝」", beethoven.slice(r[0], r[1]) === "贝");
  // 「维」在「路德维希」里也出现过，但姓氏段优先，不该回退到前段匹配
  const rv = T.composerMatchRange(beethoven, "希");
  eq("姓氏段优先于前段包含匹配", rv, [beethoven.indexOf("希"), beethoven.indexOf("希") + 1]);

  // 无匹配时返回空数组（渲染层据此给出「没有匹配」提示）
  eq("无匹配返回空候选", T.composerSuggestions("zzz").length, 0);

  // 候选上限 6 条：列表是绝对定位浮层，再多会把下半屏全盖住
  ok("候选数量不超过 6 条", T.composerSuggestions("").length <= 6);

  // 展开候选：列表挂进 DOM，且命中项被高亮标记
  S.acField = "composer";
  S.acQuery = "贝";
  T.renderEditSheet();
  const opened = els.get("sheetBody").innerHTML;
  ok("展开后渲染候选列表", opened.includes("aclist"));
  ok("候选项带选中语义 data-act=\"acpick\"", opened.includes('data-act="acpick"'));
  ok("命中片段被 mark 高亮", opened.includes("<mark>"));

  // 退化成「无匹配」提示时仍是绝对定位浮层，不会把下方字段顶走
  S.acQuery = "zzz";
  T.renderEditSheet();
  const none = els.get("sheetBody").innerHTML;
  ok("无匹配时给提示而不是空浮层", none.includes("没有匹配的作曲家"));
  ok("无匹配提示也走浮层（不挤动文档流）", none.includes('class="aclist"'));

  T.closeSheet();
  eq("关闭弹层后候选状态一并清掉", [S.acField, S.acQuery, S.acIndex], [null, "", -1]);
}

// ---------------- 23. 表单密度（真机反馈「太密集」） ----------------
{
  const css = html;
  ok("字段上下间距放大到 16px", /\.field\{padding:16px 0 4px;\}/.test(css));
  ok("字段标签字号放大到 13px", /\.field label\{display:block;font-size:13px/.test(css));
  ok("输入框正文字号放大到 15px", /font-size:15px;font-family:var\(--font\)/.test(css));
  ok("建议 chip 同步放大到 13px", /\.suggchip\{\s*padding:7px 12px[^}]*font-size:13px/.test(css));
}

// ---------------- 24. 列表快捷入口与底栏尺寸（按参考图） ----------------
{
  const css = html;
  // 真机反馈「快捷入口太小」
  ok("快捷入口命中区放大到 34px", /\.cardacts \.ca\{\s*width:34px;height:34px/.test(css));
  ok("快捷入口图标放大到 18px", /\.cardacts \.ca svg\{width:18px;height:18px;\}/.test(css));
  ok("快捷入口间距放到 4px（防相邻误触）", /\.cardacts\{display:flex;align-items:center;gap:4px/.test(css));

  // 底栏：字体/焦点/高度/边距四项
  ok("页签文字放大到 12px", /\.navitem span\{font-size:12px/.test(css));
  ok("页签图标放大到 23px", /\.navitem svg\{width:23px;height:23px;\}/.test(css));
  ok("焦点气泡拉宽成 64×46 胶囊", /width:64px;height:46px;border-radius:23px/.test(css));
  ok("导台左右边距放大到 16px", /left:16px;right:16px;bottom:12px/.test(css));
  // 圆角必须等于高度一半（56/2=28），两端才是半圆收口而不是圆角矩形
  ok("导台内边距 +2px（高度 56）", /padding:6px;\s*\n\s*border-radius:28px/.test(css));
  ok("内容避让空间同步放大到 96px", /\.with-nav \.scroll\{padding-bottom:96px;\}/.test(css));
}

// ---------------- 25. 真机演示模式（状态栏对照） ----------------
{
  const css = html;
  ok("提供叠层状态栏的演示开关", /\.phone\.simstatus \.statusbar\{/.test(css));
  ok("叠层状态栏 z-index 高于应用界面", /z-index:100/.test(css));
  ok("演示条有「状态栏对照」入口", /data-demo="simstatus"/.test(css));
  ok("演示条有「编辑乐谱」入口", /data-demo="edit"/.test(css));
  // 阅读器打开时状态栏转深色，否则白底压在纸面上读不清
  ok("阅读器状态栏有深色变体", /\.statusbar\.dark/.test(css));
}

// ---------------- 汇总 ----------------
console.log(`\n通过 ${pass} 项，失败 ${fails.length} 项`);
if (fails.length) {
  console.log("\n失败明细：");
  fails.forEach((f, i) => console.log(`${i + 1}. ${f}`));
  process.exit(1);
}
console.log("全部通过 ✅");
