/**
 * fix-stepper-demo.html 的业务逻辑回归测试。
 *
 * 为什么单独一个文件：`regression-test.js` 只加载 scoreapp-prototype.html，
 * 逐条校对页（fix-stepper-demo.html）此前**完全没有自动化覆盖** ——
 * 而它是这一版功能的权威规格。它一旦跑偏，Compose 侧照着抄也跟着错，
 * 所以必须自己有一份断言。
 *
 * 做法：把 <script> 抽出来丢进一个最小 DOM 桩里求值，
 * 再把里面几个纯业务函数取出来单独打。与 regression-test.js 同一套路。
 */
const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "fix-stepper-demo.html"), "utf8");
const code = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)]
  .map((m) => m[1])
  .join("\n");

// ---------------- 最小 DOM 桩 ----------------
// 页面脚本在顶层就会执行 el('prev').onclick = ... 这类绑定，
// 所以每个元素都得是个「能读能写」的真对象，不能是 Proxy 返回的假函数。
const elStub = () => ({
  onclick: null,
  oninput: null,
  onkeydown: null,
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
    "Blob",
    "URL",
    code +
      "\n;return { cleanTitle, looksRelated, parseKey, keyLabel, keyToSfMi," +
      " propose, entryOf, hasChange, buildCsv, ROWS, FIELDS, HEADER," +
      " api: {" +
      "  setTyped(i,k,v){ if(!typed[i]) typed[i]={}; typed[i][k]=v; }," +
      "  clearTyped(i){ delete typed[i]; }," +
      "  setAi(i,obj){ aiInjected[i]=obj; }," +
      "  setSaved(fn,obj){ saved[fn]=obj; }," +
      "  savedOf(fn){ return saved[fn]; }," +
      "  clearAll(){ for(const k of Object.keys(saved)) delete saved[k];" +
      "              for(const k of Object.keys(typed)) delete typed[k];" +
      "              for(const k of Object.keys(aiInjected)) delete aiInjected[k]; draft.clear(); }," +
      "  clearUsed(){ for(const k of Object.keys(used)) delete used[k]; }," +
      "  usedOf(k){ return used[k]||[]; }," +
      "  rememberUsed" +
      " } };",
  )(
    documentStub,
    windowStub,
    windowStub.localStorage,
    function () {},
    { createObjectURL: () => "", revokeObjectURL() {} },
  );
} catch (e) {
  console.error("加载脚本失败：", e.message);
  process.exit(1);
}

let pass = 0;
const fails = [];
function ok(name, cond) {
  if (cond) pass++;
  else fails.push(name);
}
function eq(name, actual, expected) {
  if (actual === expected) pass++;
  else fails.push(`${name} —— 期望 ${JSON.stringify(expected)}，实得 ${JSON.stringify(actual)}`);
}

// ---------------- cleanTitle ----------------
eq("cleanTitle 去 _pdf", mod.cleanTitle("《月光》_pdf"), "月光");
eq("cleanTitle 去书名号", mod.cleanTitle("《萱草花》"), "萱草花");
eq("cleanTitle 去扫描水印", mod.cleanTitle("[Z-Library] 彩云追月.pdf"), "彩云追月");
eq("cleanTitle 去开头序号", mod.cleanTitle("1_月半小夜曲"), "月半小夜曲");
eq("cleanTitle 去尾部调号", mod.cleanTitle("七月的草原G"), "七月的草原");
eq("cleanTitle 循环剥重复后缀", mod.cleanTitle("x_pdf.pdf"), "x");
eq("cleanTitle 空串安全", mod.cleanTitle(""), "");
eq("cleanTitle 无噪声时原样", mod.cleanTitle("茉莉花"), "茉莉花");

// ---------------- looksRelated ----------------
// 核心：必须**先清洗再比**。不清洗的话《月光》_pdf 归一化成「月光pdf」，
// 跟「月光奏鸣曲」怎么都比不上，一整批正常数据会被误报成「对不上」。
ok("looksRelated 清洗后能对上", mod.looksRelated("《月光》_pdf", "月光奏鸣曲"));
ok("looksRelated 带水印能对上", mod.looksRelated("[Z-Library] 彩云追月.pdf", "彩云追月"));
ok("looksRelated 子串放行", mod.looksRelated("月光", "月光奏鸣曲"));
ok("looksRelated 反向子串放行", mod.looksRelated("月光奏鸣曲", "月光"));
ok("looksRelated 完全相同放行", mod.looksRelated("茉莉花", "茉莉花"));
ok("looksRelated 原本没曲名放行", mod.looksRelated("", "任意"));
ok("looksRelated 真的对不上要拦", !mod.looksRelated("萱草花", "茉莉花"));
ok(
  "looksRelated 清洗后仍对不上要拦",
  !mod.looksRelated("《萱草花》_pdf", "茉莉花"),
);
// 中英译名之间没有共同字符 —— 守卫认不出，如实拦下，由用户判断
ok(
  "looksRelated 中英译名之间如实拦下",
  !mod.looksRelated("月光奏鸣曲", "Piano Sonata No.14, Op.27 No.2"),
);

// ---------------- 调性 ----------------
// parseKey 返回 {letter, acc, minor}；keyToSfMi 才给出 forScore 的 keysf/keymi。
const KEY_CASES = [
  ["C", 0, 0], ["am", 0, 1],
  ["bB", -2, 0], ["bbm", -5, 1],
  ["F#", 6, 0], ["c#m", 4, 1],
  ["bE", -3, 0], ["ebm", -6, 1],
];
KEY_CASES.forEach(([txt, sf, mi]) => {
  const p = mod.keyToSfMi(txt);
  ok(`keyToSfMi(${txt}) 能解析`, !!p);
  if (p) {
    eq(`keyToSfMi(${txt}) keysf`, p.keysf, sf);
    eq(`keyToSfMi(${txt}) keymi`, p.keymi, mi);
  }
});

// 全部 30 种组合：显示写法 → 解析回来，必须回到同一个 (keysf, keymi)
// 显示写法由 keyLabel 从一个代表字符串产出；这里直接用 keyLabel 的往返。
let roundTripBad = [];
const SHORT_FOR = {
  "0,0": "C", "0,1": "am", "-2,0": "bB", "-5,1": "bbm",
  "6,0": "F#", "4,1": "c#m", "-3,0": "bE", "-6,1": "ebm",
};
Object.entries(SHORT_FOR).forEach(([k, short]) => {
  const back = mod.keyToSfMi(short);
  const [sf, mi] = k.split(",").map(Number);
  if (!back || back.keysf !== sf || back.keymi !== mi) {
    roundTripBad.push(`${short}→${k} 实得 ${JSON.stringify(back)}`);
  }
});
// 再把全部 30 种组合用「小调走关系大调」的思路穷举一遍：
// 大调 15 个根音 + 小调 15 个根音，各自 keyToSfMi 后不报 null 且 keymi 正确
const MAJOR_ROOTS = ["C", "G", "D", "A", "E", "B", "F#", "F", "bB", "bE", "bA", "bD", "bG", "bC"];
const MINOR_ROOTS = ["a", "e", "b", "f#", "c#", "g#", "d#", "a#", "d", "g", "c", "f", "bb", "eb", "ab"];
let keyBad = [];
MAJOR_ROOTS.forEach((r) => {
  const p = mod.keyToSfMi(r);
  if (!p || p.keymi !== 0) keyBad.push(`大调 ${r} → ${JSON.stringify(p)}`);
});
MINOR_ROOTS.forEach((r) => {
  const p = mod.keyToSfMi(r + "m");
  if (!p || p.keymi !== 1) keyBad.push(`小调 ${r}m → ${JSON.stringify(p)}`);
});
ok("大调 14 个根音全部可解析", keyBad.filter((x) => x.startsWith("大调")).length === 0);
ok("小调 15 个根音全部可解析", keyBad.filter((x) => x.startsWith("小调")).length === 0);
if (keyBad.length) console.error("调性解析失败：", keyBad.join(" "));
ok("已知写法往返无损", roundTripBad.length === 0);
if (roundTripBad.length) console.error("往返失败：", roundTripBad.join(" "));

// 认不出来的要返回 null，绝不硬猜
ok("认不出的调名返回 null", mod.keyToSfMi("很抒情") === null);
ok("空串返回 null", mod.keyToSfMi("") === null);

// Jackson 定的显示写法：降号前缀小写 b，升号后缀，大调大写、小调小写带 m
eq("显示 bB（降号在字母前）", mod.keyLabel("bB"), "bB");
eq("显示 c#m（小调小写带 m）", mod.keyLabel("c#m"), "c#m");
eq("显示 F#（升号在后）", mod.keyLabel("F#"), "F#");
eq("显示 C（无升降号）", mod.keyLabel("C"), "C");
eq("显示 am（小调）", mod.keyLabel("am"), "am");
eq("降号小调 bbm", mod.keyLabel("bbm"), "bbm");
// 降号小调的字母要小写、降号仍在前面：ebm → bem（不是 ebm，也不是 E♭m）
eq("降号小调 ebm 规范成 bem", mod.keyLabel("ebm"), "bem");
eq("降号大调 bE", mod.keyLabel("bE"), "bE");

// ---------------- 自填 + 用过的值（第十三阶段）----------------
// 起因：Jackson 说「标签词条需要可以自行填写」。校对页此前只有「勾/不勾」，
// 值只能来自规则和 AI —— 而 AI 永不碰 标签/来源，规则也给不出来，
// 于是那两列永远是死的。补上自填后，值的来源变成三路：
//   规则 < AI < **用户自填**（自填最高）
// 存档因此从「记字段名」改成「记字段→值」，否则手打的值翻页就没了。
const api = mod.api;
const R1 = mod.ROWS[1];              // 标题带 _pdf 噪声、有作曲家、没类型
const FN1 = R1.fileName;
const csvRow = () => mod.buildCsv().split("\r\n")[2];   // 0=表头，1=第 1 条，2=第 2 条
api.clearAll();

eq("导出的表头是真实 15 列", mod.buildCsv().split("\r\n")[0], mod.HEADER.join(","));

// 规则本来的样子（用来对比「自填之后确实变了」）
const base1 = mod.entryOf(1).p;
eq("规则把第 2 条的 _pdf 洗掉", base1.title, "月半小夜曲");
ok("规则给第 2 条提了类型", base1.genre === "Nocturne");
ok("规则和 AI 都给不出来源", mod.propose(R1).p.ref === null && mod.entryOf(1).p.ref === null);

// 自填优先级最高：压过 AI，也压过规则
api.setAi(1, { title: "月半小夜曲（AI）", composer: "河合奈保子", tag: "声乐", genre: "流行歌曲", key: "am" });
eq("AI 注入后曲名是 AI 给的", mod.entryOf(1).p.title, "月半小夜曲（AI）");
api.setTyped(1, "composer", "河合奈保子（自填）");
eq("自填压过 AI", mod.entryOf(1).p.composer, "河合奈保子（自填）");
api.setTyped(1, "title", "月半小夜曲 Live 版");
eq("自填压过规则", mod.entryOf(1).p.title, "月半小夜曲 Live 版");
api.clearTyped(1);
eq("撤回自填后退回 AI 那一层", mod.entryOf(1).p.title, "月半小夜曲（AI）");

// 标签/来源这类「没人会给建议」的字段，靠自填才填得上
api.setTyped(1, "ref", "网络下载");
eq("自填能把来源填上", mod.entryOf(1).p.ref, "网络下载");
api.setTyped(1, "label", "考级曲目");
eq("自填能改标签", mod.entryOf(1).p.label, "考级曲目");
api.clearTyped(1);

// 存档存的是「字段 → 值」：翻回旧条时屏幕上必须是**当时存进去的那个值**，
// 不能显示成重新算出来的建议 —— 否则屏幕上和导出里是两个值。
api.clearAll();
api.setSaved(FN1, { title: "当年手打的名字" });
eq("存档值回填进显示", mod.entryOf(1).p.title, "当年手打的名字");
eq("导出的第 2 条用存档值", csvRow().split(",")[1], "当年手打的名字");
// 没采纳的字段保持 CSV 原值
eq("没采纳时不改曲名", csvRow().split(",")[1] !== R1.title, true);
api.clearAll();
eq("清空后导出回到原值", csvRow().split(",")[1], R1.title);

// 自填的调性要落到 keysf / keymi 两列
api.setSaved(FN1, { key: "c#m" });
eq("自填调性写进 keysf", csvRow().split(",")[13], "4");
eq("自填调性写进 keymi", csvRow().split(",")[14], "1");
api.clearAll();

// 旧存档（数组，只有字段名没有值）：进度不能丢，值按重算的建议写
api.setSaved(FN1, ["title", "composer"]);
eq("旧存档仍能导出清洗后的曲名", csvRow().split(",")[1], "月半小夜曲");
eq("旧存档仍能导出规则给的作曲家", csvRow().split(",")[4], "Pyotr Ilyich Tchaikovsky");
api.clearAll();

// -------- 用过的值（填一次，往后一直能点）--------
api.clearUsed();
api.rememberUsed("label", "教学");
api.rememberUsed("label", "考级");
api.rememberUsed("label", "教学");
eq("用过的值去重、最近用的在前", JSON.stringify(api.usedOf("label")), JSON.stringify(["教学", "考级"]));
api.rememberUsed("label", "  考级  ");
eq("用过的值会 trim", api.usedOf("label")[0], "考级");
const beforeBlank = api.usedOf("label").length;
api.rememberUsed("label", "   ");
eq("空白不进词表", api.usedOf("label").length, beforeBlank);
for (let n = 0; n < 12; n++) api.rememberUsed("label", "值" + n);
eq("词表最多留 8 个", api.usedOf("label").length, 8);
eq("最新填的排在最前", api.usedOf("label")[0], "值11");
api.rememberUsed("ref", "网络下载");
eq("词表按字段分开存", api.usedOf("ref").length, 1);
api.clearUsed();

// ---------------- 汇总 ----------------
console.log(`\n通过 ${pass} 项，失败 ${fails.length} 项`);
if (fails.length) {
  fails.forEach((f) => console.error("  ✗ " + f));
  process.exit(1);
}
console.log("全部通过 ✓");
