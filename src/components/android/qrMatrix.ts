/**
 * Pure-TypeScript ISO/IEC 18004 QR Code matrix generator (Byte mode, ECC Level L,
 * Versions 1–10: up to 271 UTF-8 bytes — plenty for any LAN/tunnel APK URL).
 */

interface VersionSpec {
  version: number;
  size: number;
  dataCodewords: number;
  ecPerBlock: number;
  blocks: Array<{ count: number; dataCw: number }>;
  alignments: number[];
}

// ISO/IEC 18004 Table 9 (Error Correction Level L, Versions 1..10)
const VERSIONS_L: VersionSpec[] = [
  { version: 1, size: 21, dataCodewords: 19, ecPerBlock: 7, blocks: [{ count: 1, dataCw: 19 }], alignments: [] },
  { version: 2, size: 25, dataCodewords: 34, ecPerBlock: 10, blocks: [{ count: 1, dataCw: 34 }], alignments: [6, 18] },
  { version: 3, size: 29, dataCodewords: 55, ecPerBlock: 15, blocks: [{ count: 1, dataCw: 55 }], alignments: [6, 22] },
  { version: 4, size: 33, dataCodewords: 80, ecPerBlock: 20, blocks: [{ count: 1, dataCw: 80 }], alignments: [6, 26] },
  { version: 5, size: 37, dataCodewords: 108, ecPerBlock: 26, blocks: [{ count: 1, dataCw: 108 }], alignments: [6, 30] },
  { version: 6, size: 41, dataCodewords: 136, ecPerBlock: 18, blocks: [{ count: 2, dataCw: 68 }], alignments: [6, 34] },
  { version: 7, size: 45, dataCodewords: 156, ecPerBlock: 20, blocks: [{ count: 2, dataCw: 78 }], alignments: [6, 22, 38] },
  { version: 8, size: 49, dataCodewords: 194, ecPerBlock: 24, blocks: [{ count: 2, dataCw: 97 }], alignments: [6, 24, 42] },
  { version: 9, size: 53, dataCodewords: 232, ecPerBlock: 30, blocks: [{ count: 2, dataCw: 116 }], alignments: [6, 26, 46] },
  { version: 10, size: 57, dataCodewords: 274, ecPerBlock: 18, blocks: [{ count: 2, dataCw: 68 }, { count: 2, dataCw: 69 }], alignments: [6, 28, 50] },
];

// GF(256) with primitive polynomial 0x11d
const GF_EXP = new Uint8Array(512);
const GF_LOG = new Uint8Array(256);
{
  let x = 1;
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x;
    GF_LOG[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255];
}

function gfMul(a: number, b: number): number {
  if (a === 0 || b === 0) return 0;
  return GF_EXP[GF_LOG[a] + GF_LOG[b]];
}

function rsGeneratorPoly(degree: number): Uint8Array {
  let poly = new Uint8Array([1]);
  for (let i = 0; i < degree; i++) {
    const next = new Uint8Array(poly.length + 1);
    const factor = GF_EXP[i];
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j];
      next[j + 1] ^= gfMul(poly[j], factor);
    }
    poly = next;
  }
  return poly;
}

function rsRemainder(data: Uint8Array, ecCount: number): Uint8Array {
  const gen = rsGeneratorPoly(ecCount);
  const rem = new Uint8Array(ecCount);
  for (const byte of data) {
    const factor = byte ^ rem[0];
    rem.copyWithin(0, 1);
    rem[ecCount - 1] = 0;
    for (let j = 0; j < ecCount; j++) {
      rem[j] ^= gfMul(gen[j + 1], factor);
    }
  }
  return rem;
}

function appendBits(bits: number[], value: number, length: number): void {
  for (let i = length - 1; i >= 0; i--) {
    bits.push((value >>> i) & 1);
  }
}

function computeFormatBits(mask: number): number {
  // Level L = 01
  const data = (0b01 << 3) | mask;
  let rem = data << 10;
  for (let i = 4; i >= 0; i--) {
    if (rem & (1 << (i + 10))) {
      rem ^= 0x537 << i;
    }
  }
  return ((data << 10) | rem) ^ 0x5412;
}

function computeVersionBits(version: number): number {
  let rem = version << 12;
  for (let i = 5; i >= 0; i--) {
    if (rem & (1 << (i + 12))) {
      rem ^= 0x1f25 << i;
    }
  }
  return (version << 12) | rem;
}

export function generateQrMatrix(text: string): boolean[][] {
  const bytes = new TextEncoder().encode(text);
  const spec = VERSIONS_L.find((v) => {
    const countBits = v.version <= 9 ? 8 : 16;
    const maxBytes = Math.floor((v.dataCodewords * 8 - 4 - countBits) / 8);
    return bytes.length <= maxBytes;
  });
  if (!spec) {
    throw new Error(`QR payload too long (${bytes.length} bytes)`);
  }

  // Encode byte-mode bitstream
  const bits: number[] = [];
  appendBits(bits, 0b0100, 4);
  appendBits(bits, bytes.length, spec.version <= 9 ? 8 : 16);
  for (const b of bytes) appendBits(bits, b, 8);

  const capacityBits = spec.dataCodewords * 8;
  const terminator = Math.min(4, capacityBits - bits.length);
  appendBits(bits, 0, terminator);
  while (bits.length % 8 !== 0) bits.push(0);

  const dataCw: number[] = [];
  for (let i = 0; i < bits.length; i += 8) {
    let b = 0;
    for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
    dataCw.push(b);
  }
  const pad = [0xec, 0x11];
  let padIdx = 0;
  while (dataCw.length < spec.dataCodewords) {
    dataCw.push(pad[padIdx++ & 1]);
  }

  // Split into RS blocks & interleave
  const dataBlocks: Uint8Array[] = [];
  const ecBlocks: Uint8Array[] = [];
  let offset = 0;
  for (const grp of spec.blocks) {
    for (let i = 0; i < grp.count; i++) {
      const slice = new Uint8Array(dataCw.slice(offset, offset + grp.dataCw));
      offset += grp.dataCw;
      dataBlocks.push(slice);
      ecBlocks.push(rsRemainder(slice, spec.ecPerBlock));
    }
  }

  const allBytes: number[] = [];
  const maxDataLen = Math.max(...dataBlocks.map((b) => b.length));
  for (let i = 0; i < maxDataLen; i++) {
    for (const blk of dataBlocks) {
      if (i < blk.length) allBytes.push(blk[i]);
    }
  }
  for (let i = 0; i < spec.ecPerBlock; i++) {
    for (const blk of ecBlocks) allBytes.push(blk[i]);
  }

  const n = spec.size;
  const modules: boolean[][] = Array.from({ length: n }, () =>
    Array(n).fill(false),
  );
  const isFunc: boolean[][] = Array.from({ length: n }, () =>
    Array(n).fill(false),
  );

  const setFunc = (r: number, c: number, dark: boolean) => {
    if (r < 0 || c < 0 || r >= n || c >= n) return;
    modules[r][c] = dark;
    isFunc[r][c] = true;
  };

  // Finder patterns + separators
  const drawFinder = (top: number, left: number) => {
    for (let dr = -1; dr <= 7; dr++) {
      for (let dc = -1; dc <= 7; dc++) {
        const r = top + dr;
        const c = left + dc;
        if (r < 0 || c < 0 || r >= n || c >= n) continue;
        const dist = Math.max(Math.abs(dr - 3), Math.abs(dc - 3));
        setFunc(r, c, dist !== 2 && dist !== 4);
      }
    }
  };
  drawFinder(0, 0);
  drawFinder(0, n - 7);
  drawFinder(n - 7, 0);

  // Timing patterns
  for (let i = 8; i < n - 8; i++) {
    setFunc(6, i, i % 2 === 0);
    setFunc(i, 6, i % 2 === 0);
  }

  // Alignment patterns
  for (const ar of spec.alignments) {
    for (const ac of spec.alignments) {
      if (isFunc[ar][ac]) continue;
      for (let dr = -2; dr <= 2; dr++) {
        for (let dc = -2; dc <= 2; dc++) {
          const dist = Math.max(Math.abs(dr), Math.abs(dc));
          setFunc(ar + dr, ac + dc, dist !== 1);
        }
      }
    }
  }

  // Reserve format info + dark module
  for (let i = 0; i <= 8; i++) {
    if (i !== 6) {
      setFunc(8, i, false);
      setFunc(i, 8, false);
    }
  }
  for (let i = n - 8; i < n; i++) {
    setFunc(8, i, false);
    setFunc(i, 8, false);
  }
  setFunc(n - 8, 8, true);

  // Version info for versions >= 7
  if (spec.version >= 7) {
    const vbits = computeVersionBits(spec.version);
    for (let i = 0; i < 18; i++) {
      const bit = ((vbits >>> i) & 1) !== 0;
      const a = Math.floor(i / 3);
      const b = n - 11 + (i % 3);
      setFunc(a, b, bit);
      setFunc(b, a, bit);
    }
  }

  // Place data bits in zigzag
  let bitIdx = 0;
  const totalDataBits = allBytes.length * 8;
  for (let right = n - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    const upward = ((right + 1) & 2) === 0;
    for (let step = 0; step < n; step++) {
      const r = upward ? n - 1 - step : step;
      for (let j = 0; j < 2; j++) {
        const c = right - j;
        if (!isFunc[r][c]) {
          let dark = false;
          if (bitIdx < totalDataBits) {
            dark = ((allBytes[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) !== 0;
          }
          modules[r][c] = dark;
          bitIdx++;
        }
      }
    }
  }

  // Apply mask 0..7 and choose lowest penalty
  const maskFn = (mask: number, r: number, c: number): boolean => {
    switch (mask) {
      case 0:
        return (r + c) % 2 === 0;
      case 1:
        return r % 2 === 0;
      case 2:
        return c % 3 === 0;
      case 3:
        return (r + c) % 3 === 0;
      case 4:
        return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0;
      case 5:
        return ((r * c) % 2) + ((r * c) % 3) === 0;
      case 6:
        return (((r * c) % 2) + ((r * c) % 3)) % 2 === 0;
      default:
        return (((r + c) % 2) + ((r * c) % 3)) % 2 === 0;
    }
  };

  const applyFormat = (grid: boolean[][], mask: number) => {
    const fmt = computeFormatBits(mask);
    for (let i = 0; i <= 5; i++) grid[8][i] = ((fmt >>> i) & 1) !== 0;
    grid[8][7] = ((fmt >>> 6) & 1) !== 0;
    grid[8][8] = ((fmt >>> 7) & 1) !== 0;
    grid[7][8] = ((fmt >>> 8) & 1) !== 0;
    for (let i = 9; i < 15; i++) grid[14 - i][8] = ((fmt >>> i) & 1) !== 0;
    for (let i = 0; i < 8; i++) grid[n - 1 - i][8] = ((fmt >>> i) & 1) !== 0;
    for (let i = 8; i < 15; i++) grid[8][n - 15 + i] = ((fmt >>> i) & 1) !== 0;
    grid[n - 8][8] = true;
  };

  const score = (grid: boolean[][]): number => {
    let penalty = 0;
    for (let r = 0; r < n; r++) {
      let run = 1;
      for (let c = 1; c < n; c++) {
        if (grid[r][c] === grid[r][c - 1]) {
          run++;
          if (run === 5) penalty += 3;
          else if (run > 5) penalty += 1;
        } else run = 1;
      }
    }
    for (let c = 0; c < n; c++) {
      let run = 1;
      for (let r = 1; r < n; r++) {
        if (grid[r][c] === grid[r - 1][c]) {
          run++;
          if (run === 5) penalty += 3;
          else if (run > 5) penalty += 1;
        } else run = 1;
      }
    }
    for (let r = 0; r < n - 1; r++) {
      for (let c = 0; c < n - 1; c++) {
        const v = grid[r][c];
        if (v === grid[r][c + 1] && v === grid[r + 1][c] && v === grid[r + 1][c + 1]) {
          penalty += 3;
        }
      }
    }
    return penalty;
  };

  let bestGrid = modules;
  let bestScore = Infinity;
  for (let mask = 0; mask < 8; mask++) {
    const candidate = modules.map((row, r) =>
      row.map((val, c) => (isFunc[r][c] ? val : val !== maskFn(mask, r, c))),
    );
    applyFormat(candidate, mask);
    const s = score(candidate);
    if (s < bestScore) {
      bestScore = s;
      bestGrid = candidate;
    }
  }
  return bestGrid;
}
