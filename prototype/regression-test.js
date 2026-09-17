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
    style: {},
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
    querySelector() { return null; },
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

const els = new Map();
const document = {
  getElementById(id) {
    if (!els.has(id)) els.set(id, makeEl(id));
    return els.get(id);
  },
  querySelectorAll() { return []; },
  querySelector() { return null; },
  addEventListener() {},
  createElement(tag) { return makeEl(tag); },
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
  DIM_LABEL, topValue,
  probeCoverDraw,
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
eq("样例乐谱数量", T.SCORES.length, 42);
eq("谱单数量", T.SETS.length, 5);
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
const lisztCount = T.SCORES.filter((s) => s.composer === "弗朗茨·李斯特").length;
ok("样例中含肖邦作品", chopinCount > 0);
ok("样例中含李斯特作品", lisztCount > 0);

S.filters.composer = new Set([chopinFull]);
eq("单作曲家筛选", T.visibleScores().length, chopinCount);

S.filters.composer = new Set([chopinFull, "弗朗茨·李斯特"]);
eq("同维度多选取并集", T.visibleScores().length, chopinCount + lisztCount);

const chopinNocturne = T.SCORES.filter((s) => s.composer === chopinFull && s.type === "夜曲").length;
S.filters.type = new Set(["夜曲"]);
eq("跨维度取交集", T.visibleScores().length, chopinNocturne);

S.filters.type = new Set(["夜曲", "圆舞曲"]);
const chopinNightWaltz = T.SCORES.filter(
  (s) => s.composer === chopinFull && (s.type === "夜曲" || s.type === "圆舞曲"),
).length;
eq("同维度并集 + 跨维度交集组合", T.visibleScores().length, chopinNightWaltz);
resetState();

// ---------------- 6. 分面计数：同维度已选不参与计数 ----------------
S.pending.composer = new Set([chopinFull]);
eq(
  "分面计数受其它维度约束",
  T.facetCount("type", "夜曲"),
  T.SCORES.filter((s) => s.composer === chopinFull && s.type === "夜曲").length,
);
eq(
  "同维度其它选项计数不被自身已选清零",
  T.facetCount("type", "练习曲"),
  T.SCORES.filter((s) => s.composer === chopinFull && s.type === "练习曲").length,
);
ok("上一条计数大于 0（否则无法继续多选）", T.facetCount("type", "练习曲") > 0);

// 关键回归：在 type 维度已选「夜曲」时，type 维度下的「练习曲」计数不应归零
S.pending.type = new Set(["夜曲"]);
eq(
  "本维度已选条件不参与本维度计数（否则多选后全部归零）",
  T.facetCount("type", "练习曲"),
  T.SCORES.filter((s) => s.composer === chopinFull && s.type === "练习曲").length,
);
ok("上一条计数仍大于 0", T.facetCount("type", "练习曲") > 0);
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
S.pending.composer = new Set([chopinFull, "弗朗茨·李斯特"]);
eq(
  "待应用条件总数按暂存态计算",
  T.pendingResultCount(),
  chopinCount + lisztCount,
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
// 导入原先只 toast、不落库；现在必须真正写进 SCORES，并带上可分享的 filePath
const beforeCount = T.SCORES.length;
const beforeTopId = T.SCORES[0].id;
T.importScore(false, 1);
eq("导入后乐谱数 +1", T.SCORES.length, beforeCount + 1);
const imported = T.SCORES[0];
ok("新导入的乐谱置顶", imported.id > beforeTopId);
ok("新乐谱分配了全新 id", !T.SCORES.slice(1).some((s) => s.id === imported.id));
ok("新乐谱带 filePath（分享/打开 PDF 可用）", typeof imported.filePath === "string" && imported.filePath.length > 0);
ok("新乐谱登记了添加时间", imported.dateAdded > 0);
ok("新乐谱 thumbSeed 可参与缩略图绘制的取值范围", Number.isInteger(imported.thumbSeed));
eq("导入来源标记为本地导入", imported.source, "本地导入");
eq("导入后可被可见列表检索到（无筛选时）", T.visibleScores().length, T.SCORES.length);

const beforeAlbum = T.SCORES.length;
T.importScore(true, 6);
eq("相册导入同样落库", T.SCORES.length, beforeAlbum + 1);
eq("相册导入来源标记正确", T.SCORES[0].source, "相册导入");
eq("相册导入页数传入生效", T.SCORES[0].pages, 6);
ok("导入不会破坏谱单归属反查", T.SCORES.every((s) => T.setsOfScore(s).every((set) => T.setMembers(set).includes(s))));

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

// ---------------- 汇总 ----------------
console.log(`\n通过 ${pass} 项，失败 ${fails.length} 项`);
if (fails.length) {
  console.log("\n失败明细：");
  fails.forEach((f, i) => console.log(`${i + 1}. ${f}`));
  process.exit(1);
}
console.log("全部通过 ✅");
