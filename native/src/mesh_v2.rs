//! WOM2 snapshot meshing.
//!
//! The Java side captures vanilla's exact section vertex stream (already in
//! the 28-byte `DefaultVertexFormat.BLOCK` layout, section-local positions,
//! quad-major) and hands it to Rust. Rust validates it and emits two streams
//! per layer:
//!
//! * a **passthrough** stream — the input quads, byte-identical, drawn through
//!   Minecraft's own terrain pipelines (`SOLID_TERRAIN` / `CUTOUT_TERRAIN` /
//!   `TRANSLUCENT_TERRAIN`) with the block atlas and lightmap. This is the
//!   default and is pixel-identical to vanilla by construction.
//! * a **merged** stream — cube-face runs greedily merged into maximal
//!   rectangles. Merged vertices use a 44-byte tiled layout: the vanilla BLOCK
//!   fields plus a per-vertex sprite rectangle, with UV0 stored in *block
//!   units* (the vertex's geometric in-plane block coordinate). A tiled shader
//!   reconstructs per-block texturing exactly:
//!   `atlasUV = spriteRect.xy + fract(uv) * spriteRect.zw`, where a flipped
//!   face orientation is encoded as a negative sprite width/height. The
//!   per-fragment `fract` sawtooth lands on exactly the per-unit texels (the
//!   terrain shader's texel-center snapping hides the measure-zero corner
//!   point where fract of an integer is 0), so merged output is
//!   pixel-identical to the unmerged input whenever the merge predicate holds.
//!   Merging is only emitted when the snapshot header enables it.
//!
//! Merge predicate (all must hold for every quad in a run):
//! * the quad is an axis-aligned unit quad on an integer plane,
//! * its corner order matches vanilla's `FaceInfo` order for its direction,
//! * all four corner colors and packed lights are identical, and
//! * its four corner UVs are the four corners of one rectangle whose u/v axes
//!   align with the face's in-plane axes (flips allowed, encoded in the sprite
//!   rect), shared by the whole run.
//!
//! Under these conditions the merged quad covers the same plane, samples the
//! same texels and carries the same corner color/light, so the result is
//! visually identical to vanilla.

use super::*;
use std::collections::HashMap;

pub const V2_MAGIC: u32 = u32::from_ne_bytes(*b"WOM2");
pub const V2_VERSION: u16 = 2;
pub const V2_HEADER_BYTES: usize = 64;
pub const V2_LAYER_COUNT: usize = 3;
pub const V2_LAYER_TABLE_BYTES: usize = 16;
pub const V2_OUTPUT_HEADER_BYTES: usize = 32;
pub const V2_OUTPUT_LAYER_TABLE_BYTES: usize = 24;
pub const BLOCK_VERTEX_BYTES: usize = 28;
pub const TILED_VERTEX_BYTES: usize = 44;
pub const MAX_VERTICES_PER_LAYER: usize = 131_072;
pub const MAX_INPUT_BYTES: usize = 4 * 1024 * 1024;

pub const LAYER_ID_SOLID: u8 = 0;
pub const LAYER_ID_CUTOUT: u8 = 1;
pub const LAYER_ID_TRANSLUCENT: u8 = 2;
pub const LAYER_IDS: [u8; V2_LAYER_COUNT] = [LAYER_ID_SOLID, LAYER_ID_CUTOUT, LAYER_ID_TRANSLUCENT];

const FLAG_MERGE_ENABLED: u16 = 1;

const ERR_V2_LAYER: jint = -10;
const ERR_V2_VERTEX: jint = -11;

/// Hard cap for one mesh result (matches the queued-result accounting limit in
/// async_jobs.rs, which bounds the sum across all in-flight jobs).
const MAX_OUTPUT_BYTES: usize = 32 * 1024 * 1024;

// Direction ordinals match net.minecraft.core.Direction:
// DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4, EAST=5.
const DIR_AXIS: [usize; 6] = [1, 1, 2, 2, 0, 0];
const DIR_POSITIVE: [bool; 6] = [false, true, false, true, false, true];
// In-plane span axes (i0, i1) per direction.
const DIR_SPAN: [(usize, usize); 6] = [
    (0, 2), // DOWN:  X, Z
    (0, 2), // UP:    X, Z
    (0, 1), // NORTH: X, Y
    (0, 1), // SOUTH: X, Y
    (1, 2), // WEST:  Y, Z
    (1, 2), // EAST:  Y, Z
];
// Vanilla FaceInfo corner patterns: per direction, per corner, the rectangle
// extent choice (0 = min, 1 = max) along each span axis.
const FACE_CORNERS: [[(u8, u8); 4]; 6] = [
    [(0, 1), (0, 0), (1, 0), (1, 1)], // DOWN  (x, z)
    [(0, 0), (0, 1), (1, 1), (1, 0)], // UP    (x, z)
    [(1, 1), (1, 0), (0, 0), (0, 1)], // NORTH (x, y)
    [(0, 1), (0, 0), (1, 0), (1, 1)], // SOUTH (x, y)
    [(1, 0), (0, 0), (0, 1), (1, 1)], // WEST  (y, z)
    [(1, 1), (0, 1), (0, 0), (1, 0)], // EAST  (y, z)
];

#[derive(Clone, Copy)]
struct V2Vertex {
    pos: [f32; 3],
    color: [u8; 4],
    uv: [f32; 2],
    light: [i16; 2],
}

#[derive(Clone, Copy)]
struct V2Quad {
    v: [V2Vertex; 4],
}

struct LayerInput {
    layer_id: u8,
    quads: Vec<V2Quad>,
}

struct LayerOutput {
    layer_id: u8,
    passthrough: Vec<V2Quad>,
    merged: Vec<MergedQuad>,
}

#[derive(Clone, Copy)]
struct MergedVertex {
    pos: [f32; 3],
    color: [u8; 4],
    uv: [f32; 2], // block units (geometric in-plane block coordinates)
    light: [i16; 2],
    sprite: [f32; 4], // u0, v0, signed width, signed height in atlas UV units
}

#[derive(Clone, Copy)]
struct MergedQuad {
    v: [MergedVertex; 4],
}

/// One merge candidate: an axis-aligned unit quad eligible for greedy merging.
struct Candidate {
    dir: usize,
    plane: i32,
    i0: i32,
    i1: i32,
    color: [u8; 4],
    light: [i16; 2],
    /// Final sprite rect with flips encoded as negative extents:
    /// (uBase, vBase, signedW, signedH).
    sprite: [f32; 4],
    uv_pattern: [[f32; 2]; 4],
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
struct GroupKey {
    dir: u8,
    plane: i32,
    color: [u8; 4],
    light: [i16; 2],
    sprite_bits: [u32; 4],
    uv_pattern_bits: [u32; 8],
}

fn f32_bits(value: f32) -> u32 {
    value.to_bits()
}

fn parse_vertex(bytes: &[u8], offset: usize) -> Result<V2Vertex, jint> {
    if offset + BLOCK_VERTEX_BYTES > bytes.len() {
        return Err(ERR_V2_VERTEX);
    }
    let mut pos = [0f32; 3];
    let mut uv = [0f32; 2];
    for (index, slot) in pos.iter_mut().enumerate() {
        *slot = f32::from_ne_bytes([
            bytes[offset + index * 4],
            bytes[offset + index * 4 + 1],
            bytes[offset + index * 4 + 2],
            bytes[offset + index * 4 + 3],
        ]);
    }
    for (index, slot) in uv.iter_mut().enumerate() {
        *slot = f32::from_ne_bytes([
            bytes[offset + 16 + index * 4],
            bytes[offset + 16 + index * 4 + 1],
            bytes[offset + 16 + index * 4 + 2],
            bytes[offset + 16 + index * 4 + 3],
        ]);
    }
    let color = [
        bytes[offset + 12],
        bytes[offset + 13],
        bytes[offset + 14],
        bytes[offset + 15],
    ];
    let light = [
        i16::from_ne_bytes([bytes[offset + 24], bytes[offset + 25]]),
        i16::from_ne_bytes([bytes[offset + 26], bytes[offset + 27]]),
    ];
    if !pos.iter().all(|value| value.is_finite()) || !uv.iter().all(|value| value.is_finite()) {
        return Err(ERR_V2_VERTEX);
    }
    Ok(V2Vertex {
        pos,
        color,
        uv,
        light,
    })
}

fn parse_layer_quads(bytes: &[u8], vertex_count: usize) -> Result<Vec<V2Quad>, jint> {
    if vertex_count % 4 != 0 {
        return Err(ERR_V2_VERTEX);
    }
    if vertex_count > MAX_VERTICES_PER_LAYER {
        return Err(ERR_V2_VERTEX);
    }
    let quad_count = vertex_count / 4;
    let mut quads = Vec::with_capacity(quad_count);
    for quad in 0..quad_count {
        let base = quad * 4 * BLOCK_VERTEX_BYTES;
        let mut vertices = [V2Vertex {
            pos: [0.0; 3],
            color: [0; 4],
            uv: [0.0; 2],
            light: [0; 2],
        }; 4];
        for vertex in 0..4 {
            vertices[vertex] = parse_vertex(bytes, base + vertex * BLOCK_VERTEX_BYTES)?;
        }
        quads.push(V2Quad { v: vertices });
    }
    Ok(quads)
}

fn parse_input(input: &[u8]) -> Result<(bool, Vec<LayerInput>), jint> {
    if input.len() < V2_HEADER_BYTES {
        return Err(ERR_BAD_HEADER);
    }
    if read_u32(input, 0) != Some(V2_MAGIC) {
        return Err(ERR_BAD_HEADER);
    }
    if read_u16(input, 4) != Some(V2_VERSION) {
        return Err(ERR_BAD_HEADER);
    }
    if read_u16(input, 6) != Some(V2_HEADER_BYTES as u16) {
        return Err(ERR_BAD_HEADER);
    }
    let layer_count = read_u16(input, 8).ok_or(ERR_BAD_HEADER)? as usize;
    if layer_count != V2_LAYER_COUNT {
        return Err(ERR_BAD_HEADER);
    }
    let flags = read_u16(input, 10).ok_or(ERR_BAD_HEADER)?;
    let merge_enabled = flags & FLAG_MERGE_ENABLED != 0;
    let layer_table_offset = read_u32(input, 24).ok_or(ERR_BAD_HEADER)? as usize;
    let total_bytes = read_u32(input, 32).ok_or(ERR_BAD_HEADER)? as usize;
    if layer_table_offset != V2_HEADER_BYTES {
        return Err(ERR_BAD_HEADER);
    }
    if total_bytes < V2_HEADER_BYTES || total_bytes > input.len() || total_bytes > MAX_INPUT_BYTES {
        return Err(ERR_BAD_HEADER);
    }
    let table_end = layer_table_offset + V2_LAYER_COUNT * V2_LAYER_TABLE_BYTES;
    if table_end > total_bytes {
        return Err(ERR_BAD_HEADER);
    }

    let mut layers = Vec::with_capacity(V2_LAYER_COUNT);
    for slot in 0..V2_LAYER_COUNT {
        let entry = layer_table_offset + slot * V2_LAYER_TABLE_BYTES;
        let layer_id = input[entry];
        if layer_id != LAYER_IDS[slot] {
            return Err(ERR_V2_LAYER);
        }
        let vertex_count = read_u32(input, entry + 4).ok_or(ERR_V2_LAYER)? as usize;
        let vertex_bytes = read_u32(input, entry + 8).ok_or(ERR_V2_LAYER)? as usize;
        let data_offset = read_u32(input, entry + 12).ok_or(ERR_V2_LAYER)? as usize;
        if vertex_count > MAX_VERTICES_PER_LAYER {
            return Err(ERR_V2_LAYER);
        }
        let expected_bytes = vertex_count
            .checked_mul(BLOCK_VERTEX_BYTES)
            .ok_or(ERR_V2_LAYER)?;
        if vertex_bytes != expected_bytes {
            return Err(ERR_V2_LAYER);
        }
        if vertex_count == 0 {
            layers.push(LayerInput {
                layer_id,
                quads: Vec::new(),
            });
            continue;
        }
        if data_offset < table_end || data_offset + vertex_bytes > total_bytes {
            return Err(ERR_V2_LAYER);
        }
        let quads = parse_layer_quads(
            &input[data_offset..data_offset + vertex_bytes],
            vertex_count,
        )?;
        layers.push(LayerInput { layer_id, quads });
    }
    Ok((merge_enabled, layers))
}

fn sub(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}

fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}

fn dir_for(axis: usize, positive: bool) -> usize {
    (0..6)
        .find(|&dir| DIR_AXIS[dir] == axis && DIR_POSITIVE[dir] == positive)
        .unwrap()
}

/// Classify a quad as a merge candidate, or None when it must pass through.
fn classify(quad: &V2Quad) -> Option<Candidate> {
    let mut min = [f32::INFINITY; 3];
    let mut max = [f32::NEG_INFINITY; 3];
    for vertex in &quad.v {
        for axis in 0..3 {
            min[axis] = min[axis].min(vertex.pos[axis]);
            max[axis] = max[axis].max(vertex.pos[axis]);
        }
    }

    // Exactly one constant axis (the face plane); both other axes must span
    // exactly one block with integer bounds inside the section.
    let mut const_axis = None;
    for axis in 0..3 {
        if min[axis] == max[axis] {
            if const_axis.is_some() {
                return None;
            }
            const_axis = Some(axis);
        }
    }
    let const_axis = const_axis?;
    if min[const_axis].fract() != 0.0 || !(0.0..=16.0).contains(&min[const_axis]) {
        return None;
    }
    let mut span_axes = [0usize; 2];
    let mut fill = 0;
    for axis in 0..3 {
        if axis != const_axis {
            span_axes[fill] = axis;
            fill += 1;
        }
    }
    for &axis in &span_axes {
        if max[axis] - min[axis] != 1.0 {
            return None;
        }
        if min[axis].fract() != 0.0 || !(0.0..=15.0).contains(&min[axis]) {
            return None;
        }
    }

    // Uniform corner color and light across the whole run is required so the
    // merged quad is pixel-identical to the per-unit quads.
    let color = quad.v[0].color;
    let light = quad.v[0].light;
    for vertex in &quad.v[1..] {
        if vertex.color != color || vertex.light != light {
            return None;
        }
    }

    // The four corner UVs must be the four corners of one rectangle.
    let mut us = [0f32; 4];
    let mut vs = [0f32; 4];
    for (index, vertex) in quad.v.iter().enumerate() {
        us[index] = vertex.uv[0];
        vs[index] = vertex.uv[1];
    }
    let u0 = us.iter().cloned().fold(f32::INFINITY, f32::min);
    let u1 = us.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    let v0 = vs.iter().cloned().fold(f32::INFINITY, f32::min);
    let v1 = vs.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    if !(u1 > u0) || !(v1 > v0) {
        return None;
    }
    for index in 0..4 {
        let on_u_edge = us[index] == u0 || us[index] == u1;
        let on_v_edge = vs[index] == v0 || vs[index] == v1;
        if !on_u_edge || !on_v_edge {
            return None;
        }
    }

    // Winding must give an axis normal; derive the face direction from it.
    let normal = cross(
        sub(quad.v[1].pos, quad.v[0].pos),
        sub(quad.v[3].pos, quad.v[0].pos),
    );
    for axis in 0..3 {
        if axis != const_axis && normal[axis] != 0.0 {
            return None;
        }
    }
    if normal[const_axis] == 0.0 {
        return None;
    }
    let dir = dir_for(const_axis, normal[const_axis] > 0.0);

    // Corner order must match vanilla's FaceInfo for this direction, otherwise
    // re-emitting the merged quad in FaceInfo order would permute the UVs.
    let i0 = min[span_axes[0]] as i32;
    let i1 = min[span_axes[1]] as i32;
    for k in 0..4 {
        let (want_a, want_b) = FACE_CORNERS[dir][k];
        let off_a = quad.v[k].pos[span_axes[0]] - min[span_axes[0]];
        let off_b = quad.v[k].pos[span_axes[1]] - min[span_axes[1]];
        let got_a = if off_a > 0.5 { 1u8 } else { 0u8 };
        let got_b = if off_b > 0.5 { 1u8 } else { 0u8 };
        if got_a != want_a || got_b != want_b {
            return None;
        }
    }

    // UV orientation: u must depend only on the span0 choice and v only on the
    // span1 choice (never swapped), with flips allowed. Vanilla's default cube
    // faces flip per direction, so both flip combinations must be supported.
    let mut u_at_a0 = None;
    let mut u_at_a1 = None;
    let mut v_at_b0 = None;
    let mut v_at_b1 = None;
    for k in 0..4 {
        let (choice_a, choice_b) = FACE_CORNERS[dir][k];
        let u_slot = if choice_a == 0 {
            &mut u_at_a0
        } else {
            &mut u_at_a1
        };
        match u_slot {
            None => *u_slot = Some(us[k]),
            Some(existing) if *existing != us[k] => return None,
            _ => {}
        }
        let v_slot = if choice_b == 0 {
            &mut v_at_b0
        } else {
            &mut v_at_b1
        };
        match v_slot {
            None => *v_slot = Some(vs[k]),
            Some(existing) if *existing != vs[k] => return None,
            _ => {}
        }
    }
    let (u_at_a0, u_at_a1) = (u_at_a0?, u_at_a1?);
    let (v_at_b0, v_at_b1) = (v_at_b0?, v_at_b1?);
    if (u_at_a0 == u_at_a1) || (v_at_b0 == v_at_b1) {
        return None;
    }
    if (u_at_a0 != u0 && u_at_a0 != u1) || (u_at_a1 != u0 && u_at_a1 != u1) {
        return None;
    }
    if (v_at_b0 != v0 && v_at_b0 != v1) || (v_at_b1 != v0 && v_at_b1 != v1) {
        return None;
    }
    // Flips encoded as a negative sprite extent; the shader reconstructs
    // atlasUV = sprite.xy + fract(blockUV) * sprite.zw.
    let flip_u = u_at_a0 == u1;
    let flip_v = v_at_b0 == v1;
    let sprite = [
        if flip_u { u1 } else { u0 },
        if flip_v { v1 } else { v0 },
        if flip_u { u0 - u1 } else { u1 - u0 },
        if flip_v { v0 - v1 } else { v1 - v0 },
    ];

    let mut uv_pattern = [[0f32; 2]; 4];
    for k in 0..4 {
        uv_pattern[k] = quad.v[k].uv;
    }
    Some(Candidate {
        dir,
        plane: min[const_axis] as i32,
        i0,
        i1,
        color,
        light,
        sprite,
        uv_pattern,
    })
}

fn group_key(candidate: &Candidate) -> GroupKey {
    GroupKey {
        dir: candidate.dir as u8,
        plane: candidate.plane,
        color: candidate.color,
        light: candidate.light,
        sprite_bits: [
            f32_bits(candidate.sprite[0]),
            f32_bits(candidate.sprite[1]),
            f32_bits(candidate.sprite[2]),
            f32_bits(candidate.sprite[3]),
        ],
        uv_pattern_bits: [
            f32_bits(candidate.uv_pattern[0][0]),
            f32_bits(candidate.uv_pattern[0][1]),
            f32_bits(candidate.uv_pattern[1][0]),
            f32_bits(candidate.uv_pattern[1][1]),
            f32_bits(candidate.uv_pattern[2][0]),
            f32_bits(candidate.uv_pattern[2][1]),
            f32_bits(candidate.uv_pattern[3][0]),
            f32_bits(candidate.uv_pattern[3][1]),
        ],
    }
}

/// Greedy maximal-rectangle decomposition of a cell set on a 2D grid.
/// Returns rectangles as (min_a, min_b, max_a, max_b) inclusive cell bounds.
fn merge_rectangles(cells: &mut Vec<(i32, i32)>) -> Vec<(i32, i32, i32, i32)> {
    cells.sort_unstable();
    cells.dedup();
    let mut occupied: HashMap<(i32, i32), ()> = HashMap::with_capacity(cells.len());
    for &cell in cells.iter() {
        occupied.insert(cell, ());
    }
    let mut rectangles = Vec::new();
    let mut index = 0;
    while index < cells.len() {
        let (start_a, start_b) = cells[index];
        if !occupied.contains_key(&(start_a, start_b)) {
            index += 1;
            continue;
        }
        // Extend along axis a while contiguous cells exist.
        let mut max_a = start_a;
        while occupied.contains_key(&(max_a + 1, start_b)) {
            max_a += 1;
        }
        // Extend along axis b while every row is fully present.
        let mut max_b = start_b;
        'rows: while occupied.contains_key(&(start_a, max_b + 1)) {
            for a in start_a..=max_a {
                if !occupied.contains_key(&(a, max_b + 1)) {
                    break 'rows;
                }
            }
            max_b += 1;
        }
        for a in start_a..=max_a {
            for b in start_b..=max_b {
                occupied.remove(&(a, b));
            }
        }
        rectangles.push((start_a, start_b, max_a, max_b));
        index += 1;
    }
    rectangles
}

fn emit_merged_quad(
    candidate: &Candidate,
    min_a: i32,
    min_b: i32,
    max_a: i32,
    max_b: i32,
) -> MergedQuad {
    let dir = candidate.dir;
    let const_axis = DIR_AXIS[dir];
    let (span0, span1) = DIR_SPAN[dir];
    let mut vertices = [MergedVertex {
        pos: [0.0; 3],
        color: [0; 4],
        uv: [0.0; 2],
        light: [0; 2],
        sprite: [0.0; 4],
    }; 4];
    for k in 0..4 {
        let (choice_a, choice_b) = FACE_CORNERS[dir][k];
        let mut pos = [0f32; 3];
        pos[const_axis] = candidate.plane as f32;
        pos[span0] = if choice_a == 0 { min_a } else { max_a + 1 } as f32;
        pos[span1] = if choice_b == 0 { min_b } else { max_b + 1 } as f32;
        vertices[k] = MergedVertex {
            pos,
            color: candidate.color,
            // Block-unit UV: the corner's geometric in-plane block coordinate.
            // The shader sawtooths it into the sprite rect.
            uv: [pos[span0], pos[span1]],
            light: candidate.light,
            sprite: candidate.sprite,
        };
    }
    MergedQuad { v: vertices }
}

fn mesh_layer(layer: LayerInput, merge_enabled: bool) -> LayerOutput {
    let mut passthrough = Vec::new();
    let mut groups: HashMap<GroupKey, (Candidate, Vec<(i32, i32)>)> = HashMap::new();
    for quad in layer.quads {
        match classify(&quad) {
            Some(candidate) if merge_enabled => {
                let cell = (candidate.i0, candidate.i1);
                let key = group_key(&candidate);
                groups
                    .entry(key)
                    .or_insert_with(|| (candidate, Vec::new()))
                    .1
                    .push(cell);
            }
            _ => passthrough.push(quad),
        }
    }

    let mut merged = Vec::new();
    // HashMap iteration order is nondeterministic; sort the groups so the
    // output bytes are reproducible for identical input.
    let mut sorted_groups: Vec<(GroupKey, Candidate, Vec<(i32, i32)>)> = groups
        .into_iter()
        .map(|(key, (candidate, cells))| (key, candidate, cells))
        .collect();
    sorted_groups.sort_by(|a, b| {
        (
            a.0.dir,
            a.0.plane,
            a.0.color,
            a.0.light,
            a.0.sprite_bits,
            a.0.uv_pattern_bits,
        )
            .cmp(&(
                b.0.dir,
                b.0.plane,
                b.0.color,
                b.0.light,
                b.0.sprite_bits,
                b.0.uv_pattern_bits,
            ))
    });
    for (_, candidate, mut cells) in sorted_groups {
        for (min_a, min_b, max_a, max_b) in merge_rectangles(&mut cells) {
            merged.push(emit_merged_quad(&candidate, min_a, min_b, max_a, max_b));
        }
    }
    LayerOutput {
        layer_id: layer.layer_id,
        passthrough,
        merged,
    }
}

fn write_block_vertex(out: &mut Vec<u8>, vertex: &V2Vertex) {
    for axis in 0..3 {
        out.extend_from_slice(&vertex.pos[axis].to_ne_bytes());
    }
    out.extend_from_slice(&vertex.color);
    out.extend_from_slice(&vertex.uv[0].to_ne_bytes());
    out.extend_from_slice(&vertex.uv[1].to_ne_bytes());
    out.extend_from_slice(&vertex.light[0].to_ne_bytes());
    out.extend_from_slice(&vertex.light[1].to_ne_bytes());
}

fn write_block_vertex_at(out: &mut [u8], offset: usize, vertex: &V2Vertex) {
    if offset + BLOCK_VERTEX_BYTES > out.len() {
        return;
    }
    for (axis, component) in vertex.pos.iter().enumerate() {
        out[offset + axis * 4..offset + axis * 4 + 4].copy_from_slice(&component.to_ne_bytes());
    }
    out[offset + 12..offset + 16].copy_from_slice(&vertex.color);
    out[offset + 16..offset + 20].copy_from_slice(&vertex.uv[0].to_ne_bytes());
    out[offset + 20..offset + 24].copy_from_slice(&vertex.uv[1].to_ne_bytes());
    out[offset + 24..offset + 26].copy_from_slice(&vertex.light[0].to_ne_bytes());
    out[offset + 26..offset + 28].copy_from_slice(&vertex.light[1].to_ne_bytes());
}

fn write_tiled_vertex(out: &mut Vec<u8>, vertex: &MergedVertex) {
    for axis in 0..3 {
        out.extend_from_slice(&vertex.pos[axis].to_ne_bytes());
    }
    out.extend_from_slice(&vertex.color);
    out.extend_from_slice(&vertex.uv[0].to_ne_bytes());
    out.extend_from_slice(&vertex.uv[1].to_ne_bytes());
    out.extend_from_slice(&vertex.light[0].to_ne_bytes());
    out.extend_from_slice(&vertex.light[1].to_ne_bytes());
    for component in vertex.sprite {
        out.extend_from_slice(&component.to_ne_bytes());
    }
}

fn write_tiled_vertex_at(out: &mut [u8], offset: usize, vertex: &MergedVertex) {
    if offset + TILED_VERTEX_BYTES > out.len() {
        return;
    }
    for (axis, component) in vertex.pos.iter().enumerate() {
        out[offset + axis * 4..offset + axis * 4 + 4].copy_from_slice(&component.to_ne_bytes());
    }
    out[offset + 12..offset + 16].copy_from_slice(&vertex.color);
    out[offset + 16..offset + 20].copy_from_slice(&vertex.uv[0].to_ne_bytes());
    out[offset + 20..offset + 24].copy_from_slice(&vertex.uv[1].to_ne_bytes());
    out[offset + 24..offset + 26].copy_from_slice(&vertex.light[0].to_ne_bytes());
    out[offset + 26..offset + 28].copy_from_slice(&vertex.light[1].to_ne_bytes());
    for (index, component) in vertex.sprite.iter().enumerate() {
        out[offset + 28 + index * 4..offset + 28 + index * 4 + 4]
            .copy_from_slice(&component.to_ne_bytes());
    }
}

/// Mesh a WOM2 snapshot. Returns the complete output buffer (header, layer
/// table, passthrough data, merged data).
pub fn mesh_section_v2(input: &[u8]) -> Result<Vec<u8>, jint> {
    let (merge_enabled, layers) = parse_input(input)?;
    let outputs: Vec<LayerOutput> = layers
        .into_iter()
        .map(|layer| mesh_layer(layer, merge_enabled))
        .collect();

    let table_offset = V2_OUTPUT_HEADER_BYTES;
    let mut data_offset = table_offset + V2_LAYER_COUNT * V2_OUTPUT_LAYER_TABLE_BYTES;
    // (passthrough_quads, passthrough_bytes, passthrough_offset, merged_quads, merged_bytes)
    let mut entries = [(0u32, 0u32, 0u32, 0u32, 0u32); V2_LAYER_COUNT];
    for (slot, output) in outputs.iter().enumerate() {
        let passthrough_quads = output.passthrough.len();
        let merged_quads = output.merged.len();
        let passthrough_bytes = passthrough_quads * 4 * BLOCK_VERTEX_BYTES;
        let merged_bytes = merged_quads * 4 * TILED_VERTEX_BYTES;
        entries[slot] = (
            passthrough_quads as u32,
            passthrough_bytes as u32,
            data_offset as u32,
            merged_quads as u32,
            merged_bytes as u32,
        );
        data_offset += passthrough_bytes + merged_bytes;
    }
    let total_bytes = data_offset;
    if total_bytes > MAX_OUTPUT_BYTES {
        return Err(ERR_OUTPUT_LIMIT);
    }

    let mut out = vec![0u8; total_bytes];
    write_u32(&mut out, 0, V2_MAGIC);
    write_u16(&mut out, 4, V2_VERSION);
    write_u16(&mut out, 6, V2_OUTPUT_HEADER_BYTES as u16);
    write_u16(&mut out, 8, V2_LAYER_COUNT as u16);
    write_u16(&mut out, 10, 0);
    write_u32(&mut out, 12, table_offset as u32);
    write_u32(&mut out, 16, total_bytes as u32);
    write_u32(&mut out, 20, MAX_VERTICES_PER_LAYER as u32 / 4);
    write_u32(&mut out, 24, 0);
    write_u32(&mut out, 28, 0);

    for (slot, output) in outputs.iter().enumerate() {
        let entry = table_offset + slot * V2_OUTPUT_LAYER_TABLE_BYTES;
        let (passthrough_quads, passthrough_bytes, passthrough_offset, merged_quads, _) =
            entries[slot];
        out[entry] = output.layer_id;
        out[entry + 1] = if merged_quads > 0 { 1 } else { 0 };
        write_u16(&mut out, entry + 2, 0);
        write_u32(&mut out, entry + 4, passthrough_quads);
        write_u32(&mut out, entry + 8, passthrough_bytes);
        write_u32(&mut out, entry + 12, passthrough_offset);
        write_u32(&mut out, entry + 16, merged_quads);
        write_u32(
            &mut out,
            entry + 20,
            merged_quads * 4 * TILED_VERTEX_BYTES as u32,
        );
    }

    for (slot, output) in outputs.iter().enumerate() {
        let (_, passthrough_bytes, passthrough_offset, _, _) = entries[slot];
        let mut cursor = passthrough_offset as usize;
        for quad in &output.passthrough {
            for vertex in &quad.v {
                write_block_vertex_at(&mut out, cursor, vertex);
                cursor += BLOCK_VERTEX_BYTES;
            }
        }
        debug_assert_eq!(
            cursor,
            passthrough_offset as usize + passthrough_bytes as usize
        );
        let merged_offset = passthrough_offset as usize + passthrough_bytes as usize;
        let mut merged_cursor = merged_offset;
        for quad in &output.merged {
            for vertex in &quad.v {
                write_tiled_vertex_at(&mut out, merged_cursor, vertex);
                merged_cursor += TILED_VERTEX_BYTES;
            }
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vertex(x: f32, y: f32, z: f32, u: f32, v: f32) -> V2Vertex {
        V2Vertex {
            pos: [x, y, z],
            color: [255, 255, 255, 255],
            uv: [u, v],
            light: [240, 240],
        }
    }

    /// Build a unit quad in FaceInfo corner order for `dir`, with UV flips.
    /// `flip_u`/`flip_v` mirror vanilla's per-direction default face UVs.
    fn block_quad(
        dir: usize,
        plane: i32,
        i0: i32,
        i1: i32,
        u0: f32,
        v0: f32,
        u1: f32,
        v1: f32,
        flip_u: bool,
        flip_v: bool,
    ) -> V2Quad {
        let const_axis = DIR_AXIS[dir];
        let (span0, span1) = DIR_SPAN[dir];
        let mut v = [vertex(0.0, 0.0, 0.0, 0.0, 0.0); 4];
        for k in 0..4 {
            let (choice_a, choice_b) = FACE_CORNERS[dir][k];
            let mut pos = [0f32; 3];
            pos[const_axis] = plane as f32;
            pos[span0] = (i0 + choice_a as i32) as f32;
            pos[span1] = (i1 + choice_b as i32) as f32;
            let along_a = choice_a == 1;
            let along_b = choice_b == 1;
            let u = if along_a != flip_u { u1 } else { u0 };
            let vv = if along_b != flip_v { v1 } else { v0 };
            v[k] = vertex(pos[0], pos[1], pos[2], u, vv);
        }
        V2Quad { v }
    }

    /// Vanilla default UV flips per direction (from FaceBakery.defaultFaceUV):
    /// NORTH/WEST/EAST flip u; DOWN/NORTH/SOUTH/EAST flip v.
    fn vanilla_flips(dir: usize) -> (bool, bool) {
        match dir {
            0 => (false, true),  // DOWN
            1 => (false, false), // UP
            2 => (true, true),   // NORTH
            3 => (false, true),  // SOUTH
            4 => (true, false),  // WEST
            5 => (true, true),   // EAST
            _ => unreachable!(),
        }
    }

    fn build_input(merge_enabled: bool, layers: &[(u8, Vec<V2Quad>)]) -> Vec<u8> {
        let table_offset = V2_HEADER_BYTES;
        let mut data_offset = table_offset + V2_LAYER_COUNT * V2_LAYER_TABLE_BYTES;
        let mut entries = [(0u32, 0u32, 0u32); V2_LAYER_COUNT];
        for (slot, (_, quads)) in layers.iter().enumerate() {
            let vertex_count = quads.len() * 4;
            let vertex_bytes = vertex_count * BLOCK_VERTEX_BYTES;
            entries[slot] = (vertex_count as u32, vertex_bytes as u32, data_offset as u32);
            data_offset += vertex_bytes;
        }
        let total = data_offset;
        let mut input = vec![0u8; total];
        write_u32(&mut input, 0, V2_MAGIC);
        write_u16(&mut input, 4, V2_VERSION);
        write_u16(&mut input, 6, V2_HEADER_BYTES as u16);
        write_u16(&mut input, 8, V2_LAYER_COUNT as u16);
        write_u16(
            &mut input,
            10,
            if merge_enabled { FLAG_MERGE_ENABLED } else { 0 },
        );
        // Section world origin (validation only; vertices are section-local).
        write_u32(&mut input, 12, (-3_000_000i32) as u32);
        write_u32(&mut input, 16, (-64i32) as u32);
        write_u32(&mut input, 20, (29_999_984i32) as u32);
        write_u32(&mut input, 24, table_offset as u32);
        write_u32(&mut input, 28, 0);
        write_u32(&mut input, 32, total as u32);
        write_u8(&mut input, 36, 0x3f);
        write_u32(&mut input, 40, MAX_VERTICES_PER_LAYER as u32);
        // Always write all three table entries (missing slots stay empty).
        for slot in 0..V2_LAYER_COUNT {
            let entry = table_offset + slot * V2_LAYER_TABLE_BYTES;
            input[entry] = LAYER_IDS[slot];
            let (vertex_count, vertex_bytes, offset) = entries[slot];
            write_u32(&mut input, entry + 4, vertex_count);
            write_u32(&mut input, entry + 8, vertex_bytes);
            write_u32(&mut input, entry + 12, offset);
        }
        for (slot, (_, quads)) in layers.iter().enumerate() {
            let (_, _, offset) = entries[slot];
            let mut cursor = offset as usize;
            for quad in quads {
                for v in &quad.v {
                    write_block_vertex_at(&mut input, cursor, v);
                    cursor += BLOCK_VERTEX_BYTES;
                }
            }
        }
        input
    }

    fn parse_output(out: &[u8]) -> Vec<(u8, usize, usize, usize, usize, usize)> {
        assert_eq!(read_u32(out, 0), Some(V2_MAGIC));
        assert_eq!(read_u16(out, 4), Some(V2_VERSION));
        let table_offset = read_u32(out, 12).unwrap() as usize;
        let mut layers = Vec::new();
        for slot in 0..V2_LAYER_COUNT {
            let entry = table_offset + slot * V2_OUTPUT_LAYER_TABLE_BYTES;
            layers.push((
                out[entry],
                read_u32(out, entry + 4).unwrap() as usize,
                read_u32(out, entry + 8).unwrap() as usize,
                read_u32(out, entry + 12).unwrap() as usize,
                read_u32(out, entry + 16).unwrap() as usize,
                read_u32(out, entry + 20).unwrap() as usize,
            ));
        }
        layers
    }

    fn parse_tiled_vertex(bytes: &[u8], offset: usize) -> Option<MergedVertex> {
        if offset + TILED_VERTEX_BYTES > bytes.len() {
            return None;
        }
        let read_f32 = |at: usize| {
            f32::from_ne_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
        };
        let read_i16 = |at: usize| i16::from_ne_bytes([bytes[at], bytes[at + 1]]);
        Some(MergedVertex {
            pos: [read_f32(offset), read_f32(offset + 4), read_f32(offset + 8)],
            color: [
                bytes[offset + 12],
                bytes[offset + 13],
                bytes[offset + 14],
                bytes[offset + 15],
            ],
            uv: [read_f32(offset + 16), read_f32(offset + 20)],
            light: [read_i16(offset + 24), read_i16(offset + 26)],
            sprite: [
                read_f32(offset + 28),
                read_f32(offset + 32),
                read_f32(offset + 36),
                read_f32(offset + 40),
            ],
        })
    }

    #[test]
    fn single_quad_passes_through_identically() {
        let quad = block_quad(1, 16, 3, 5, 0.25, 0.5, 0.3125, 0.5625, false, false);
        let input = build_input(false, &[(LAYER_ID_SOLID, vec![quad])]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].1, 1);
        assert_eq!(layers[0].4, 0);
        let parsed = parse_vertex(&output, layers[0].3).unwrap();
        assert_eq!(parsed.pos, quad.v[0].pos);
        assert_eq!(parsed.color, quad.v[0].color);
        assert_eq!(parsed.uv, quad.v[0].uv);
        assert_eq!(parsed.light, quad.v[0].light);
    }

    #[test]
    fn adjacent_coplanar_uniform_quads_merge_into_one() {
        // Two east-facing unit quads side by side on plane x=4 (vanilla EAST
        // flips u and v).
        let (fu, fv) = vanilla_flips(5);
        let q0 = block_quad(5, 4, 2, 3, 0.0, 0.0, 0.0625, 0.0625, fu, fv);
        let q1 = block_quad(5, 4, 3, 3, 0.0, 0.0, 0.0625, 0.0625, fu, fv);
        let input = build_input(true, &[(LAYER_ID_SOLID, vec![q0, q1])]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].1, 0, "both quads should be merged away");
        assert_eq!(layers[0].4, 1, "exactly one merged quad");
        let merged_offset = layers[0].3 + layers[0].2;
        let v0 = parse_tiled_vertex(&output, merged_offset).unwrap();
        let v2 = parse_tiled_vertex(&output, merged_offset + 2 * TILED_VERTEX_BYTES).unwrap();
        // EAST corners: (maxY,maxZ),(minY,maxZ),(minY,minZ),(maxY,minZ) on x=4,
        // spanning y cells 2..3 and z cell 3 → y in [2,4], z in [3,4].
        assert_eq!(v0.pos, [4.0, 4.0, 4.0]);
        assert_eq!(v2.pos, [4.0, 2.0, 3.0]);
        // Block-unit UVs are the geometric in-plane coordinates.
        assert_eq!(v0.uv, [4.0, 4.0]);
        assert_eq!(v2.uv, [2.0, 3.0]);
        // EAST flips both axes → negative sprite extents.
        assert_eq!(v0.sprite, [0.0625, 0.0625, -0.0625, -0.0625]);
        assert_eq!(v0.color, [255, 255, 255, 255]);
        assert_eq!(v0.light, [240, 240]);
    }

    #[test]
    fn differing_corner_attributes_prevent_merging() {
        let (fu, fv) = vanilla_flips(1);
        let mut q0 = block_quad(1, 16, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let mut q1 = block_quad(1, 16, 1, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        q0.v[1].light = [100, 240];
        q1.v[0].light = [100, 240];
        let input = build_input(true, &[(LAYER_ID_SOLID, vec![q0, q1])]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].1, 2, "no merge without uniform attributes");
        assert_eq!(layers[0].4, 0);
    }

    #[test]
    fn non_unit_and_fractional_quads_pass_through() {
        let (fu, fv) = vanilla_flips(1);
        // A quad spanning two blocks is not a unit quad.
        let mut wide = block_quad(1, 16, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        wide.v[2].pos[0] = 2.0;
        wide.v[3].pos[0] = 2.0;
        // A unit quad on a fractional plane.
        let mut fractional = block_quad(1, 0, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        for v in &mut fractional.v {
            v.pos[1] = 0.5;
        }
        let input = build_input(true, &[(LAYER_ID_SOLID, vec![wide, fractional])]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].1, 2);
        assert_eq!(layers[0].4, 0);
    }

    #[test]
    fn full_sixteen_by_sixteen_wall_merges_to_one_quad() {
        let (fu, fv) = vanilla_flips(1);
        let mut quads = Vec::new();
        for x in 0..16 {
            for z in 0..16 {
                quads.push(block_quad(1, 16, x, z, 0.0, 0.0, 1.0, 1.0, fu, fv));
            }
        }
        let input = build_input(true, &[(LAYER_ID_SOLID, quads)]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].1, 0);
        assert_eq!(layers[0].4, 1);
        let merged_offset = layers[0].3;
        let v0 = parse_tiled_vertex(&output, merged_offset).unwrap();
        let v2 = parse_tiled_vertex(&output, merged_offset + 2 * TILED_VERTEX_BYTES).unwrap();
        let v3 = parse_tiled_vertex(&output, merged_offset + 3 * TILED_VERTEX_BYTES).unwrap();
        // UP corners: (minX,minZ),(minX,maxZ),(maxX,maxZ),(maxX,minZ).
        assert_eq!(v0.pos, [0.0, 16.0, 0.0]);
        assert_eq!(v2.pos, [16.0, 16.0, 16.0]);
        assert_eq!(v3.pos, [16.0, 16.0, 0.0]);
        assert_eq!(v0.uv, [0.0, 0.0]);
        assert_eq!(v2.uv, [16.0, 16.0]);
        assert_eq!(v3.uv, [16.0, 0.0]);
    }

    #[test]
    fn two_by_two_cells_merge_to_one_quad() {
        let (fu, fv) = vanilla_flips(1);
        let mut quads = Vec::new();
        for x in 0..2 {
            for z in 0..2 {
                quads.push(block_quad(1, 16, x, z, 0.0, 0.0, 1.0, 1.0, fu, fv));
            }
        }
        let input = build_input(true, &[(LAYER_ID_SOLID, quads)]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].4, 1);
    }

    #[test]
    fn diagonal_cells_stay_separate() {
        let (fu, fv) = vanilla_flips(1);
        let q0 = block_quad(1, 16, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let q1 = block_quad(1, 16, 1, 1, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let input = build_input(true, &[(LAYER_ID_SOLID, vec![q0, q1])]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].4, 2, "diagonal cells cannot form one rectangle");
    }

    #[test]
    fn different_planes_do_not_merge() {
        let (fu, fv) = vanilla_flips(1);
        let q0 = block_quad(1, 16, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let q1 = block_quad(1, 15, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let input = build_input(true, &[(LAYER_ID_SOLID, vec![q0, q1])]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].4, 2);
    }

    #[test]
    fn different_uv_rects_do_not_merge() {
        let (fu, fv) = vanilla_flips(1);
        let q0 = block_quad(1, 16, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let q1 = block_quad(1, 16, 1, 0, 0.5, 0.5, 1.0, 1.0, fu, fv);
        let input = build_input(true, &[(LAYER_ID_SOLID, vec![q0, q1])]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].4, 2);
    }

    #[test]
    fn different_orientations_do_not_merge() {
        // Same rect, one quad flipped along u → different group.
        let q0 = block_quad(1, 16, 0, 0, 0.0, 0.0, 1.0, 1.0, false, false);
        let q1 = block_quad(1, 16, 1, 0, 0.0, 0.0, 1.0, 1.0, true, false);
        let input = build_input(true, &[(LAYER_ID_SOLID, vec![q0, q1])]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].4, 2);
    }

    #[test]
    fn merging_disabled_emits_passthrough_only() {
        let (fu, fv) = vanilla_flips(1);
        let q0 = block_quad(1, 16, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let q1 = block_quad(1, 16, 1, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let input = build_input(false, &[(LAYER_ID_SOLID, vec![q0, q1])]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].1, 2);
        assert_eq!(layers[0].4, 0);
    }

    #[test]
    fn empty_section_produces_valid_empty_output() {
        let input = build_input(true, &[]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        for layer in &layers {
            assert_eq!(layer.1, 0);
            assert_eq!(layer.4, 0);
        }
        assert_eq!(read_u32(&output, 16).unwrap() as usize, output.len());
    }

    #[test]
    fn layers_stay_separate() {
        let (fu, fv) = vanilla_flips(1);
        let solid = block_quad(1, 16, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let cutout = block_quad(3, 16, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let translucent = block_quad(5, 4, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let input = build_input(
            true,
            &[
                (LAYER_ID_SOLID, vec![solid]),
                (LAYER_ID_CUTOUT, vec![cutout]),
                (LAYER_ID_TRANSLUCENT, vec![translucent]),
            ],
        );
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].0, LAYER_ID_SOLID);
        assert_eq!(layers[1].0, LAYER_ID_CUTOUT);
        assert_eq!(layers[2].0, LAYER_ID_TRANSLUCENT);
        assert_eq!(layers[0].4, 1);
        assert_eq!(layers[1].4, 1);
        assert_eq!(layers[2].4, 1);
    }

    #[test]
    fn malformed_inputs_are_rejected() {
        let good = build_input(false, &[]);
        let mut bad = good.clone();
        write_u32(&mut bad, 0, 0xDEADBEEF);
        assert_eq!(mesh_section_v2(&bad), Err(ERR_BAD_HEADER));
        let mut bad = good.clone();
        write_u16(&mut bad, 4, 99);
        assert_eq!(mesh_section_v2(&bad), Err(ERR_BAD_HEADER));
        assert_eq!(mesh_section_v2(&good[..20]), Err(ERR_BAD_HEADER));
        let mut bad = good.clone();
        write_u32(&mut bad, 32, (good.len() + 1024) as u32);
        assert_eq!(mesh_section_v2(&bad), Err(ERR_BAD_HEADER));
        let (fu, fv) = vanilla_flips(1);
        let one = block_quad(1, 16, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let mut bad = build_input(false, &[(LAYER_ID_SOLID, vec![one.clone()])]);
        write_u32(&mut bad, V2_HEADER_BYTES + 4, 5);
        assert!(mesh_section_v2(&bad).is_err());
        let mut bad = good.clone();
        bad[V2_HEADER_BYTES] = 9;
        assert_eq!(mesh_section_v2(&bad), Err(ERR_V2_LAYER));
        let mut bad = build_input(false, &[(LAYER_ID_SOLID, vec![one.clone()])]);
        write_u32(&mut bad, V2_HEADER_BYTES + 8, 12345);
        assert_eq!(mesh_section_v2(&bad), Err(ERR_V2_LAYER));
        let mut bad = build_input(false, &[(LAYER_ID_SOLID, vec![one])]);
        write_u32(&mut bad, V2_HEADER_BYTES + 12, 0x7FFFFFFF);
        assert_eq!(mesh_section_v2(&bad), Err(ERR_V2_LAYER));
    }

    #[test]
    fn non_finite_vertex_positions_are_rejected() {
        let (fu, fv) = vanilla_flips(1);
        let mut quad = block_quad(1, 16, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        quad.v[1].pos[0] = f32::NAN;
        let input = build_input(false, &[(LAYER_ID_SOLID, vec![quad])]);
        assert_eq!(mesh_section_v2(&input), Err(ERR_V2_VERTEX));
    }

    #[test]
    fn meshing_is_deterministic() {
        let (fu, fv) = vanilla_flips(1);
        let mut quads = Vec::new();
        for x in 0..8 {
            for z in 0..8 {
                quads.push(block_quad(1, 16, x, z, 0.0, 0.0, 1.0, 1.0, fu, fv));
            }
        }
        let (fu5, fv5) = vanilla_flips(5);
        quads.push(block_quad(5, 4, 0, 0, 0.0, 0.0, 1.0, 1.0, fu5, fv5));
        let input = build_input(true, &[(LAYER_ID_SOLID, quads)]);
        let first = mesh_section_v2(&input).unwrap();
        let second = mesh_section_v2(&input).unwrap();
        assert_eq!(first, second);
    }

    #[test]
    fn mixed_candidates_and_passthrough_split_correctly() {
        let (fu, fv) = vanilla_flips(1);
        let mut quads = Vec::new();
        for x in 0..3 {
            quads.push(block_quad(1, 16, x, 0, 0.0, 0.0, 1.0, 1.0, fu, fv));
        }
        let mut fractional = block_quad(1, 16, 5, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        for v in &mut fractional.v {
            v.pos[1] = 15.5;
        }
        quads.push(fractional);
        let input = build_input(true, &[(LAYER_ID_SOLID, quads)]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].1, 1, "only the fractional quad passes through");
        assert_eq!(layers[0].4, 1, "the three unit quads merge");
    }

    #[test]
    fn section_boundary_coordinates_merge() {
        let (fu, fv) = vanilla_flips(1);
        let q0 = block_quad(1, 16, 0, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let q1 = block_quad(1, 16, 15, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
        let input = build_input(true, &[(LAYER_ID_SOLID, vec![q0, q1])]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        // Not adjacent (cells 0 and 15) → two separate merged quads.
        assert_eq!(layers[0].4, 2);
    }

    #[test]
    fn merged_uv_block_units_reconstruct_unit_uvs() {
        // A 3-wide run on plane y=16, UP direction (no flips). The shader
        // reconstructs atlasUV = sprite.xy + fract(blockUV) * sprite.zw.
        let (fu, fv) = vanilla_flips(1);
        let mut quads = Vec::new();
        for x in 2..5 {
            quads.push(block_quad(1, 16, x, 7, 0.25, 0.5, 0.3125, 0.5625, fu, fv));
        }
        let input = build_input(true, &[(LAYER_ID_SOLID, quads)]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].4, 1);
        let merged_offset = layers[0].3;
        // UP corners: (minX,minZ),(minX,maxZ),(maxX,maxZ),(maxX,minZ).
        let v0 = parse_tiled_vertex(&output, merged_offset).unwrap();
        assert_eq!(v0.pos, [2.0, 16.0, 7.0]);
        assert_eq!(v0.uv, [2.0, 7.0]);
        assert_eq!(v0.sprite, [0.25, 0.5, 0.0625, 0.0625]);
        // Shader at corner 0: fract(2.0)=0 → atlasUV = (0.25, 0.5) = the unit
        // quad's corner-0 UV. ✓
        let v2 = parse_tiled_vertex(&output, merged_offset + 2 * TILED_VERTEX_BYTES).unwrap();
        assert_eq!(v2.pos, [5.0, 16.0, 8.0]);
        assert_eq!(v2.uv, [5.0, 8.0]);
        // Interior of the last unit (blockUV → 5⁻): fract → 1⁻ → atlasUV →
        // (0.3125⁻, 0.5625⁻) = the unit quad's max corner UV. ✓
    }

    #[test]
    fn flipped_orientation_encodes_negative_sprite_extents() {
        // DOWN faces flip v in vanilla's default UVs.
        let (fu, fv) = vanilla_flips(0);
        assert!(fv);
        let q0 = block_quad(0, 0, 3, 4, 0.125, 0.25, 0.1875, 0.3125, fu, fv);
        let q1 = block_quad(0, 0, 4, 4, 0.125, 0.25, 0.1875, 0.3125, fu, fv);
        let input = build_input(true, &[(LAYER_ID_SOLID, vec![q0, q1])]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        assert_eq!(layers[0].4, 1);
        let merged_offset = layers[0].3;
        let v0 = parse_tiled_vertex(&output, merged_offset).unwrap();
        // DOWN flips v → sprite = (u0, v1, w, -h).
        assert_eq!(v0.sprite, [0.125, 0.3125, 0.0625, -0.0625]);
        // Block UVs stay geometric (positive).
        assert_eq!(v0.uv, [3.0, 5.0]);
        // Shader at corner 0 (DOWN corner 0 = (minX, minY, maxZ)): the unit
        // quad's corner-0 UV is (u0, v1) because v is flipped.
        // fract(3.0)=0, fract(5.0)=0 → atlasUV = (u0, v1) ✓.
    }

    #[test]
    fn debug_classify_up_quad() {
        let (fu, fv) = vanilla_flips(1);
        let q = block_quad(1, 16, 3, 5, 0.25, 0.5, 0.3125, 0.5625, fu, fv);
        eprintln!(
            "positions: {:?}",
            [q.v[0].pos, q.v[1].pos, q.v[2].pos, q.v[3].pos]
        );
        eprintln!("uvs: {:?}", [q.v[0].uv, q.v[1].uv, q.v[2].uv, q.v[3].uv]);
        eprintln!("classify some: {}", classify(&q).is_some());
    }

    #[test]
    fn output_quad_counts_never_exceed_input() {
        // Every merged quad consumes at least one input quad; passthrough plus
        // merged quads per layer must not exceed the input quad count.
        let (fu, fv) = vanilla_flips(1);
        let mut quads = Vec::new();
        for x in 0..16 {
            for z in 0..16 {
                quads.push(block_quad(1, 16, x, z, 0.0, 0.0, 1.0, 1.0, fu, fv));
            }
        }
        // Plus scattered non-mergeable quads.
        for i in 0..10 {
            let mut q = block_quad(1, 16, i, 0, 0.0, 0.0, 1.0, 1.0, fu, fv);
            q.v[0].pos[1] = 3.5;
            quads.push(q);
        }
        let input_quads = quads.len();
        let input = build_input(true, &[(LAYER_ID_SOLID, quads)]);
        let output = mesh_section_v2(&input).unwrap();
        let layers = parse_output(&output);
        let total_quads = layers[0].1 + layers[0].4;
        assert!(total_quads <= input_quads);
        // Index counts stay within u32 and the sequential buffer range.
        let max_index_count = total_quads * 6;
        assert!(max_index_count < u32::MAX as usize);
    }
}
