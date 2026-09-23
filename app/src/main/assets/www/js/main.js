/*
 * MC3D — a Minecraft-style voxel sandbox for glasses-free 3D displays.
 *
 * Runs as a plain WebGL page in any browser, and in "immersive-vr" WebXR
 * sessions (via the bundled WebXR shim on Leia devices, or native WebXR
 * where available) where the world is presented as a stereo window.
 */
import * as THREE from "./vendor/three.module.min.js";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------
const params = new URLSearchParams(location.search);
const SAVE_KEY = "mc3d.world.v1";

const WORLD_X = 160;
const WORLD_Z = 160;
const WORLD_Y = 64;
const CHUNK = 16;
const CHUNKS_X = WORLD_X / CHUNK;
const CHUNKS_Z = WORLD_Z / CHUNK;
const SEA_LEVEL = 24;

const EYE_HEIGHT = 1.62;
const PLAYER_HALF = 0.3;
const PLAYER_HEIGHT = 1.8;
const GRAVITY = 27;
const JUMP_SPEED = 8.4;
const WALK_SPEED = 4.4;
const SPRINT_SPEED = 6.9;
const FLY_SPEED = 9;
const REACH = 6;

const DEFAULT_DPR = 1.25;

// ---------------------------------------------------------------------------
// Blocks
// ---------------------------------------------------------------------------
const B = {
  AIR: 0, GRASS: 1, DIRT: 2, STONE: 3, COBBLE: 4, SAND: 5, LOG: 6,
  PLANKS: 7, LEAVES: 8, WATER: 9, GLASS: 10, COAL: 11, IRON: 12,
  BEDROCK: 13, SNOW: 14
};

// Atlas tile indices (4x4 grid of 16px tiles)
const T = {
  GRASS_TOP: 0, GRASS_SIDE: 1, DIRT: 2, STONE: 3,
  COBBLE: 4, SAND: 5, LOG_SIDE: 6, LOG_TOP: 7,
  PLANKS: 8, LEAVES: 9, WATER: 10, GLASS: 11,
  COAL: 12, IRON: 13, BEDROCK: 14, SNOW: 15
};

// face: [top, side, bottom]
const BLOCK_INFO = {
  [B.GRASS]:   { name: "Grass",    tiles: [T.GRASS_TOP, T.GRASS_SIDE, T.DIRT] },
  [B.DIRT]:    { name: "Dirt",     tiles: [T.DIRT, T.DIRT, T.DIRT] },
  [B.STONE]:   { name: "Stone",    tiles: [T.STONE, T.STONE, T.STONE] },
  [B.COBBLE]:  { name: "Cobble",   tiles: [T.COBBLE, T.COBBLE, T.COBBLE] },
  [B.SAND]:    { name: "Sand",     tiles: [T.SAND, T.SAND, T.SAND] },
  [B.LOG]:     { name: "Oak log",  tiles: [T.LOG_TOP, T.LOG_SIDE, T.LOG_TOP] },
  [B.PLANKS]:  { name: "Planks",   tiles: [T.PLANKS, T.PLANKS, T.PLANKS] },
  [B.LEAVES]:  { name: "Leaves",   tiles: [T.LEAVES, T.LEAVES, T.LEAVES] },
  [B.WATER]:   { name: "Water",    tiles: [T.WATER, T.WATER, T.WATER], liquid: true },
  [B.GLASS]:   { name: "Glass",    tiles: [T.GLASS, T.GLASS, T.GLASS], glass: true },
  [B.COAL]:    { name: "Coal ore", tiles: [T.COAL, T.COAL, T.COAL] },
  [B.IRON]:    { name: "Iron ore", tiles: [T.IRON, T.IRON, T.IRON] },
  [B.BEDROCK]: { name: "Bedrock",  tiles: [T.BEDROCK, T.BEDROCK, T.BEDROCK] },
  [B.SNOW]:    { name: "Snow",     tiles: [T.SNOW, T.SNOW, T.SNOW] }
};

const HOTBAR = [
  B.GRASS, B.DIRT, B.STONE, B.COBBLE, B.PLANKS,
  B.LOG, B.LEAVES, B.SAND, B.GLASS
];

function isSolid(id) {
  return id !== B.AIR && id !== B.WATER;
}
function isOpaque(id) {
  // Leaves render opaque (fast graphics); glass and water are see-through.
  return isSolid(id) && id !== B.GLASS;
}
function occludesFace(neighborId, ownId) {
  // Does the neighbor hide this block's face?
  if (neighborId === B.AIR) return false;
  if (neighborId === B.WATER) return ownId === B.WATER;
  if (neighborId === B.GLASS) return ownId === B.GLASS;
  return true; // opaque neighbor (incl. leaves)
}

// ---------------------------------------------------------------------------
// Seeded noise
// ---------------------------------------------------------------------------
function hash2(xi, zi, seed) {
  let h = (Math.imul(xi, 374761393) + Math.imul(zi, 668265263) +
    Math.imul(seed, 1442695041)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}
function smoothstep(t) { return t * t * (3 - 2 * t); }
function valueNoise(x, z, seed) {
  const xi = Math.floor(x), zi = Math.floor(z);
  const xf = x - xi, zf = z - zi;
  const a = hash2(xi, zi, seed), b = hash2(xi + 1, zi, seed);
  const c = hash2(xi, zi + 1, seed), d = hash2(xi + 1, zi + 1, seed);
  const u = smoothstep(xf), v = smoothstep(zf);
  return a * (1 - u) * (1 - v) + b * u * (1 - v) +
    c * (1 - u) * v + d * u * v;
}
function fbm(x, z, seed, octaves, scale) {
  let amp = 1, sum = 0, norm = 0, freq = 1 / scale;
  for (let i = 0; i < octaves; i++) {
    sum += amp * valueNoise(x * freq, z * freq, seed + i * 131);
    norm += amp;
    amp *= 0.5;
    freq *= 2;
  }
  return sum / norm;
}

// ---------------------------------------------------------------------------
// World
// ---------------------------------------------------------------------------
const world = {
  seed: 0,
  blocks: new Uint8Array(WORLD_X * WORLD_Z * WORLD_Y),
  edits: new Map(),
  heights: new Int16Array(WORLD_X * WORLD_Z),
  spawn: { x: WORLD_X / 2, y: 40, z: WORLD_Z / 2 }
};

function blockIndex(x, y, z) {
  return (x * WORLD_Z + z) * WORLD_Y + y;
}
function getBlock(x, y, z) {
  if (x < 0 || z < 0 || x >= WORLD_X || z >= WORLD_Z || y < 0 || y >= WORLD_Y) {
    return B.AIR;
  }
  return world.blocks[blockIndex(x, y, z)];
}
function setBlockRaw(x, y, z, id) {
  if (x < 0 || z < 0 || x >= WORLD_X || z >= WORLD_Z || y < 0 || y >= WORLD_Y) {
    return;
  }
  world.blocks[blockIndex(x, y, z)] = id;
}

function generateWorld(seed) {
  world.seed = seed;
  world.blocks.fill(B.AIR);
  world.edits.clear();

  for (let x = 0; x < WORLD_X; x++) {
    for (let z = 0; z < WORLD_Z; z++) {
      const n = fbm(x, z, seed, 4, 46);
      const ridge = fbm(x, z, seed + 777, 3, 130);
      let h = Math.floor(24 + (n - 0.48) * 30 + (ridge - 0.5) * 8);
      h = Math.max(4, Math.min(WORLD_Y - 12, h));
      world.heights[x * WORLD_Z + z] = h;

      for (let y = 0; y <= h; y++) {
        let id = B.STONE;
        if (y === 0) {
          id = B.BEDROCK;
        } else if (y === h) {
          if (h <= SEA_LEVEL + 1) id = B.SAND;
          else if (h >= 34) id = B.SNOW;
          else id = B.GRASS;
        } else if (y >= h - 3) {
          id = h <= SEA_LEVEL + 1 ? B.SAND : B.DIRT;
        } else {
          const o = hash2(x * 37 + y, z * 17 + y, seed + 555);
          if (o < 0.006 && y < 26) id = B.IRON;
          else if (o < 0.02) id = B.COAL;
        }
        setBlockRaw(x, y, z, id);
      }
      for (let y = h + 1; y <= SEA_LEVEL; y++) {
        setBlockRaw(x, y, z, B.WATER);
      }
    }
  }

  // Trees
  for (let x = 3; x < WORLD_X - 3; x++) {
    for (let z = 3; z < WORLD_Z - 3; z++) {
      const h = world.heights[x * WORLD_Z + z];
      if (getBlock(x, h, z) !== B.GRASS) continue;
      if (hash2(x, z, seed + 909) > 0.012) continue;
      const trunk = 4 + Math.floor(hash2(x, z, seed + 910) * 3);
      for (let y = 1; y <= trunk; y++) setBlockRaw(x, h + y, z, B.LOG);
      const top = h + trunk;
      for (let dy = -1; dy <= 2; dy++) {
        const r = dy <= 0 ? 2 : (dy === 1 ? 2 : 1);
        for (let dx = -r; dx <= r; dx++) {
          for (let dz = -r; dz <= r; dz++) {
            if (Math.abs(dx) === r && Math.abs(dz) === r && dy >= 1) continue;
            if (dx === 0 && dz === 0 && dy <= 0) continue;
            if (getBlock(x + dx, top + dy, z + dz) === B.AIR) {
              setBlockRaw(x + dx, top + dy, z + dz, B.LEAVES);
            }
          }
        }
      }
      setBlockRaw(x, top + 2, z, B.LEAVES);
    }
  }

  // Spawn point: nearest grassy column to the center above sea level
  const cx = WORLD_X >> 1, cz = WORLD_Z >> 1;
  outer: for (let r = 0; r < 60; r++) {
    for (let dx = -r; dx <= r; dx++) {
      for (let dz = -r; dz <= r; dz++) {
        if (Math.max(Math.abs(dx), Math.abs(dz)) !== r) continue;
        const x = cx + dx, z = cz + dz;
        const h = world.heights[x * WORLD_Z + z];
        if (h > SEA_LEVEL + 1 && getBlock(x, h, z) === B.GRASS) {
          world.spawn = { x: x + 0.5, y: h + 1, z: z + 0.5 };
          break outer;
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Save / load
// ---------------------------------------------------------------------------
let saveTimer = 0;
function saveWorld() {
  try {
    const edits = [];
    for (const [idx, id] of world.edits) edits.push(idx, id);
    localStorage.setItem(SAVE_KEY, JSON.stringify({
      v: 1,
      seed: world.seed,
      edits,
      player: {
        x: player.pos.x, y: player.pos.y, z: player.pos.z,
        yaw: player.yaw, pitch: player.pitch,
        slot: hotbarIndex
      }
    }));
  } catch (e) { console.warn("save failed", e); }
}
function queueSave() {
  saveTimer = 1.2; // saved from the main loop after the debounce elapses
}

function loadWorld() {
  let data = null;
  try {
    const raw = localStorage.getItem(SAVE_KEY);
    if (raw) data = JSON.parse(raw);
  } catch (e) { /* corrupted save: start fresh */ }

  let seed;
  if (params.get("seed") !== null) {
    seed = parseInt(params.get("seed"), 10) | 0;
  } else if (data && typeof data.seed === "number") {
    seed = data.seed;
  } else {
    seed = (Math.random() * 0x7fffffff) | 0;
  }

  generateWorld(seed);

  if (data && Array.isArray(data.edits)) {
    for (let i = 0; i + 1 < data.edits.length; i += 2) {
      const idx = data.edits[i], id = data.edits[i + 1];
      world.blocks[idx] = id;
      world.edits.set(idx, id);
      // refresh affected heights cache roughly (top surface may change)
      const x = Math.floor(idx / (WORLD_Z * WORLD_Y));
      const z = Math.floor(idx / WORLD_Y) % WORLD_Z;
      let h = WORLD_Y - 1;
      while (h > 0 && !isSolid(getBlock(x, h, z))) h--;
      world.heights[x * WORLD_Z + z] = h;
    }
  }
  return data;
}

// ---------------------------------------------------------------------------
// Procedural texture atlas
// ---------------------------------------------------------------------------
const TILE = 16, ATLAS_TILES = 4;
const ATLAS_SIZE = TILE * ATLAS_TILES;

function makeAtlas() {
  const cv = document.createElement("canvas");
  cv.width = cv.height = ATLAS_SIZE;
  const ctx = cv.getContext("2d");
  ctx.clearRect(0, 0, ATLAS_SIZE, ATLAS_SIZE);

  function tileRect(i) {
    return { x: (i % ATLAS_TILES) * TILE, y: Math.floor(i / ATLAS_TILES) * TILE };
  }
  function speckle(i, base, variants, density) {
    const { x, y } = tileRect(i);
    ctx.fillStyle = base;
    ctx.fillRect(x, y, TILE, TILE);
    for (let px = 0; px < TILE; px++) {
      for (let py = 0; py < TILE; py++) {
        const r = hash2(px + i * 61, py + i * 97, 1234);
        if (r < density) {
          ctx.fillStyle = variants[(r * variants.length * 7 | 0) % variants.length];
          ctx.fillRect(x + px, y + py, 1, 1);
        }
      }
    }
  }

  speckle(T.GRASS_TOP, "#6cac40", ["#5d9c37", "#7abd4b", "#548e33"], 0.55);
  speckle(T.DIRT, "#8a5f3c", ["#7a5233", "#99693f", "#6e4a2e"], 0.5);
  speckle(T.SAND, "#dcd39b", ["#d3c98d", "#e6dcaa", "#cbc07f"], 0.5);
  speckle(T.STONE, "#8d8d8d", ["#828282", "#989898", "#787878"], 0.45);
  speckle(T.SNOW, "#eef4f7", ["#e2ebf0", "#f8fbfd", "#d9e5ec"], 0.4);

  // Grass side: dirt with a grass rim
  (function () {
    const { x, y } = tileRect(T.GRASS_SIDE);
    ctx.drawImage(cv, tileRect(T.DIRT).x, tileRect(T.DIRT).y, TILE, TILE, x, y, TILE, TILE);
    for (let px = 0; px < TILE; px++) {
      const depth = 2 + (hash2(px, 7, 99) * 3 | 0);
      for (let py = 0; py < depth; py++) {
        ctx.fillStyle = hash2(px, py, 98) < 0.4 ? "#5d9c37" : "#6cac40";
        ctx.fillRect(x + px, y + py, 1, 1);
      }
    }
  })();

  // Cobblestone: rounded stone cells
  (function () {
    const { x, y } = tileRect(T.COBBLE);
    ctx.fillStyle = "#5c5c5c";
    ctx.fillRect(x, y, TILE, TILE);
    const cells = [[0, 0, 8, 8], [8, 0, 8, 6], [0, 8, 6, 8], [6, 6, 10, 10], [8, 12, 8, 4]];
    for (const [cx2, cy2, w, h] of cells) {
      const shade = 118 + (hash2(cx2, cy2, 5) * 50 | 0);
      ctx.fillStyle = `rgb(${shade},${shade},${shade})`;
      ctx.fillRect(x + cx2 + 1, y + cy2 + 1, w - 1, h - 1);
    }
  })();

  // Logs
  (function () {
    const { x, y } = tileRect(T.LOG_SIDE);
    for (let px = 0; px < TILE; px++) {
      const streak = hash2(px, 3, 21);
      ctx.fillStyle = streak < 0.3 ? "#4f3822" : (streak < 0.7 ? "#6b4c2a" : "#5a401f");
      ctx.fillRect(x + px, y, 1, TILE);
    }
    ctx.fillStyle = "rgba(40,26,12,0.5)";
    for (let py = 1; py < TILE; py += 5) ctx.fillRect(x, y + py, TILE, 1);
  })();
  (function () {
    const { x, y } = tileRect(T.LOG_TOP);
    ctx.fillStyle = "#6b4c2a";
    ctx.fillRect(x, y, TILE, TILE);
    ctx.strokeStyle = "#8a6a3e";
    for (let r = 2; r < 8; r += 2) {
      ctx.beginPath();
      ctx.arc(x + 8, y + 8, r, 0, Math.PI * 2);
      ctx.stroke();
    }
  })();

  // Planks
  (function () {
    const { x, y } = tileRect(T.PLANKS);
    ctx.fillStyle = "#a07840";
    ctx.fillRect(x, y, TILE, TILE);
    ctx.fillStyle = "#8c672f";
    for (let py = 0; py < TILE; py += 4) ctx.fillRect(x, y + py, TILE, 1);
    ctx.fillStyle = "#7a5a28";
    for (let py = 0; py < TILE; py += 4) {
      const notch = (hash2(py, 1, 3) * 12 | 0) + 2;
      ctx.fillRect(x + notch, y + py, 1, 4);
    }
    for (let px = 0; px < TILE; px++) {
      if (hash2(px, 9, 44) < 0.2) {
        ctx.fillStyle = "#96c05a";
        ctx.fillRect(x + px, y + (hash2(px, 2, 45) * 12 | 0), 1, 1);
      }
    }
  })();

  // Leaves (opaque with dark holes)
  speckle(T.LEAVES, "#3e7d2c", ["#356d25", "#4a8f36", "#2c5e1f", "#27401c"], 0.75);

  // Water
  speckle(T.WATER, "#3f76e4", ["#3a6ed8", "#4a83ef", "#3564c8"], 0.4);

  // Glass: mostly transparent, frame + streaks (cutout via alphaTest)
  (function () {
    const { x, y } = tileRect(T.GLASS);
    ctx.clearRect(x, y, TILE, TILE);
    ctx.fillStyle = "rgba(220,240,255,0.85)";
    ctx.fillRect(x, y, TILE, 1);
    ctx.fillRect(x, y + TILE - 1, TILE, 1);
    ctx.fillRect(x, y, 1, TILE);
    ctx.fillRect(x + TILE - 1, y, 1, TILE);
    ctx.fillStyle = "rgba(230,245,255,0.9)";
    ctx.fillRect(x + 3, y + 3, 1, 6);
    ctx.fillRect(x + 4, y + 9, 1, 4);
    ctx.fillRect(x + 11, y + 2, 1, 5);
  })();

  // Ores
  (function () {
    const { x, y } = tileRect(T.COAL);
    ctx.drawImage(cv, tileRect(T.STONE).x, tileRect(T.STONE).y, TILE, TILE, x, y, TILE, TILE);
    ctx.fillStyle = "#1e1e1e";
    const spots = [[3, 3, 3], [9, 5, 2], [5, 10, 3], [11, 11, 2]];
    for (const [sx, sy, s] of spots) ctx.fillRect(x + sx, y + sy, s, s);
  })();
  (function () {
    const { x, y } = tileRect(T.IRON);
    ctx.drawImage(cv, tileRect(T.STONE).x, tileRect(T.STONE).y, TILE, TILE, x, y, TILE, TILE);
    ctx.fillStyle = "#d8a26a";
    const spots = [[4, 2, 3], [10, 6, 2], [3, 9, 2], [8, 11, 3]];
    for (const [sx, sy, s] of spots) ctx.fillRect(x + sx, y + sy, s, s);
  })();

  // Bedrock
  speckle(T.BEDROCK, "#3a3a3a", ["#222", "#4a4a4a", "#161616", "#565656"], 0.7);

  return cv;
}

const atlasCanvas = makeAtlas();
const atlasTexture = new THREE.CanvasTexture(atlasCanvas);
atlasTexture.magFilter = THREE.NearestFilter;
atlasTexture.minFilter = THREE.NearestFilter;
atlasTexture.generateMipmaps = false;
atlasTexture.flipY = false;
atlasTexture.colorSpace = THREE.SRGBColorSpace;

function tileU(tileIndex, u) { return (tileIndex % ATLAS_TILES + u) / ATLAS_TILES; }
function tileV(tileIndex, v) {
  const row = Math.floor(tileIndex / ATLAS_TILES);
  return (row + 1 - v) / ATLAS_TILES;
}

// ---------------------------------------------------------------------------
// Chunk meshing (with baked ambient occlusion)
// ---------------------------------------------------------------------------
const FACES = [
  { dir: [-1, 0, 0], shade: 0.68, corners: [
    { pos: [0, 1, 0], uv: [0, 1] }, { pos: [0, 0, 0], uv: [0, 0] },
    { pos: [0, 1, 1], uv: [1, 1] }, { pos: [0, 0, 1], uv: [1, 0] }] },
  { dir: [1, 0, 0], shade: 0.68, corners: [
    { pos: [1, 1, 1], uv: [0, 1] }, { pos: [1, 0, 1], uv: [0, 0] },
    { pos: [1, 1, 0], uv: [1, 1] }, { pos: [1, 0, 0], uv: [1, 0] }] },
  { dir: [0, -1, 0], shade: 0.5, corners: [
    { pos: [1, 0, 1], uv: [1, 0] }, { pos: [0, 0, 1], uv: [0, 0] },
    { pos: [1, 0, 0], uv: [1, 1] }, { pos: [0, 0, 0], uv: [0, 1] }] },
  { dir: [0, 1, 0], shade: 1.0, corners: [
    { pos: [0, 1, 1], uv: [1, 1] }, { pos: [1, 1, 1], uv: [0, 1] },
    { pos: [0, 1, 0], uv: [1, 0] }, { pos: [1, 1, 0], uv: [0, 0] }] },
  { dir: [0, 0, -1], shade: 0.8, corners: [
    { pos: [1, 0, 0], uv: [0, 0] }, { pos: [0, 0, 0], uv: [1, 0] },
    { pos: [1, 1, 0], uv: [0, 1] }, { pos: [0, 1, 0], uv: [1, 1] }] },
  { dir: [0, 0, 1], shade: 0.8, corners: [
    { pos: [0, 0, 1], uv: [0, 0] }, { pos: [1, 0, 1], uv: [1, 0] },
    { pos: [0, 1, 1], uv: [0, 1] }, { pos: [1, 1, 1], uv: [1, 1] }] }
];

const AO_LEVELS = [0.5, 0.66, 0.82, 1.0];

function aoForCorner(x, y, z, face, corner) {
  const d = face.dir;
  // tangent axes of this face
  let t1, t2;
  if (d[0] !== 0) { t1 = [0, 1, 0]; t2 = [0, 0, 1]; }
  else if (d[1] !== 0) { t1 = [1, 0, 0]; t2 = [0, 0, 1]; }
  else { t1 = [1, 0, 0]; t2 = [0, 1, 0]; }

  const s1 = corner.pos[t1[0] ? 0 : (t1[1] ? 1 : 2)] ? 1 : -1;
  const s2 = corner.pos[t2[0] ? 0 : (t2[1] ? 1 : 2)] ? 1 : -1;

  const bx = x + d[0], by = y + d[1], bz = z + d[2];
  const side1 = isOpaque(getBlock(bx + t1[0] * s1, by + t1[1] * s1, bz + t1[2] * s1)) ? 1 : 0;
  const side2 = isOpaque(getBlock(bx + t2[0] * s2, by + t2[1] * s2, bz + t2[2] * s2)) ? 1 : 0;
  const cornerB = isOpaque(getBlock(
    bx + t1[0] * s1 + t2[0] * s2,
    by + t1[1] * s1 + t2[1] * s2,
    bz + t1[2] * s1 + t2[2] * s2)) ? 1 : 0;
  if (side1 && side2) return 0;
  return 3 - (side1 + side2 + cornerB);
}

const chunkMeshes = [];  // { solid: Mesh|null, water: Mesh|null }
const dirtyChunks = new Set();

function markDirty(x, z) {
  const cx = Math.floor(x / CHUNK), cz = Math.floor(z / CHUNK);
  for (let dx = -1; dx <= 1; dx++) {
    for (let dz = -1; dz <= 1; dz++) {
      const cxx = cx + dx, czz = cz + dz;
      if (cxx < 0 || czz < 0 || cxx >= CHUNKS_X || czz >= CHUNKS_Z) continue;
      dirtyChunks.add(cxx * CHUNKS_Z + czz);
    }
  }
}

function tileForFace(id, faceIndex) {
  const info = BLOCK_INFO[id];
  if (!info) return T.STONE;
  if (faceIndex === 3) return info.tiles[0];       // top
  if (faceIndex === 2) return info.tiles[2];       // bottom
  return info.tiles[1];                           // sides
}

const solidMaterial = new THREE.MeshLambertMaterial({
  map: atlasTexture,
  vertexColors: true,
  alphaTest: 0.5,
  side: THREE.FrontSide
});
const waterMaterial = new THREE.MeshLambertMaterial({
  map: atlasTexture,
  vertexColors: true,
  transparent: true,
  opacity: 0.72,
  depthWrite: false
});

function buildChunkGeometry(cx, cz, liquid) {
  const positions = [];
  const normals = [];
  const uvs = [];
  const colors = [];
  const indices = [];

  const x0 = cx * CHUNK, z0 = cz * CHUNK;

  for (let x = x0; x < x0 + CHUNK; x++) {
    for (let z = z0; z < z0 + CHUNK; z++) {
      for (let y = 0; y < WORLD_Y; y++) {
        const id = getBlock(x, y, z);
        if (id === B.AIR) continue;
        const isWater = id === B.WATER;
        if (liquid !== isWater) continue;

        for (let f = 0; f < 6; f++) {
          const face = FACES[f];
          const nx = x + face.dir[0], ny = y + face.dir[1], nz = z + face.dir[2];
          const neighbor = getBlock(nx, ny, nz);
          if (liquid) {
            if (neighbor !== B.AIR) continue;
          } else if (occludesFace(neighbor, id)) {
            continue;
          }

          const tile = tileForFace(id, f);
          const baseIndex = positions.length / 3;
          const ao = [0, 0, 0, 0];

          for (let ci = 0; ci < 4; ci++) {
            const c = face.corners[ci];
            positions.push(x + c.pos[0], y + c.pos[1], z + c.pos[2]);
            normals.push(face.dir[0], face.dir[1], face.dir[2]);
            uvs.push(tileU(tile, c.uv[0]), tileV(tile, c.uv[1]));
            let level = 3;
            if (!liquid) level = aoForCorner(x, y, z, face, c);
            ao[ci] = level;
            const shade = face.shade * AO_LEVELS[level];
            colors.push(shade, shade, shade);
          }

          if (ao[0] + ao[3] > ao[1] + ao[2]) {
            indices.push(baseIndex, baseIndex + 1, baseIndex + 3,
              baseIndex, baseIndex + 3, baseIndex + 2);
          } else {
            indices.push(baseIndex, baseIndex + 1, baseIndex + 2,
              baseIndex + 2, baseIndex + 1, baseIndex + 3);
          }
        }
      }
    }
  }

  if (positions.length === 0) return null;
  const geo = new THREE.BufferGeometry();
  geo.setAttribute("position", new THREE.Float32BufferAttribute(positions, 3));
  geo.setAttribute("normal", new THREE.Float32BufferAttribute(normals, 3));
  geo.setAttribute("uv", new THREE.Float32BufferAttribute(uvs, 2));
  geo.setAttribute("color", new THREE.Float32BufferAttribute(colors, 3));
  geo.setIndex(indices);
  geo.computeBoundingSphere();
  return geo;
}

function rebuildChunk(cx, cz) {
  const idx = cx * CHUNKS_Z + cz;
  const entry = chunkMeshes[idx] || (chunkMeshes[idx] = { solid: null, water: null });

  for (const kind of ["solid", "water"]) {
    if (entry[kind]) {
      scene.remove(entry[kind]);
      entry[kind].geometry.dispose();
      entry[kind] = null;
    }
  }
  const solid = buildChunkGeometry(cx, cz, false);
  if (solid) {
    entry.solid = new THREE.Mesh(solid, solidMaterial);
    scene.add(entry.solid);
  }
  const water = buildChunkGeometry(cx, cz, true);
  if (water) {
    entry.water = new THREE.Mesh(water, waterMaterial);
    entry.water.renderOrder = 1;
    scene.add(entry.water);
  }
}

function buildAllChunks() {
  for (let cx = 0; cx < CHUNKS_X; cx++) {
    for (let cz = 0; cz < CHUNKS_Z; cz++) {
      rebuildChunk(cx, cz);
    }
  }
  dirtyChunks.clear();
}

function processDirtyChunks() {
  let budget = 2;
  for (const idx of dirtyChunks) {
    if (budget-- <= 0) break;
    dirtyChunks.delete(idx);
    rebuildChunk(Math.floor(idx / CHUNKS_Z), idx % CHUNKS_Z);
  }
}

function editBlock(x, y, z, id) {
  if (x < 0 || z < 0 || x >= WORLD_X || z >= WORLD_Z || y < 0 || y >= WORLD_Y) return;
  const idx = blockIndex(x, y, z);
  if (world.blocks[idx] === id) return;
  world.blocks[idx] = id;
  world.edits.set(idx, id);
  let h = WORLD_Y - 1;
  while (h > 0 && !isSolid(getBlock(x, h, z))) h--;
  world.heights[x * WORLD_Z + z] = h;
  markDirty(x, z);
  queueSave();
}

// ---------------------------------------------------------------------------
// Scene setup
// ---------------------------------------------------------------------------
const canvas = document.getElementById("game");

const renderer = new THREE.WebGLRenderer({
  canvas,
  antialias: false,
  powerPreference: "high-performance"
});
renderer.xr.enabled = true;

const DPR_CAP = params.get("dpr") ? parseFloat(params.get("dpr")) : DEFAULT_DPR;
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x87b7e8);
scene.fog = new THREE.Fog(0x87b7e8, 55, 130);

const camera = new THREE.PerspectiveCamera(
  70, window.innerWidth / window.innerHeight, 0.1, 400);
camera.rotation.order = "YXZ";

scene.add(new THREE.HemisphereLight(0xcfe5ff, 0x8a7a5a, 0.85));
const sun = new THREE.DirectionalLight(0xfff3d6, 1.05);
sun.position.set(60, 120, 40);
scene.add(sun);

// Clouds
const clouds = new THREE.Group();
{
  const cloudMat = new THREE.MeshLambertMaterial({
    color: 0xffffff, transparent: true, opacity: 0.55, depthWrite: false
  });
  for (let i = 0; i < 14; i++) {
    const w = 10 + hash2(i, 1, 42) * 18;
    const d = 8 + hash2(i, 2, 43) * 14;
    const geo = new THREE.BoxGeometry(w, 1.4, d);
    const m = new THREE.Mesh(geo, cloudMat);
    m.position.set(
      hash2(i, 3, 44) * WORLD_X * 1.4 - WORLD_X * 0.2,
      74 + hash2(i, 4, 45) * 6,
      hash2(i, 5, 46) * WORLD_Z * 1.4 - WORLD_Z * 0.2);
    clouds.add(m);
  }
}
scene.add(clouds);

// Block highlight
const highlight = new THREE.LineSegments(
  new THREE.EdgesGeometry(new THREE.BoxGeometry(1.002, 1.002, 1.002)),
  new THREE.LineBasicMaterial({ color: 0x111111 })
);
highlight.visible = false;
scene.add(highlight);

// ---------------------------------------------------------------------------
// Player
// ---------------------------------------------------------------------------
const player = {
  pos: new THREE.Vector3(world.spawn.x, world.spawn.y, world.spawn.z),
  vel: new THREE.Vector3(),
  yaw: 0,
  pitch: -0.12,
  onGround: false,
  flying: false,
  inWater: false
};

function playerAABBIntersectsBlock(bx, by, bz) {
  const minX = player.pos.x - PLAYER_HALF, maxX = player.pos.x + PLAYER_HALF;
  const minY = player.pos.y, maxY = player.pos.y + PLAYER_HEIGHT;
  const minZ = player.pos.z - PLAYER_HALF, maxZ = player.pos.z + PLAYER_HALF;
  return bx + 1 > minX && bx < maxX && by + 1 > minY && by < maxY &&
    bz + 1 > minZ && bz < maxZ;
}

function collidesAt(axis, value) {
  const p = player.pos.clone();
  p[axis] = value;
  const minX = Math.floor(p.x - PLAYER_HALF), maxX = Math.floor(p.x + PLAYER_HALF);
  const minY = Math.floor(p.y), maxY = Math.floor(p.y + PLAYER_HEIGHT - 0.001);
  const minZ = Math.floor(p.z - PLAYER_HALF), maxZ = Math.floor(p.z + PLAYER_HALF);
  for (let x = minX; x <= maxX; x++) {
    for (let y = minY; y <= maxY; y++) {
      for (let z = minZ; z <= maxZ; z++) {
        if (isSolid(getBlock(x, y, z))) return true;
      }
    }
  }
  return false;
}

function moveAxis(axis, amount) {
  if (amount === 0) return;
  const target = player.pos[axis] + amount;
  if (!collidesAt(axis, target)) {
    player.pos[axis] = target;
    if (axis === "y" && amount < 0) player.onGround = false;
    return;
  }
  // Binary resolution to the block boundary
  const step = Math.sign(amount) * 0.02;
  let v = player.pos[axis];
  for (let i = 0; i < Math.abs(amount) / 0.02 + 4; i++) {
    const next = v + step;
    if (collidesAt(axis, next)) break;
    v = next;
    if (i > 400) break;
  }
  player.pos[axis] = v;
  if (axis === "y") {
    if (amount < 0) player.onGround = true;
    player.vel.y = 0;
  }
}

function updatePlayer(dt) {
  const forwardInput = input.move.y;   // +1 forward
  const strafeInput = input.move.x;    // +1 right
  const sprinting = input.sprintTouch || input.sprintKeys;
  const joyVec = input.touchActive
    ? { x: input.joy.x, y: -input.joy.y }
    : { x: strafeInput, y: forwardInput };
  const jumping = input.jumpTouch || input.jumpKeys;

  const sinYaw = Math.sin(player.yaw);
  const cosYaw = Math.cos(player.yaw);
  // Forward is -Z rotated by yaw; right is +X rotated by yaw.
  let mx = (-sinYaw * joyVec.y + cosYaw * joyVec.x);
  let mz = (-cosYaw * joyVec.y - sinYaw * joyVec.x);
  const mlen = Math.hypot(mx, mz);
  if (mlen > 1) { mx /= mlen; mz /= mlen; }

  const eyeBlock = getBlock(
    Math.floor(player.pos.x), Math.floor(player.pos.y + 1.0), Math.floor(player.pos.z));
  player.inWater = eyeBlock === B.WATER;

  let speed = player.flying
    ? (sprinting ? FLY_SPEED * 1.6 : FLY_SPEED)
    : (player.inWater ? WALK_SPEED * 0.65 : (sprinting ? SPRINT_SPEED : WALK_SPEED));

  if (player.flying) {
    player.vel.y *= Math.pow(0.001, dt);
    if (jumping) player.vel.y = speed * 0.8;
    else if (input.descend) player.vel.y = -speed * 0.8;
  } else if (player.inWater) {
    player.vel.y += -6 * dt;
    if (player.vel.y < -2.2) player.vel.y = -2.2;
    if (jumping) player.vel.y = 4.2;
  } else {
    player.vel.y -= GRAVITY * dt;
    if (player.vel.y < -60) player.vel.y = -60;
    if (jumping && player.onGround) {
      player.vel.y = JUMP_SPEED;
      player.onGround = false;
    }
  }

  player.onGround = false;
  moveAxis("y", player.vel.y * dt);
  moveAxis("x", mx * speed * dt);
  moveAxis("z", mz * speed * dt);

  // Falling out of the world respawns the player
  if (player.pos.y < -12) {
    player.pos.set(world.spawn.x, world.spawn.y + 2, world.spawn.z);
    player.vel.set(0, 0, 0);
  }

  // Landing cancels fly mode
  if (player.flying && player.onGround) player.flying = false;
}

// ---------------------------------------------------------------------------
// Voxel raycast (Amanatides & Woo DDA)
// ---------------------------------------------------------------------------
function raycastVoxel(origin, dir, maxDist) {
  let x = Math.floor(origin.x), y = Math.floor(origin.y), z = Math.floor(origin.z);
  const stepX = Math.sign(dir.x), stepY = Math.sign(dir.y), stepZ = Math.sign(dir.z);
  const tDeltaX = stepX !== 0 ? Math.abs(1 / dir.x) : Infinity;
  const tDeltaY = stepY !== 0 ? Math.abs(1 / dir.y) : Infinity;
  const tDeltaZ = stepZ !== 0 ? Math.abs(1 / dir.z) : Infinity;
  const fracX = stepX > 0 ? (x + 1 - origin.x) : (origin.x - x);
  const fracY = stepY > 0 ? (y + 1 - origin.y) : (origin.y - y);
  const fracZ = stepZ > 0 ? (z + 1 - origin.z) : (origin.z - z);
  let tMaxX = stepX !== 0 ? tDeltaX * fracX : Infinity;
  let tMaxY = stepY !== 0 ? tDeltaY * fracY : Infinity;
  let tMaxZ = stepZ !== 0 ? tDeltaZ * fracZ : Infinity;

  let face = [0, 0, 0];
  let t = 0;
  while (t <= maxDist) {
    const id = getBlock(x, y, z);
    if (isSolid(id)) {
      return { x, y, z, id, nx: face[0], ny: face[1], nz: face[2] };
    }
    if (tMaxX < tMaxY && tMaxX < tMaxZ) {
      t = tMaxX; tMaxX += tDeltaX; x += stepX; face = [-stepX, 0, 0];
    } else if (tMaxY < tMaxZ) {
      t = tMaxY; tMaxY += tDeltaY; y += stepY; face = [0, -stepY, 0];
    } else {
      t = tMaxZ; tMaxZ += tDeltaZ; z += stepZ; face = [0, 0, -stepZ];
    }
  }
  return null;
}

const camDir = new THREE.Vector3();
function currentTarget() {
  camera.getWorldDirection(camDir);
  return raycastVoxel(camera.position, camDir, REACH);
}

function mineTarget() {
  const hit = currentTarget();
  if (!hit) return;
  editBlock(hit.x, hit.y, hit.z, B.AIR);
  playSound("break");
  if (window.NativeLeia && NativeLeia.vibrate) NativeLeia.vibrate(12);
}

function placeTarget() {
  const hit = currentTarget();
  if (!hit) return;
  const px = hit.x + hit.nx, py = hit.y + hit.ny, pz = hit.z + hit.nz;
  const existing = getBlock(px, py, pz);
  if (existing !== B.AIR && existing !== B.WATER) return;
  const id = HOTBAR[hotbarIndex];
  // Placing inside the player is not allowed (unless flying above it)
  if (isSolid(id) && playerAABBIntersectsBlock(px, py, pz)) return;
  editBlock(px, py, pz, id);
  playSound("place");
}

// ---------------------------------------------------------------------------
// Audio (procedural, no assets)
// ---------------------------------------------------------------------------
let audioCtx = null;
function ensureAudio() {
  if (!audioCtx) {
    try {
      audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    } catch (e) { audioCtx = null; }
  }
  if (audioCtx && audioCtx.state === "suspended") audioCtx.resume();
}
function playSound(kind) {
  if (!audioCtx) return;
  const t0 = audioCtx.currentTime;
  const osc = audioCtx.createOscillator();
  const gain = audioCtx.createGain();
  osc.connect(gain);
  gain.connect(audioCtx.destination);
  if (kind === "break") {
    osc.type = "square";
    osc.frequency.setValueAtTime(190, t0);
    osc.frequency.exponentialRampToValueAtTime(90, t0 + 0.09);
    gain.gain.setValueAtTime(0.08, t0);
    gain.gain.exponentialRampToValueAtTime(0.001, t0 + 0.1);
    osc.start(t0); osc.stop(t0 + 0.11);
  } else if (kind === "place") {
    osc.type = "triangle";
    osc.frequency.setValueAtTime(330, t0);
    osc.frequency.exponentialRampToValueAtTime(240, t0 + 0.06);
    gain.gain.setValueAtTime(0.07, t0);
    gain.gain.exponentialRampToValueAtTime(0.001, t0 + 0.07);
    osc.start(t0); osc.stop(t0 + 0.08);
  }
}

// ---------------------------------------------------------------------------
// Input state
// ---------------------------------------------------------------------------
const input = {
  keys: new Set(),
  move: { x: 0, y: 0 },      // keyboard axes
  joy: { x: 0, y: 0 },       // touch joystick
  touchActive: false,
  jumpKeys: false,
  jumpTouch: false,
  descend: false,
  sprintKeys: false,
  sprintTouch: false
};

let playing = false;
let hotbarIndex = 0;

// --- keyboard ---
addEventListener("keydown", (e) => {
  input.keys.add(e.code);
  if (e.code === "Space") {
    const now = performance.now();
    if (now - lastSpaceTap < 280 && playing) toggleFly();
    lastSpaceTap = now;
  }
  if (e.code === "KeyF" && playing) toggleFly();
  if (/^Digit[1-9]$/.test(e.code)) selectSlot(parseInt(e.code[5], 10) - 1);
  if (e.code === "Space") e.preventDefault();
});
addEventListener("keyup", (e) => input.keys.delete(e.code));
let lastSpaceTap = 0;

function toggleFly() {
  player.flying = !player.flying;
  if (player.flying) player.vel.y = 0;
  document.getElementById("btn-fly").classList.toggle("active", player.flying);
  toast(player.flying ? "Flying ON" : "Flying OFF");
}

addEventListener("wheel", (e) => {
  if (!playing) return;
  selectSlot((hotbarIndex + (e.deltaY > 0 ? 1 : -1) + HOTBAR.length) % HOTBAR.length);
});

// --- pointer lock (desktop) ---
const isCoarse = matchMedia("(pointer: coarse)").matches || "ontouchstart" in window;

function tryPointerLock() {
  try {
    const p = canvas.requestPointerLock && canvas.requestPointerLock();
    if (p && typeof p.catch === "function") p.catch(() => { /* headless/no gesture */ });
  } catch (e) { /* ignore */ }
}

canvas.addEventListener("click", () => {
  ensureAudio();
  if (playing && !isCoarse && document.pointerLockElement !== canvas) {
    tryPointerLock();
  }
});

addEventListener("mousemove", (e) => {
  if (document.pointerLockElement !== canvas || !playing) return;
  player.yaw -= e.movementX * 0.0024;
  player.pitch -= e.movementY * 0.0024;
  player.pitch = Math.max(-Math.PI / 2 + 0.01, Math.min(Math.PI / 2 - 0.01, player.pitch));
});

addEventListener("mousedown", (e) => {
  if (document.pointerLockElement !== canvas || !playing) return;
  if (e.button === 0) mineTarget();
  else if (e.button === 2) placeTarget();
});
addEventListener("contextmenu", (e) => e.preventDefault());

// --- touch controls ---
const joyEl = document.getElementById("joystick");
const knobEl = document.getElementById("joystick-knob");
let joyPointer = null;

joyEl.addEventListener("pointerdown", (e) => {
  e.preventDefault();
  joyPointer = e.pointerId;
  joyEl.setPointerCapture(e.pointerId);
  updateJoystick(e);
});
joyEl.addEventListener("pointermove", (e) => {
  if (joyPointer !== e.pointerId) return;
  updateJoystick(e);
});
function endJoystick(e) {
  if (joyPointer !== e.pointerId) return;
  joyPointer = null;
  input.joy.x = 0;
  input.joy.y = 0;
  input.touchActive = false;
  input.sprintTouch = false;
  knobEl.style.transform = "translate(-50%, -50%)";
}
joyEl.addEventListener("pointerup", endJoystick);
joyEl.addEventListener("pointercancel", endJoystick);

function updateJoystick(e) {
  const rect = joyEl.getBoundingClientRect();
  const cx = rect.left + rect.width / 2;
  const cy = rect.top + rect.height / 2;
  let dx = (e.clientX - cx) / (rect.width / 2);
  let dy = (e.clientY - cy) / (rect.height / 2);
  const len = Math.hypot(dx, dy);
  if (len > 1) { dx /= len; dy /= len; }
  input.joy.x = dx;
  input.joy.y = dy;
  input.touchActive = true;
  input.sprintTouch = len > 0.95;
  knobEl.style.transform =
    `translate(calc(-50% + ${dx * 38}px), calc(-50% + ${dy * 38}px))`;
}

// Look drag + tap-to-mine on the canvas
let lookPointer = null;
let lookLast = { x: 0, y: 0 };
let lookStart = { x: 0, y: 0, t: 0 };
let lookMoved = 0;

canvas.addEventListener("pointerdown", (e) => {
  ensureAudio();
  if (!playing) return;
  if (lookPointer !== null) return;
  lookPointer = e.pointerId;
  canvas.setPointerCapture(e.pointerId);
  lookLast = { x: e.clientX, y: e.clientY };
  lookStart = { x: e.clientX, y: e.clientY, t: performance.now() };
  lookMoved = 0;
});
canvas.addEventListener("pointermove", (e) => {
  if (lookPointer !== e.pointerId || !playing) return;
  const dx = e.clientX - lookLast.x;
  const dy = e.clientY - lookLast.y;
  lookLast = { x: e.clientX, y: e.clientY };
  lookMoved += Math.abs(dx) + Math.abs(dy);
  const scale = isXRActive() ? 0.0038 : 0.0048;
  player.yaw -= dx * scale;
  player.pitch -= dy * scale;
  player.pitch = Math.max(-Math.PI / 2 + 0.01, Math.min(Math.PI / 2 - 0.01, player.pitch));
});
function endLook(e) {
  if (lookPointer !== e.pointerId) return;
  lookPointer = null;
  const quick = performance.now() - lookStart.t < 260 && lookMoved < 12;
  if (quick && playing) mineTarget();
}
canvas.addEventListener("pointerup", endLook);
canvas.addEventListener("pointercancel", endLook);

// Buttons
const btnJump = document.getElementById("btn-jump");
btnJump.addEventListener("pointerdown", (e) => {
  e.preventDefault();
  input.jumpTouch = true;
});
btnJump.addEventListener("pointerup", () => { input.jumpTouch = false; });
btnJump.addEventListener("pointercancel", () => { input.jumpTouch = false; });

document.getElementById("btn-place").addEventListener("click", (e) => {
  e.preventDefault();
  if (playing) placeTarget();
});
document.getElementById("btn-fly").addEventListener("click", (e) => {
  e.preventDefault();
  if (playing) toggleFly();
});

function applyKeyboardAxes() {
  const k = input.keys;
  input.move.x = (k.has("KeyD") || k.has("ArrowRight") ? 1 : 0) -
    (k.has("KeyA") || k.has("ArrowLeft") ? 1 : 0);
  input.move.y = (k.has("KeyW") || k.has("ArrowUp") ? 1 : 0) -
    (k.has("KeyS") || k.has("ArrowDown") ? 1 : 0);
  input.jumpKeys = k.has("Space");
  input.descend = k.has("ShiftLeft") && player.flying;
  input.sprintKeys = k.has("ShiftLeft");
}

// ---------------------------------------------------------------------------
// Toast (declared early: used during hotbar construction)
// ---------------------------------------------------------------------------
const toastEl = document.getElementById("toast");
let toastTimer = 0;
function toast(message) {
  toastEl.textContent = message;
  toastEl.classList.add("show");
  toastTimer = 1.6;
}

// ---------------------------------------------------------------------------
// Hotbar UI
// ---------------------------------------------------------------------------
const hotbarEl = document.getElementById("hotbar");
const slotEls = [];
HOTBAR.forEach((id, i) => {
  const slot = document.createElement("div");
  slot.className = "slot";
  const cv = document.createElement("canvas");
  cv.width = cv.height = TILE;
  const cx = cv.getContext("2d");
  const tile = BLOCK_INFO[id].tiles[0];
  cx.drawImage(atlasCanvas,
    (tile % ATLAS_TILES) * TILE, Math.floor(tile / ATLAS_TILES) * TILE, TILE, TILE,
    0, 0, TILE, TILE);
  slot.appendChild(cv);
  slot.title = BLOCK_INFO[id].name;
  slot.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    e.stopPropagation();
    selectSlot(i);
  });
  hotbarEl.appendChild(slot);
  slotEls.push(slot);
});
function selectSlot(i) {
  hotbarIndex = i;
  slotEls.forEach((el, j) => el.classList.toggle("selected", j === i));
  const info = BLOCK_INFO[HOTBAR[i]];
  if (info) toast(info.name);
}
selectSlot(0);

// ---------------------------------------------------------------------------
// XR session management
// ---------------------------------------------------------------------------
let xrSession = null;
let baseRefSpace = null;

function isXRActive() {
  return renderer.xr.isPresenting;
}

async function enterXR() {
  ensureAudio();
  if (isXRActive()) return;
  if (!navigator.xr) {
    toast("WebXR not available in this browser");
    return;
  }
  try {
    const supported = await navigator.xr.isSessionSupported("immersive-vr");
    if (!supported) {
      toast("This device cannot start immersive 3D sessions");
      return;
    }
    console.log("[mc3d] requesting XR session");
    xrSession = await navigator.xr.requestSession("immersive-vr", {
      optionalFeatures: ["local-floor"]
    });
    console.log("[mc3d] session granted, handing to three.js");
    await renderer.xr.setSession(xrSession);
    console.log("[mc3d] setSession done");
    startPlaying();
  } catch (e) {
    console.error("[mc3d] enterXR failed", e);
    toast("Could not start 3D: " + (e && e.message ? e.message : e));
  }
}

function exitXR() {
  if (xrSession) {
    try { xrSession.end(); } catch (e) { /* ignore */ }
  }
}

renderer.xr.addEventListener("sessionstart", () => {
  baseRefSpace = renderer.xr.getReferenceSpace();
  document.getElementById("btn-exit-xr").classList.remove("hidden");
  toast("3D on — mind the sweet spot");
  updateXrTransport(true);
});
renderer.xr.addEventListener("sessionend", () => {
  document.getElementById("btn-exit-xr").classList.add("hidden");
  camera.fov = 70;
  camera.updateProjectionMatrix();
  onResize();
});

const _xrEuler = new THREE.Euler(0, 0, 0, "YXZ");
const _xrQuat = new THREE.Quaternion();
function updateXrTransport(force) {
  if (!isXRActive() || !baseRefSpace) return;
  if (!force && !playerMovedSinceXrUpdate && !playerRotatedSinceXrUpdate) return;
  playerMovedSinceXrUpdate = false;
  playerRotatedSinceXrUpdate = false;
  _xrEuler.set(player.pitch, player.yaw, 0);
  _xrQuat.setFromEuler(_xrEuler);
  const t = new XRRigidTransform(
    { x: player.pos.x, y: player.pos.y, z: player.pos.z },
    { x: _xrQuat.x, y: _xrQuat.y, z: _xrQuat.z, w: _xrQuat.w });
  try {
    renderer.xr.setReferenceSpace(baseRefSpace.getOffsetReferenceSpace(t.inverse));
  } catch (e) {
    console.warn("transport update failed", e);
  }
}
let playerMovedSinceXrUpdate = false;
let playerRotatedSinceXrUpdate = false;

// ---------------------------------------------------------------------------
// UI: splash, menu
// ---------------------------------------------------------------------------
const splashEl = document.getElementById("splash");
const menuEl = document.getElementById("menu");

function startPlaying() {
  playing = true;
  splashEl.classList.add("hidden");
  menuEl.classList.add("hidden");
  if (!isCoarse) tryPointerLock();
  if (!isCoarse) document.getElementById("touch-ui").classList.add("hidden");
  else document.getElementById("touch-ui").classList.remove("hidden");
}

document.getElementById("btn-play").addEventListener("click", () => {
  ensureAudio();
  startPlaying();
});
document.getElementById("btn-play-xr").addEventListener("click", enterXR);
document.getElementById("btn-enter-xr").addEventListener("click", enterXR);
document.getElementById("btn-resume").addEventListener("click", startPlaying);
document.getElementById("btn-menu").addEventListener("click", () => {
  playing = false;
  menuEl.classList.remove("hidden");
  saveWorld();
  if (document.exitPointerLock) document.exitPointerLock();
});
document.getElementById("btn-exit-xr").addEventListener("click", exitXR);
document.getElementById("btn-new-world").addEventListener("click", resetWorld);
document.getElementById("btn-reset").addEventListener("click", resetWorld);

function resetWorld() {
  try { localStorage.removeItem(SAVE_KEY); } catch (e) { /* ignore */ }
  location.reload();
}

// XR availability hint
(function () {
  const hint = document.getElementById("xr-hint");
  if (window.NativeLeia && window.NativeLeia.isLeiaDevice &&
      window.NativeLeia.isLeiaDevice()) {
    hint.textContent = "Leia display detected \u2014 tap PLAY IN 3D for glasses-free stereo.";
  } else if (navigator.xr) {
    navigator.xr.isSessionSupported("immersive-vr").then((ok) => {
      hint.textContent = ok
        ? "This browser can start immersive 3D sessions."
        : "No immersive 3D support detected; running flat.";
    }).catch(() => { /* ignore */ });
  } else {
    hint.textContent = "No WebXR support detected; running flat.";
  }
  const info = document.getElementById("device-info");
  if (window.NativeLeia && window.NativeLeia.deviceInfo) {
    info.textContent = window.NativeLeia.deviceInfo();
  }
})();

// ---------------------------------------------------------------------------
// Resize
// ---------------------------------------------------------------------------
function onResize() {
  if (isXRActive()) return; // three.js manages the XR framebuffer size
  const w = window.innerWidth;
  const h = window.innerHeight;
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
  const dpr = Math.min(window.devicePixelRatio || 1, DPR_CAP);
  renderer.setPixelRatio(dpr);
  renderer.setSize(w, h, false);
}
addEventListener("resize", onResize);
onResize();

// ---------------------------------------------------------------------------
// Debug overlay
// ---------------------------------------------------------------------------
const debugEl = document.getElementById("debug");
const DEBUG = params.get("debug") === "1";
if (DEBUG) debugEl.classList.remove("hidden");
let fpsAccum = 0, fpsFrames = 0, fpsValue = 0;

// ---------------------------------------------------------------------------
// Boot & main loop
// ---------------------------------------------------------------------------
const savedData = loadWorld();
buildAllChunks();

if (savedData && savedData.player) {
  const p = savedData.player;
  if (typeof p.x === "number" && isFinite(p.x)) {
    player.pos.set(p.x, p.y, p.z);
    player.yaw = p.yaw || 0;
    player.pitch = p.pitch || 0;
  }
  if (typeof savedData.player.slot === "number" &&
      savedData.player.slot >= 0 && savedData.player.slot < HOTBAR.length) {
    selectSlot(savedData.player.slot);
  }
}
// Make sure the player does not spawn inside blocks
(function () {
  const x = Math.floor(player.pos.x), z = Math.floor(player.pos.z);
  let y = Math.min(WORLD_Y - 2, Math.floor(player.pos.y));
  while (y < WORLD_Y - 2 &&
         (isSolid(getBlock(x, y, z)) || isSolid(getBlock(x, y + 1, z)))) y++;
  player.pos.y = y + 0.01;
})();

addEventListener("pagehide", saveWorld);
document.addEventListener("visibilitychange", () => {
  if (document.hidden) saveWorld();
});

let lastTime = performance.now();

function update(dt) {
  applyKeyboardAxes();
  const beforeX = player.pos.x, beforeY = player.pos.y, beforeZ = player.pos.z;
  const beforeYaw = player.yaw, beforePitch = player.pitch;
  updatePlayer(dt);

  if (player.pos.x !== beforeX || player.pos.y !== beforeY || player.pos.z !== beforeZ) {
    playerMovedSinceXrUpdate = true;
  }
  if (player.yaw !== beforeYaw || player.pitch !== beforePitch) {
    playerRotatedSinceXrUpdate = true;
  }

  // Camera (mono mode; in XR three.js drives the camera from the XR pose)
  if (!isXRActive()) {
    camera.position.set(
      player.pos.x, player.pos.y + EYE_HEIGHT, player.pos.z);
    camera.rotation.set(player.pitch, player.yaw, 0);
  } else {
    updateXrTransport(false);
  }

  // Clouds drift
  clouds.position.x += dt * 1.1;
  if (clouds.position.x > WORLD_X) clouds.position.x -= WORLD_X;

  // Underwater tint
  const headBlock = getBlock(
    Math.floor(camera.position.x), Math.floor(camera.position.y),
    Math.floor(camera.position.z));
  document.getElementById("water-overlay").style.display =
    headBlock === B.WATER ? "block" : "none";

  // Block highlight
  if (playing) {
    const hit = currentTarget();
    if (hit) {
      highlight.visible = true;
      highlight.position.set(hit.x + 0.5, hit.y + 0.5, hit.z + 0.5);
    } else {
      highlight.visible = false;
    }
  } else {
    highlight.visible = false;
  }

  processDirtyChunks();

  if (saveTimer > 0) {
    saveTimer -= dt;
    if (saveTimer <= 0) saveWorld();
  }
  if (toastTimer > 0) {
    toastTimer -= dt;
    if (toastTimer <= 0) toastEl.classList.remove("show");
  }
}

renderer.setAnimationLoop((time) => {
  const dt = Math.min(0.05, Math.max(0.0001, (time - lastTime) / 1000));
  lastTime = time;
  update(dt);
  renderer.render(scene, camera);

  if (DEBUG) {
    fpsAccum += dt; fpsFrames++;
    if (fpsAccum >= 0.5) {
      fpsValue = Math.round(fpsFrames / fpsAccum);
      fpsAccum = 0; fpsFrames = 0;
    }
    debugEl.textContent =
      `fps ${fpsValue}\n` +
      `pos ${player.pos.x.toFixed(1)} ${player.pos.y.toFixed(1)} ${player.pos.z.toFixed(1)}\n` +
      `xr ${isXRActive() ? "ON" : "off"}\n` +
      `dpr ${renderer.getPixelRatio().toFixed(2)}`;
  }
});
