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
  SURNAME_INITIAL, ALIAS,
  normalizeDraft, metaLine,
};
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

// ---------------- 汇总 ----------------
console.log(`\n通过 ${pass} 项，失败 ${fails.length} 项`);
if (fails.length) {
  console.log("\n失败明细：");
  fails.forEach((f, i) => console.log(`${i + 1}. ${f}`));
  process.exit(1);
}
console.log("全部通过 ✅");
