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
      " aiTitleOnly, promptFromFileName, looksRelated, reprefillPrompt, prefillPrompt," +
      " searchFallbackEligible, buildSearchQuery, simSearch, applySearchFallback," +
      " searchOn: () => searchOn, setSearchOn: (v) => { searchOn = !!v; }," +
      " api: {" +
      "  setTyped(i,k,v){ if(!typed[i]) typed[i]={}; typed[i][k]=v; }," +
      "  clearTyped(i){ delete typed[i]; }," +
      // 「跳到第 i 条并重预填」，模拟翻页对输入框的影响。
      // 不直接调 go() —— 那还会动 draft/存档，把用例的焦点搅浑；
      // 这里只想测输入框那一条线。
      "  reprefillFor(i){ idx=i; reprefillPrompt(); }," +
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

// ================ 清空字段（空值覆盖，第十四阶段）================
// 起因：Jackson 的曲库里有值本身是错的，光靠「自填一个新值」治不了 ——
// 他要的是「这一格就是空的」。而 `FixProposal` 那套里**空值代表「不改」**，
// 所以「清空」在数据结构上根本表达不出来，得单独走一条路。
//
// 三条不许踩的线：
// 1. 清空 ≠ 撤回自填。撤回自填是「我填错了，退回规则/AI 的值」；
//    清空是「连原值都不要」。后者严格更强。
// 2. 清空必须**落存档**，否则翻页回来原值又冒出来，白清。
// 3. 导出的那一格必须真的是空。`p.title ?? r.title` 这种写法在 p 为空时
//    会回落成**原值** —— 界面上显示「已清空」，导出的 CSV 里却还是老值，
//    而且用户看不出来。
api.clearAll();
const R3 = mod.ROWS[3];
const FN3 = R3.fileName;
// 先确认这条的曲名原值非空，否则「清空」看不出效果
ok("第 4 条有原始曲名可清", !!(R3.title && R3.title.trim()));

// --- 清空后候选值里没有它，原值不再冒头 ---
api.setTyped(3, "title", "");
const e3 = mod.entryOf(3);
eq("清空后候选值里没有曲名", e3.p.title, undefined);
ok("清空后 blanked 里有曲名", e3.blanked.has("title"));
ok("清空后不算「有建议」", !mod.FIELDS.some(f => f.k === "title" && e3.p[f.k] != null));

// --- 清空要落存档，翻回来还是空的 ---
api.setSaved(FN3, { title: "" });
eq("存档里的空曲名回填后仍是空的", mod.entryOf(3).p.title, undefined);
ok("存档里的空曲名仍算已清空", mod.entryOf(3).blanked.has("title"));

// --- 清空算已采纳，导出时会真的写成空 ---
const row3 = mod.buildCsv().split("\r\n")[4];   // 0=表头，1..4 = 第 1..4 条
eq("清空后导出的曲名是空", row3.split(",")[1], "");
ok("导出的曲名不等于原值", row3.split(",")[1] !== R3.title);
api.clearAll();

// --- 清空调性时两列一起清 ---
api.setTyped(3, "key", "");
const row3b = mod.buildCsv().split("\r\n")[4].split(",");
eq("清空后 keysf 是空", row3b[13], "");
eq("清空后 keymi 是空", row3b[14], "");
api.clearAll();

// --- 撤回自填与清空是两件事 ---
api.setTyped(3, "composer", "某人");
eq("自填后候选值是自填值", mod.entryOf(3).p.composer, "某人");
ok("自填不算清空", !mod.entryOf(3).blanked.has("composer"));
api.setTyped(3, "composer", "");
eq("改成清空后候选值没了", mod.entryOf(3).p.composer, undefined);
ok("改成清空后 blanked 里有它", mod.entryOf(3).blanked.has("composer"));
api.clearTyped(3);
// 撤回之后回到「规则/AI 说什么就是什么」，而不是「空」
ok("撤回自填不是清空", !mod.entryOf(3).blanked.has("composer"));
api.clearAll();

// --- 只有清空的行也算「有改动」---
// 只判候选值的话，这种行会被判成「无需改动，直接翻下一条」，
// 而它明明是要写东西出去的。
api.setTyped(3, "title", "");
ok("只有清空的行也算有改动", mod.hasChange(mod.entryOf(3)));
api.clearAll();

// 对照：直接喂一个「候选值全空 + 无清空」的 entry。
// 不能拿 ROWS 里的真条目来对照 —— 这份样例数据每一条都至少有一条规则建议
// （标题清洗或作曲家补全），根本不存在「空手」的行，那样对不出来。
ok(
  "候选值全空且无清空时不算有改动",
  !mod.hasChange({ r: R3, p: {}, ov: {}, ai: false, aiAlt: {}, blanked: new Set() }),
);
ok(
  "候选值全空但有清空时算有改动",
  mod.hasChange({ r: R3, p: {}, ov: {}, ai: false, aiAlt: {}, blanked: new Set(["title"]) }),
);

// ================ AI 输入框预填文件名 ================
// 起因：Jackson 说「ai 输入框预填文件名」。他要填 549 条，
// 每条都手敲一遍曲名是不可接受的开销。
//
// 为什么取文件名而不是曲名：文件名是**用户自己当初的命名**，往往比库里
// 那个被截断/串味的曲名信息更全（「七月的草原 合唱」这类带编制的尾巴都在文件名里）。
ok("预填会去扩展名", mod.promptFromFileName("七月的草原 合唱.pdf") === "七月的草原 合唱");
ok("预填会清洗标题噪声", mod.promptFromFileName("《月光》_pdf.pdf") === "月光");
ok("预填对没扩展名的原样", mod.promptFromFileName("茉莉花") === "茉莉花");
eq("预填对空文件名安全", mod.promptFromFileName(""), "");
eq("预填对 null 安全", mod.promptFromFileName(null), "");

// 翻页换预填 vs 保住用户手写的 —— 这一对是本次最容易写错的地方。
// `prefillPrompt` 的守卫是「有字就不动」，而翻页那一刻框里正好有上一条的预填，
// 直接调它会被自己挡死（输入框永远停在第一条）。所以翻页走 `reprefillPrompt`，
// 它按「框里的字是不是我上次预填的那串」判断，而不是按「框里空不空」。
const aiBox = documentStub.getElementById("aiprompt");
eq("开场预填的是第一条的文件名", aiBox.value, mod.promptFromFileName(mod.ROWS[0].fileName));
api.reprefillFor(1);
eq("翻页后换成第二条的文件名", aiBox.value, mod.promptFromFileName(mod.ROWS[1].fileName));
api.reprefillFor(0);
eq("翻回来又换回第一条", aiBox.value, mod.promptFromFileName(mod.ROWS[0].fileName));

// 用户改写过的：翻页不许动它
aiBox.value = "月光奏鸣曲 升c小调";
api.reprefillFor(1);
eq("用户手写的提示词翻页也不动", aiBox.value, "月光奏鸣曲 升c小调");

// 用户删空的：翻页也不许替他填回来（删空也是「动过」）
aiBox.value = "";
api.reprefillFor(0);
eq("用户删空后不许擅自填回来", aiBox.value, "");

// ================ 「只认出曲名」必须是单独一种状态 ================
// 起因：真机上 gemini-3.8-flash 给《七月的草原》《您花开的样子》返回
// 「曲名填了、其余四项全空」的对象。这个形态跟「认出来了但信息少」
// 长得一模一样，意思却相反 —— 曲名往往只是把用户刚敲进去的字抄了一遍。
// 用户看到蓝条「识别完成」+ 孤零零一行曲名，会以为 AI 认出了这首曲子。
ok(
  "只填曲名能被认出来",
  mod.aiTitleOnly({ title: "您花开的样子", composer: "", instr: "", genre: "", key: "" }),
);
ok(
  "曲名之外还有别的值就不算只有曲名",
  !mod.aiTitleOnly({ title: "月光", composer: "贝多芬", instr: "", genre: "", key: "" }),
);
ok(
  "只有曲名也不算 unknown（unknown 是另一个分支）",
  !mod.aiTitleOnly({ unknown: true }),
);
ok("null 不算只有曲名", !mod.aiTitleOnly(null));

// ================ 联网兜底（方案 B：App 自己搜，资料喂回模型） ================
// 规格七条见 fix-stepper-demo.html 的「联网兜底」注释块。这里钉住纯逻辑部分。

// 1. 触发资格：unknown 和 titleOnly 都算「没认出」，认出了不搜
ok("unknown 该触发联网", mod.searchFallbackEligible({ unknown: true }));
ok(
  "titleOnly 该触发联网",
  mod.searchFallbackEligible({ title: "七月的草原", composer: "", instr: "", genre: "", key: "" }),
);
ok(
  "认出来了不该触发（哪怕字段不全）",
  !mod.searchFallbackEligible({ title: "月光", composer: "贝多芬", instr: "", genre: "", key: "" }),
);
ok("null 不触发", !mod.searchFallbackEligible(null));

// 2. 搜索词：框里有字用框里的（可能带「合唱」这类编制尾巴），空了退回文件名清洗
eq("搜索词优先用框里的字", mod.buildSearchQuery("  七月的草原 合唱 ", "x.pdf"), "七月的草原 合唱");
eq("框空了退回文件名", mod.buildSearchQuery("", "《七月的草原》_pdf"), "七月的草原");
eq("框里全是空格也退回文件名", mod.buildSearchQuery("   ", "月光.pdf"), "月光");

// 3. 模拟搜索的命中/对不上/没命中
ok("七月的草原搜得到", !!mod.simSearch("七月的草原"));
eq("七月的草原有两条资料", mod.simSearch("七月的草原").hits.length, 2);
ok(
  "边境的小鸟搜得到但对不上（reject）",
  !!mod.simSearch("边境的小鸟") && mod.simSearch("边境的小鸟").reject === true,
);
ok("查无此曲返回 null", mod.simSearch("完全不存在的曲子xyz") === null);

// 4. 兜底结果：认出 → via:'search' + 来源，且不再算 titleOnly
const fb1 = mod.applySearchFallback("七月的草原");
eq("二问采信了资料里的作曲家", fb1.result.composer, "内蒙古民歌");
eq("结果挂 via=search 标记", fb1.result.via, "search");
eq("来源两条", fb1.sources.length, 2);
ok("联网认出的结果不再算 titleOnly", !mod.aiTitleOnly(fb1.result));

// 5. 来源截断：4 条命中只亮 3 条 —— 列一排链接没人看
const fb2 = mod.applySearchFallback("茉莉芬芳");
eq("来源最多三条", fb2.sources.length, 3);

// 6. 搜不到 → 老实说 unknown + searched，绝不硬编
const fb3 = mod.applySearchFallback("查无此曲xyz");
eq("搜不到仍是 unknown", fb3.result.unknown, true);
eq("挂着 searched 标记（措辞要跟没搜过分开）", fb3.result.searched, true);

// 7. 资料对不上 → 仍是 unknown + searchNote 说明原因，来源照样亮
const fb4 = mod.applySearchFallback("边境的小鸟");
eq("资料对不上仍是 unknown", fb4.result.unknown, true);
ok("有 searchNote 说明为什么不敢下结论", !!fb4.result.searchNote);
eq("对不上的来源也亮出来", fb4.sources.length, 1);

// 8. 开关：默认开，可关；关掉后自动兜底不跑（黄条出手动键），开关本身可复原
ok("联网兜底默认开", mod.searchOn());
mod.setSearchOn(false);
eq("关掉后 searchOn 为 false", mod.searchOn(), false);
mod.setSearchOn(true);
eq("再开回来", mod.searchOn(), true);

// 7.5 1.14 真机案例：资料里写着作曲家，二问必须把归属抄出来，而不是再抄一遍曲名
const fb5 = mod.applySearchFallback("Bella siccome un angelo");
eq("咏叹调搜到了归属信息", fb5.result.composer, "Gaetano Donizetti");
eq("曲名照资料原样", fb5.result.title, "Bella siccome un angelo");
ok("作曲家有了就不再算 titleOnly", !mod.aiTitleOnly(fb5.result));
eq("来源亮出来", fb5.sources.length, 3);

// ---------------- 汇总 ----------------
console.log(`\n通过 ${pass} 项，失败 ${fails.length} 项`);
if (fails.length) {
  fails.forEach((f) => console.error("  ✗ " + f));
  process.exit(1);
}
console.log("全部通过 ✓");
