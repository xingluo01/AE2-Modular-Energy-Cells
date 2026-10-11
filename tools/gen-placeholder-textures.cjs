// 占位贴图生成器：三档能源模块方块的「线框」占位纹理。
//
// 为什么要有这个脚本而不是直接画图：
//   正式美术到位之前，方块必须有贴图才能进世界（缺贴图是紫黑格，看不出连接关系是否生效）。
//   这里按 AE2 合成单元（crafting cube）那套贴图分工生成占位件——连接边框与内面各自一张，
//   四张一套 × 三档。等正式贴图来了，替换同名文件即可，模型与代码一行都不用改。
//
// 贴图口径（对齐 AE2 `appeng/client/render/crafting` 的用法，见 docs/ARCHITECTURE.md）：
//   *_ring_corner  合成单元的 3×3 角块取样这张图的角部；四边都画线，任何一角都取得到线
//   *_ring_hor     上下各 3px 横带：横向条纹在立方体顶/底面取样的是贴图顶或底那 3px
//   *_ring_ver     左右各 3px 竖带：同理，纵向条纹取到贴图左或右那 3px
//   *_cell_face    内面（未被连接遮住的那部分）铺的底纹
//   `CubeBuilder.getStandardUv` 会把 y/z 坐标换算成 V（y1=0 取贴图底、y1=13 取贴图顶），
//   所以占位件把线条画在四边而不是只画一边——反正不是成品美术，先保证任何取样窗口都有线。
//
// 用法：node tools/gen-placeholder-textures.cjs [仓库根]
// 只写 assets/ae2modularcells/textures/block/ 下这 12 个文件，重复执行结果一致（幂等）。
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const SIZE = 16;

/** 三档的配色。占位件只求「一眼能分出档位」，不追求好看。 */
const TIERS = [
    { id: 'basic', base: '#3f4f6d', frame: '#8fa8cf' },
    { id: 'dense', base: '#2f5f55', frame: '#7fc9b6' },
    { id: 'superdense', base: '#4b3a6b', frame: '#b49ae0' },
];

const CRC_TABLE = (() => {
    const table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) {
            c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        }
        table[n] = c;
    }
    return table;
})();

function crc32(buffer) {
    let c = 0xffffffff;
    for (let i = 0; i < buffer.length; i++) {
        c = CRC_TABLE[(c ^ buffer[i]) & 0xff] ^ (c >>> 8);
    }
    return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length, 0);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body), 0);
    return Buffer.concat([length, body, crc]);
}

/** 把 RGBA 像素打成 PNG（8 位、颜色类型 6、每行 filter 0）。 */
function encodePng(width, height, pixels) {
    const stride = width * 4;
    const raw = Buffer.alloc((stride + 1) * height);
    for (let y = 0; y < height; y++) {
        raw[y * (stride + 1)] = 0;
        for (let x = 0; x < stride; x++) {
            raw[y * (stride + 1) + 1 + x] = pixels[y * stride + x];
        }
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(width, 0);
    ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8; // bit depth
    ihdr[9] = 6; // color type: RGBA
    ihdr[10] = 0; // deflate
    ihdr[11] = 0; // adaptive filtering
    ihdr[12] = 0; // no interlace
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        pngChunk('IHDR', ihdr),
        pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
        pngChunk('IEND', Buffer.alloc(0)),
    ]);
}

function parseColor(hex) {
    return [
        parseInt(hex.slice(1, 3), 16),
        parseInt(hex.slice(3, 5), 16),
        parseInt(hex.slice(5, 7), 16),
        255,
    ];
}

/** 一块 16×16 的透明画布。 */
function createCanvas() {
    return { pixels: Buffer.alloc(SIZE * SIZE * 4) };
}

function setPixel(canvas, x, y, color) {
    if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) {
        return;
    }
    const offset = (y * SIZE + x) * 4;
    canvas.pixels[offset] = color[0];
    canvas.pixels[offset + 1] = color[1];
    canvas.pixels[offset + 2] = color[2];
    canvas.pixels[offset + 3] = color[3];
}

function fillRect(canvas, x, y, width, height, color) {
    for (let dy = 0; dy < height; dy++) {
        for (let dx = 0; dx < width; dx++) {
            setPixel(canvas, x + dx, y + dy, color);
        }
    }
}

/** 内面：底色 + 一圈 1px 深色边 + 正中一条横线，明确是占位而非成品。 */
function faceTexture(tier) {
    const canvas = createCanvas();
    const base = parseColor(tier.base);
    const frame = parseColor(tier.frame);
    const shade = [base[0] >> 1, base[1] >> 1, base[2] >> 1, 255];

    fillRect(canvas, 0, 0, SIZE, SIZE, base);
    fillRect(canvas, 0, 0, SIZE, 1, frame);
    fillRect(canvas, 0, SIZE - 1, SIZE, 1, frame);
    fillRect(canvas, 0, 0, 1, SIZE, frame);
    fillRect(canvas, SIZE - 1, 0, 1, SIZE, frame);
    fillRect(canvas, 2, 2, SIZE - 4, SIZE - 4, shade);
    fillRect(canvas, 2, 7, SIZE - 4, 2, frame);
    return canvas;
}

/** 角部：3px 的四边框，保证任意 3×3 取样窗口里都有线。 */
function ringCornerTexture(tier) {
    const canvas = createCanvas();
    const frame = parseColor(tier.frame);
    const shade = [frame[0] >> 1, frame[1] >> 1, frame[2] >> 1, 255];

    fillRect(canvas, 0, 0, SIZE, 3, frame);
    fillRect(canvas, 0, SIZE - 3, SIZE, 3, frame);
    fillRect(canvas, 0, 0, 3, SIZE, frame);
    fillRect(canvas, SIZE - 3, 0, 3, SIZE, frame);
    fillRect(canvas, 1, 1, SIZE - 2, 1, shade);
    fillRect(canvas, 1, SIZE - 2, SIZE - 2, 1, shade);
    return canvas;
}

function ringHorizontalTexture(tier) {
    const canvas = createCanvas();
    const frame = parseColor(tier.frame);
    fillRect(canvas, 0, 0, SIZE, 3, frame);
    fillRect(canvas, 0, SIZE - 3, SIZE, 3, frame);
    return canvas;
}

function ringVerticalTexture(tier) {
    const canvas = createCanvas();
    const frame = parseColor(tier.frame);
    fillRect(canvas, 0, 0, 3, SIZE, frame);
    fillRect(canvas, SIZE - 3, 0, 3, SIZE, frame);
    return canvas;
}

function main() {
    const repoRoot = process.argv[2] || '.';
    const outDir = path.join(repoRoot, 'src', 'main', 'resources', 'assets', 'ae2modularcells',
            'textures', 'block');
    fs.mkdirSync(outDir, { recursive: true });

    const generated = [];
    for (const tier of TIERS) {
        const block = `${tier.id}_energy_cell_module`;
        const files = [
            [`${block}_face.png`, faceTexture(tier)],
            [`${block}_ring_corner.png`, ringCornerTexture(tier)],
            [`${block}_ring_hor.png`, ringHorizontalTexture(tier)],
            [`${block}_ring_ver.png`, ringVerticalTexture(tier)],
        ];
        for (const [name, canvas] of files) {
            const target = path.join(outDir, name);
            fs.writeFileSync(target, encodePng(SIZE, SIZE, canvas.pixels));
            generated.push(path.relative(repoRoot, target).split(path.sep).join('/'));
        }
    }

    console.log(`占位贴图已生成 ${generated.length} 个文件（${TIERS.length} 档 × 4 张）：`);
    for (const file of generated) {
        console.log('  ' + file);
    }
}

main();
