// 屏幕样式自检：屏幕类按 id 取的控件/贴图，以及按编号批量注册的槽位语义（module_N 等），
// 样式文档都必顶声明。
//
// 为什么需要它：AE2 的 ScreenStyle.getWidget(id) 与 getImage(id) 在文档里找不到那个键时
// 直接 throw IllegalStateException——抛在屏幕构造期，玩家看到的是「打开 GUI 时客户端被断开」
// （NeoForge 的 ClientPayloadHandler 拿不到屏幕实例）。姊妹项目 AE2 Pattern Disk 踩过一次
// （屏幕加了控件而样式文档没同步，玩家一点开 GUI 就被踢）。槽位那一条同样是硬错误，但完全不报错：
// AE2 只给「文档声明过的语义」摆坐标，没声明的停在 (0,0) 或被隐藏后再也回不来。
//
// 用法：
//   node tools/check-screen-widgets.cjs [仓库根] [AE2 jar]
// AE2 jar 用来补读 includes 链里的 AE2 公共段落（common/*.json）。不传则自己找：
// 先看 build/moddev/*LegacyClasspath.txt（构建真正在用的那个 jar），再退到 gradle 缓存。
//
// 退出码：0 = 全部已声明；1 = 有控件/贴图/槽位未声明；2 = AE2 段落未取到，有部分无法判定。
// 2 不是通过——它意味着这次没能核完，别把它当绿灯。
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const repo = process.argv[2] || '.';
const screensDir = path.join(repo, 'src/main/resources/assets/ae2/screens');
const ourDir = path.join(screensDir, 'ae2modularcells');
const clientJavaDir = path.join(repo, 'src/client/java');
const mainJavaDir = path.join(repo, 'src/main/java');

// 样式文档里声明过的槽位语义条数核对次数（见下面 checkSlotFamilies() 的调用处）。

// 仓库根写错、或客户端源集搬家时，先给出用法再退出：本脚本的退出码 1 表示「有控件未声明」，
// 而抛 ENOENT 同样以 1 结束，操作者与 CI 会把「路径打错」误读成「样式文档缺键」。
// 这种情况按「没能核完」处理（2，不算通过）。
if (!fs.existsSync(clientJavaDir)) {
  console.log('用法：node tools/check-screen-widgets.cjs [仓库根] [AE2 jar]');
  console.log(`找不到 ${clientJavaDir} —— 仓库根写错了，或者客户端源集不在 src/client/java。`);
  console.log('屏幕样式自检：未能开始（未核完，按 2 退出）');
  process.exit(2);
}

/**
 * 找 AE2 jar：优先用构建自己写下的 classpath（build/moddev/*LegacyClasspath.txt），
 * 那里是构建真正在用的那个 jar，不会拿错变体（Forge 的 appeng/appliedenergistics2-forge
 * 与本项目的 NeoForge ae2 不是同一个包）。
 */
// AE2 jar 的缓存坐标。本项目的 compileOnly 走 Maven（org.appliedenergistics），开发运行期走 CurseForge
// 坐标；两个坐标下的目录名不同，而不同来源的 jar 里 assets/ae2/screens/common/*.json 是完全一样的，
// 所以三个都试。只按一个坐标找会在换依赖来源后静默退到「无法判定」。
const AE2_CACHE_COORDS = [
  ['org.appliedenergistics', 'appliedenergistics2'],
  ['maven.modrinth', 'ae2'],
  ['curse.maven', 'applied-energistics-2-223794'],
];
const AE2_JAR_RE = /^(appliedenergistics2|ae2)-\d[^/\\]*\.jar$/;

/** 是 AE2 的主 jar（排除 sources/javadoc 那些同名件）。 */
function isAe2Jar(name) {
  return !/-sources\.jar$|-javadoc\.jar$/.test(name) && (AE2_JAR_RE.test(name) || /applied[-_]?energistics.*\.jar$/i.test(name));
}

/** 从 libs.versions.toml 读声明的 AE2 版本，用来优先挑对应版本的 jar。 */
function declaredAe2Version(root) {
  try {
    const toml = fs.readFileSync(path.join(root, 'gradle/libs.versions.toml'), 'utf8');
    const m = toml.match(/^\s*ae2\s*=\s*"([^"]+)"/m);
    return m ? m[1] : null;
  } catch {
    return null;
  }
}

function findAe2Jar(root) {
  for (const name of ['clientLegacyClasspath.txt', 'serverLegacyClasspath.txt']) {
    const p = path.join(root, 'build/moddev', name);
    if (!fs.existsSync(p)) continue;
    const hit = fs
      .readFileSync(p, 'utf8')
      .split(';')
      .find((e) => isAe2Jar(path.basename(e)) && fs.existsSync(e));
    if (hit) return hit;
  }
  const wanted = declaredAe2Version(root);
  const roots = [process.env.GRADLE_USER_HOME, path.join(os.homedir(), '.gradle')].filter(Boolean);
  const dirs = [];
  for (const root of roots) {
    for (const [group, artifact] of AE2_CACHE_COORDS) {
      const dir = path.join(root, 'caches/modules-2/files-2.1', group, artifact);
      if (fs.existsSync(dir)) dirs.push(dir);
    }
  }
  // 两轮：先在所有缓存位置找与 libs.versions.toml 声明版本同名的那一份，找不到再退到任意版本。
  // 不能一轮就「命中即返回」：缓存目录之间与内部都不保证版本顺序，先撞上旧版就把判定依据
  // 从本项目声明的构建换成了别人的构建。
  if (wanted) {
    for (const dir of dirs) {
      const exact = fs.existsSync(path.join(dir, wanted)) ? walkForAe2Jar(path.join(dir, wanted)) : null;
      if (exact) return exact;
    }
  }
  for (const dir of dirs) {
    const found = walkForAe2Jar(dir);
    if (found) return found;
  }
  return null;
}

/**
 * 在坐标目录下找第一份 AE2 主 jar。可以传 artifact 根（含版本层），也可以直接传某个版本目录。
 *
 * 写成递归而不是写死「版本/哈希/文件」三层：缓存布局不止一种（maven 是三层，有的坐标只有两层），
 * 写死层数就会在某一类坐标上静默返回 null，然后退到别的版本去。
 */
function walkForAe2Jar(dir, depth = 0) {
  if (depth > 4) return null;
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return null;
  }
  for (const e of entries) {
    if (e.isFile() && isAe2Jar(e.name)) return path.join(dir, e.name);
  }
  for (const e of entries) {
    if (!e.isDirectory()) continue;
    const hit = walkForAe2Jar(path.join(dir, e.name), depth + 1);
    if (hit) return hit;
  }
  return null;
}

const ae2Jar = process.argv[3] || findAe2Jar(repo);
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ae2-common-'));
// 解包出来的 AE2 公共段落是临时件，进程退出时统一清掉：脚本有多条 exit 路径（0/1/2），
// 逐个补清理迟早漏一个，挂 exit 一次就够。
process.on('exit', () => {
  try {
    fs.rmSync(tmp, { recursive: true, force: true });
  } catch {
    // 清理失败不改退出码：临时目录失收是小事，误报检查失败才是大事。
  }
});
let ae2Available = false;
if (ae2Jar && fs.existsSync(ae2Jar)) {
  try {
    const entries = execFileSync('unzip', ['-Z1', ae2Jar], { encoding: 'utf8' })
      .split('\n')
      .filter((f) => f.startsWith('assets/ae2/screens/') && f.endsWith('.json'));
    for (const entry of entries) {
      const dest = path.join(tmp, entry.replace('assets/ae2/screens/', ''));
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      fs.writeFileSync(dest, execFileSync('unzip', ['-p', ae2Jar, entry], { maxBuffer: 1 << 24 }));
    }
    ae2Available = true;
  } catch (e) {
    // 没有 unzip / jar 损坏：退化成「无法判定」，不要抛栈——那看起来像脚本坏了，而不是环境缺件。
    console.log(`（读取 AE2 资源失败：${String(e.message).split('\n')[0]}）`);
  }
}

// 屏幕类 / 面板类 → 它使用的样式文档。面板类由终端屏幕用同一份文档添加控件，故同映射。
//
// 本仓当前还没有屏幕类，所以这里是空表：实现阶段每加一个屏幕就往里登记一条，否则脚本会把它报成
// 「引用了控件但没登记样式文档映射，无法判定」。
const SCREEN_TO_STYLE = {
  'EnergyModuleTerminalScreen.java': 'energy_module_terminal.json',
};

function resolveInclude(fromFile, inc) {
  const direct = path.resolve(path.dirname(fromFile), inc);
  if (fs.existsSync(direct)) return direct;
  const ae2Root = path.join(tmp, inc.replace(/^\.\.\//, ''));
  if (fs.existsSync(ae2Root)) return ae2Root;
  const ae2Rel = path.join(tmp, inc);
  if (fs.existsSync(ae2Rel)) return ae2Rel;
  return null;
}

/** 递归收集样式文档（含 includes）里 widgets 与 images 两个键空间的 id。 */
function loadStyle(file, seen = new Set()) {
  const abs = path.resolve(file);
  const empty = { widgets: new Set(), images: new Set(), unresolved: [] };
  if (seen.has(abs) || !fs.existsSync(abs)) return empty;
  seen.add(abs);
  let json;
  try {
    json = JSON.parse(fs.readFileSync(abs, 'utf8'));
  } catch (e) {
    // 文档写坏了：打印文件名并把它算进 unresolved（调用方据此计入「无法判定」），
    // 不要抛栈——那看起来像脚本坏了，而不是某份样式文档坏了。
    console.log(`  （样式文档读取失败：${path.relative(repo, abs)} — ${String(e.message).split('\n')[0]}）`);
    return { widgets: new Set(), images: new Set(), unresolved: [abs] };
  }
  const widgets = new Set(Object.keys(json.widgets || {}));
  const images = new Set(Object.keys(json.images || {}));
  const slots = new Set(Object.keys(json.slots || {}));
  const unresolved = [];
  for (const inc of json.includes || []) {
    const target = resolveInclude(abs, inc);
    if (!target) {
      unresolved.push(inc);
      continue;
    }
    const sub = loadStyle(target, seen);
    for (const w of sub.widgets) widgets.add(w);
    for (const i of sub.images) images.add(i);
    for (const s of sub.slots) slots.add(s);
    for (const u of sub.unresolved) unresolved.push(u);
  }
  return { widgets, images, slots, unresolved };
}

// 屏幕类里按 id 取控件的写法：字面量（widgets.add*/style.getWidget/getImage）与
// 运行时拼接的前缀（"modePanel" + i）。
const WIDGET_METHODS =
  'add|addButton|addCheckbox|addNumberEntryWidget|addScrollBar|addBackgroundPanel';
const refs = new Map();
(function scan(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      scan(p);
      continue;
    }
    if (!e.name.endsWith('.java')) continue;
    const src = fs.readFileSync(p, 'utf8');
    const widgets = new Set();
    const images = new Set();
    const dynamic = [];
    if (/addOpenPriorityButton\s*\(/.test(src)) widgets.add('openPriority');
    // 接收者不写死成 any.widgets：ScreenStyle 那边叫 style.getWidget / style.getImage。
    for (const m of src.matchAll(
      new RegExp(`[\\w.]+\\.(?:${WIDGET_METHODS})\\(\\s*"([A-Za-z0-9_]+)"`, 'g'),
    )) {
      widgets.add(m[1]);
    }
    for (const m of src.matchAll(/[\w.]+\.(?:add|getWidget)\(\s*"([A-Za-z0-9_]+)"\s*\+/g)) {
      dynamic.push(m[1]);
    }
    for (const m of src.matchAll(/[\w.]+\.getImage\(\s*"([A-Za-z0-9_]+)"/g)) {
      images.add(m[1]);
    }
    for (const m of src.matchAll(/[\w.]+\.setTooltipAreaEnabled\(\s*"([A-Za-z0-9_]+)"/g)) {
      widgets.add(m[1]);
    }
    for (const prefix of dynamic) widgets.delete(prefix);
    // 屏幕用到槽位语义时，槽位那一套也得核（见下面的家族核对）。
    const usesSlotSemantics = /EnergyModuleSlotSemantics\./.test(src);
    if (widgets.size || images.size || dynamic.length || usesSlotSemantics) {
      refs.set(e.name, { widgets, images, dynamic, usesSlotSemantics });
    }
  }
})(clientJavaDir);

// 批量注册的槽位语义：`SlotSemantics.register(<modid> + ":<前缀>_" + ...)`——取出前缀，再拿去对文档里的
// `slots` 条目。条数不用猜：MAX_ROWS 与 MODULES_PER_ROW 在本仓是字面量常量，扫到就用；**同一个名字扫到两个
// 不同值就不当字面量**（宁可记「无法判定」，也不能拿最后一个值去核）。
const slotFamilies = new Set();
const maxRowsValues = new Set();
const modulesPerRowValues = new Set();
(function scanSemantics(dir) {
  if (!fs.existsSync(dir)) return;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      scanSemantics(p);
      continue;
    }
    if (!e.name.endsWith('.java')) continue;
    const src = fs.readFileSync(p, 'utf8');
    for (const m of src.matchAll(
      /SlotSemantics\.register\(\s*[\w.]+\.MOD_ID\s*\+\s*":([A-Za-z0-9_]+?)_"\s*\+/g,
    )) {
      slotFamilies.add(m[1]);
    }
    for (const m of src.matchAll(/\bMAX_ROWS\s*=\s*(\d+)\s*;/g)) maxRowsValues.add(Number(m[1]));
    for (const m of src.matchAll(/\bMODULES_PER_ROW\s*=\s*(\d+)\s*;/g)) {
      modulesPerRowValues.add(Number(m[1]));
    }
  }
})(mainJavaDir);
const declaredMaxRows = maxRowsValues.size === 1 ? [...maxRowsValues][0] : null;
const declaredModulesPerRow = modulesPerRowValues.size === 1 ? [...modulesPerRowValues][0] : null;

/**
 * 槽位家族核对。返回 { fails, oks, und, skipped }：skipped 表示一个家族都没扫到（调用方必须记「无法判定」，
 * 不能当通过——注册写法一改就会变成静默失效）。
 */
function checkSlotFamilies(haveS) {
  if (!slotFamilies.size) return { fails: [], oks: [], und: 0, skipped: true };
  if (declaredMaxRows === null || declaredModulesPerRow === null) {
    return { fails: [], oks: [], und: slotFamilies.size, skipped: false };
  }
  const fails = [];
  const oks = [];
  for (const family of slotFamilies) {
    const isTitle = family.includes('title');
    const count = isTitle ? declaredMaxRows : declaredMaxRows * declaredModulesPerRow;
    // 严格按「家族名_编号」结尾匹配：不能用 includes，否则 module_ 会把 module_components_35 也算进来
    // （编号对得上，漏声明就被掩盖了）。
    const pattern = new RegExp(':' + family + '_(\\d+)$');
    const nums = [...haveS]
      .map((k) => k.match(pattern))
      .filter(Boolean)
      .map((m) => Number(m[1]))
      .sort((a, b) => a - b);
    const gaps = [];
    for (let i = 0; i < count; i += 1) if (!nums.includes(i)) gaps.push(i);
    if (gaps.length) {
      fails.push(
        `槽位语义 "${family}": 代码里可用 0..${count - 1}` +
          `（MAX_ROWS=${declaredMaxRows}、MODULES_PER_ROW=${declaredModulesPerRow}），` +
          `本样式文档的 slots 里缺 ${gaps.map((i) => family + '_' + i).join(', ')}`,
      );
    } else {
      oks.push(`ok 槽位语义 ${family}_0..${count - 1}（共 ${count} 个）`);
    }
  }
  return { fails, oks, und: 0, skipped: false };
}

let failed = 0;
let checked = 0;
let undecidable = 0;
let slotChecks = 0;
let slotReferenced = false;
console.log(`AE2 资源：${ae2Available ? ae2Jar : '未取到（includes 无法解析，相关项无法判定）'}`);

for (const [screen, { widgets: wantW, images: wantI, dynamic, usesSlotSemantics }] of refs) {
  const styleName = SCREEN_TO_STYLE[screen];
  if (!styleName) {
    undecidable += wantW.size + wantI.size + dynamic.length + (usesSlotSemantics ? 1 : 0);
    console.log(`?    ${screen}: 引用了控件但没登记样式文档映射，无法判定`);
    continue;
  }
  checked += 1;
  const { widgets: haveW, images: haveI, slots: haveS, unresolved } = loadStyle(
    path.join(ourDir, styleName),
  );
  const canJudge = unresolved.length === 0;

  for (const id of wantW) {
    if (haveW.has(id)) continue;
    if (!canJudge) undecidable += 1;
    else {
      failed += 1;
      console.log(`FAIL ${screen} -> ${styleName}: 控件 "${id}" 未声明`);
    }
  }
  for (const id of wantI) {
    if (haveI.has(id)) continue;
    if (!canJudge) undecidable += 1;
    else {
      failed += 1;
      console.log(`FAIL ${screen} -> ${styleName}: 贴图 "${id}" 未在 images 里声明`);
    }
  }
  for (const prefix of dynamic) {
    const nums = [...haveW]
      .filter((w) => w.startsWith(prefix))
      .map((w) => Number(w.slice(prefix.length)))
      .filter((n) => Number.isInteger(n))
      .sort((a, b) => a - b);
    if (!nums.length) {
      if (!canJudge) undecidable += 1;
      else {
        failed += 1;
        console.log(`FAIL ${screen} -> ${styleName}: 动态控件 "${prefix}*" 一个都没声明`);
      }
      continue;
    }
    // 编号必须从 0 起连续：漏一个就在运行时抛「缺这个控件」。
    const gaps = [];
    for (let i = 0; i < nums[nums.length - 1]; i += 1) if (!nums.includes(i)) gaps.push(i);
    if (gaps.length) {
      failed += 1;
      console.log(
        `FAIL ${screen} -> ${styleName}: "${prefix}" 编号不连续，缺 ${gaps
          .map((i) => prefix + i)
          .join(', ')}`,
      );
    } else {
      console.log(
        `  ok ${screen} -> ${styleName}: ${prefix}0..${nums[nums.length - 1]}（共 ${nums.length} 个；` +
          `代码侧若增档，这里要跟着加）`,
      );
    }
  }
  if (usesSlotSemantics) {
    slotReferenced = true;
    const outcome = checkSlotFamilies(haveS);
    if (outcome.skipped) {
      // 屏幕引用了槽位语义，但一个家族都没扫到——注册写法改了、或类搬了位置。不允许静默跳过。
      undecidable += 1;
      console.log(`?    ${screen} -> ${styleName}: 用了槽位语义但代码里一个注册点都没扫到，无法判定`);
    } else if (outcome.und) {
      undecidable += outcome.und;
      console.log('  （槽位语义的条数无法判定：MAX_ROWS / MODULES_PER_ROW 不是字面量常量）');
    } else {
      slotChecks += 1;
      for (const msg of outcome.fails) {
        failed += 1;
        console.log(`FAIL ${screen} -> ${styleName}: ${msg}`);
      }
      for (const msg of outcome.oks) console.log('  ' + msg);
    }
  }

  if (unresolved.length) {
    // 两类都会进这里：includes 指向的文件找不到，以及文档本身读坏/解析失败（loadStyle 会把文件名塞进
    // unresolved）。标签写成「未解析或无法读取」，别让坏文档看起来像 includes 的毛病；路径转相对，
    // 免得把本机绝对路径写进日志。
    const shown = [...new Set(unresolved)].map((u) => path.relative(repo, u));
    console.log(`  （${styleName} 未解析或无法读取: ${shown.join(', ')}）`);
  }
}

// 槽位语义的条数核对：代码里能用到 module_0..N（N = MAX_ROWS × MODULES_PER_ROW - 1）、
// module_components_0..N、module_title_0..MAX_ROWS-1，文档一条不漏才给得上坐标
// （2026 这轮的 TALL/FULL 档位就吃过这个亏：文档只写了 6 条带，第 7 条起图标与模块行全错位）。
// 核对对象是「本屏自己那份样式文档（含 includes）」的 slots，不用全局集合：别的文档里同名键会掩盖本文件的缺失。
if (slotChecks === 0 && slotFamilies.size && !slotReferenced) {
  undecidable += 1;
  console.log('  （槽位语义一处都没核到：没有任何屏幕类引用它，或 SCREEN_TO_STYLE 没登记）');
}

console.log(
  `屏幕样式自检：${checked} 个屏幕类，${
    failed ? `${failed} 处未声明` : '全部已声明'
  }${undecidable ? `，${undecidable} 处无法判定` : ''}`,
);if (failed) process.exit(1);
// 没核完就不算通过：AE2 段落缺失时，我们文档里缺的键同样会被漏掉（2026-10-09 那次事故的形态）。
process.exit(undecidable ? 2 : 0);
