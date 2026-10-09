//! Native section shadow mesher for Minecraft 26.2.
//!
//! One input and one output direct ByteBuffer are exchanged per section compile.
//! Native output is packed triangle-list data; vanilla still draws its own mesh.

use jni::objects::{JByteBuffer, JClass};
use jni::sys::jint;
use jni::JNIEnv;
use rayon::prelude::*;
use rayon::ThreadPoolBuilder;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;
use std::sync::OnceLock;

const MAGIC: u32 = u32::from_le_bytes(*b"WOM1");
const VERSION: u16 = 1;
const HEADER_BYTES: usize = 40;
const GRID: usize = 18;
const CELL_COUNT: usize = GRID * GRID * GRID;
const PALETTE_ENTRY_BYTES: usize = 8;
const CELL_ENTRY_BYTES: usize = 4;
const VERTEX_STRIDE: usize = 16;
const MAX_VERTICES: usize = 73_728;

const FLAG_MESHABLE: u8 = 1;
const FLAG_OCCLUDES: u8 = 2;

const ERR_PANIC: jint = -1;
const ERR_DIRECT_BUFFER: jint = -2;
const ERR_BAD_HEADER: jint = -3;
const ERR_BAD_PALETTE_INDEX: jint = -4;
const ERR_OUTPUT_TOO_SMALL: jint = -5;
const ERR_OUTPUT_LIMIT: jint = -6;

#[derive(Clone, Copy)]
struct PaletteEntry {
    state_id: u32,
    flags: u8,
    face_mask: u8,
}

#[derive(Clone, Copy)]
struct Cell {
    palette_index: usize,
    sky: u8,
    block: u8,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Attr {
    sky: u8,
    block: u8,
    ao: u8,
}

#[derive(Clone, Copy)]
struct FaceCell {
    state_id: u32,
    attrs: [Attr; 4],
    mergeable: bool,
}

#[derive(Clone, Copy)]
struct Vertex {
    x: u8,
    y: u8,
    z: u8,
    face: u8,
    state_id: u32,
    sky: u8,
    block: u8,
    ao: u8,
    u: u8,
    v: u8,
}

#[derive(Clone, Copy)]
struct FaceSpec {
    normal: usize,
    positive: bool,
    u_axis: usize,
    v_axis: usize,
    face_id: u8,
}

// U cross V is the outward normal for each face. ABI IDs are W,E,D,U,N,S.
const FACES: [FaceSpec; 6] = [
    FaceSpec { normal: 0, positive: false, u_axis: 2, v_axis: 1, face_id: 0 },
    FaceSpec { normal: 0, positive: true,  u_axis: 1, v_axis: 2, face_id: 1 },
    FaceSpec { normal: 1, positive: false, u_axis: 0, v_axis: 2, face_id: 2 },
    FaceSpec { normal: 1, positive: true,  u_axis: 2, v_axis: 0, face_id: 3 },
    FaceSpec { normal: 2, positive: false, u_axis: 1, v_axis: 0, face_id: 4 },
    FaceSpec { normal: 2, positive: true,  u_axis: 0, v_axis: 1, face_id: 5 },
];

static POOL: OnceLock<rayon::ThreadPool> = OnceLock::new();

fn pool() -> &'static rayon::ThreadPool {
    POOL.get_or_init(|| {
        let available = std::thread::available_parallelism().map(|v| v.get()).unwrap_or(2);
        ThreadPoolBuilder::new()
            // Keep the native helper pool small so chunk meshing does not crowd
            // Minecraft's own workers or the integrated server tick thread.
            .num_threads(available.saturating_sub(1).clamp(1, 2))
            .thread_name(|index| format!("what-optimizations-mesh-{index}"))
            .build()
            .expect("could not initialize Rust meshing pool")
    })
}

fn read_u16(data: &[u8], offset: usize) -> Option<u16> {
    let end = offset.checked_add(2)?;
    let s = data.get(offset..end)?;
    Some(u16::from_le_bytes([s[0], s[1]]))
}

fn read_u32(data: &[u8], offset: usize) -> Option<u32> {
    let end = offset.checked_add(4)?;
    let s = data.get(offset..end)?;
    Some(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
}

fn grid_index(x: usize, y: usize, z: usize) -> usize {
    x + GRID * (y + GRID * z)
}

fn parse_input(data: &[u8]) -> Result<(Vec<PaletteEntry>, Vec<Cell>), jint> {
    if data.len() < HEADER_BYTES {
        return Err(ERR_BAD_HEADER);
    }

    let magic = read_u32(data, 0).ok_or(ERR_BAD_HEADER)?;
    let version = read_u16(data, 4).ok_or(ERR_BAD_HEADER)?;
    let header = read_u16(data, 6).ok_or(ERR_BAD_HEADER)? as usize;
    let dx = read_u16(data, 8).ok_or(ERR_BAD_HEADER)? as usize;
    let dy = read_u16(data, 10).ok_or(ERR_BAD_HEADER)? as usize;
    let dz = read_u16(data, 12).ok_or(ERR_BAD_HEADER)? as usize;
    let palette_count = read_u16(data, 14).ok_or(ERR_BAD_HEADER)? as usize;
    let cell_count = read_u32(data, 16).ok_or(ERR_BAD_HEADER)? as usize;
    let palette_stride = read_u16(data, 20).ok_or(ERR_BAD_HEADER)? as usize;
    let cell_stride = read_u16(data, 22).ok_or(ERR_BAD_HEADER)? as usize;
    let palette_offset = read_u32(data, 24).ok_or(ERR_BAD_HEADER)? as usize;
    let cells_offset = read_u32(data, 28).ok_or(ERR_BAD_HEADER)? as usize;
    let total_bytes = read_u32(data, 32).ok_or(ERR_BAD_HEADER)? as usize;

    if magic != MAGIC || version != VERSION || header != HEADER_BYTES
        || (dx, dy, dz) != (GRID, GRID, GRID)
        || palette_count == 0 || palette_count > CELL_COUNT
        || cell_count != CELL_COUNT
        || palette_stride != PALETTE_ENTRY_BYTES || cell_stride != CELL_ENTRY_BYTES
        || palette_offset != HEADER_BYTES
    {
        return Err(ERR_BAD_HEADER);
    }

    let expected_cells = palette_offset
        .checked_add(palette_count.checked_mul(PALETTE_ENTRY_BYTES).ok_or(ERR_BAD_HEADER)?)
        .ok_or(ERR_BAD_HEADER)?;
    let expected_total = expected_cells
        .checked_add(CELL_COUNT.checked_mul(CELL_ENTRY_BYTES).ok_or(ERR_BAD_HEADER)?)
        .ok_or(ERR_BAD_HEADER)?;
    if cells_offset != expected_cells || total_bytes != expected_total || total_bytes > data.len() {
        return Err(ERR_BAD_HEADER);
    }

    let mut palette = Vec::with_capacity(palette_count);
    for i in 0..palette_count {
        let p = palette_offset + i * PALETTE_ENTRY_BYTES;
        palette.push(PaletteEntry {
            state_id: read_u32(data, p).ok_or(ERR_BAD_HEADER)?,
            flags: *data.get(p + 4).ok_or(ERR_BAD_HEADER)?,
            face_mask: *data.get(p + 5).ok_or(ERR_BAD_HEADER)?,
        });
    }

    let mut cells = Vec::with_capacity(CELL_COUNT);
    for i in 0..CELL_COUNT {
        let p = cells_offset + i * CELL_ENTRY_BYTES;
        let palette_index = read_u16(data, p).ok_or(ERR_BAD_HEADER)? as usize;
        if palette_index >= palette.len() {
            return Err(ERR_BAD_PALETTE_INDEX);
        }
        cells.push(Cell {
            palette_index,
            sky: data[p + 2].min(15),
            block: data[p + 3].min(15),
        });
    }
    Ok((palette, cells))
}

fn cell_at(cells: &[Cell], pos: [usize; 3]) -> Cell {
    cells[grid_index(pos[0], pos[1], pos[2])]
}

fn entry_for<'a>(palette: &'a [PaletteEntry], cell: Cell) -> &'a PaletteEntry {
    &palette[cell.palette_index]
}

fn occludes(palette: &[PaletteEntry], cells: &[Cell], pos: [usize; 3]) -> bool {
    entry_for(palette, cell_at(cells, pos)).flags & FLAG_OCCLUDES != 0
}

fn attributes_for_face(
    spec: FaceSpec,
    grid_pos: [usize; 3],
    palette: &[PaletteEntry],
    cells: &[Cell],
) -> [Attr; 4] {
    let mut outside = grid_pos;
    if spec.positive {
        outside[spec.normal] += 1;
    } else {
        outside[spec.normal] -= 1;
    }

    // All four face corners sample the same 3x3 neighborhood on the outside
    // plane. Load it once instead of repeating up to 28 cell/palette lookups
    // per face corner.
    let empty = Cell {
        palette_index: 0,
        sky: 0,
        block: 0,
    };
    let mut neighborhood = [empty; 9];
    let mut opaque = [false; 9];
    for v in 0..3 {
        for u in 0..3 {
            let mut pos = outside;
            pos[spec.u_axis] = (outside[spec.u_axis] as isize + u as isize - 1) as usize;
            pos[spec.v_axis] = (outside[spec.v_axis] as isize + v as isize - 1) as usize;
            let index = v * 3 + u;
            let cell = cell_at(cells, pos);
            neighborhood[index] = cell;
            opaque[index] = entry_for(palette, cell).flags & FLAG_OCCLUDES != 0;
        }
    }

    let corners = [(0usize, 0usize), (2, 0), (2, 2), (0, 2)];
    let mut attrs = [Attr { sky: 0, block: 0, ao: 0 }; 4];
    for (index, (u, v)) in corners.into_iter().enumerate() {
        let side_u_index = 3 + u;
        let side_v_index = v * 3 + 1;
        let corner_index = v * 3 + u;
        let side_u = neighborhood[side_u_index];
        let side_v = neighborhood[side_v_index];
        let corner = neighborhood[corner_index];

        let su = opaque[side_u_index] as u8;
        let sv = opaque[side_v_index] as u8;
        let co = opaque[corner_index] as u8;
        let ao = if su != 0 && sv != 0 { 0 } else { 3 - su - sv - co };
        let center = neighborhood[4];

        attrs[index] = Attr {
            sky: ((u16::from(center.sky)
                + u16::from(side_u.sky)
                + u16::from(side_v.sky)
                + u16::from(corner.sky)) / 4) as u8,
            block: ((u16::from(center.block)
                + u16::from(side_u.block)
                + u16::from(side_v.block)
                + u16::from(corner.block)) / 4) as u8,
            ao,
        };
    }
    attrs
}

fn face_cell(
    spec: FaceSpec,
    grid_pos: [usize; 3],
    palette: &[PaletteEntry],
    cells: &[Cell],
) -> FaceCell {
    let cell = cell_at(cells, grid_pos);
    let block = entry_for(palette, cell);
    let attrs = attributes_for_face(spec, grid_pos, palette, cells);
    let mergeable = attrs.iter().all(|attr| *attr == attrs[0]);
    FaceCell { state_id: block.state_id, attrs, mergeable }
}

fn same_merge_key(a: FaceCell, b: FaceCell) -> bool {
    a.mergeable && b.mergeable && a.state_id == b.state_id && a.attrs[0] == b.attrs[0]
}

fn base_position(spec: FaceSpec, slice: usize, u: usize, v: usize) -> [usize; 3] {
    let mut p = [0; 3];
    p[spec.normal] = slice;
    p[spec.u_axis] = u;
    p[spec.v_axis] = v;
    p
}

fn face_positions(
    spec: FaceSpec,
    slice: usize,
    u: usize,
    v: usize,
    width: usize,
    height: usize,
) -> [[usize; 3]; 4] {
    let plane = slice + usize::from(spec.positive);
    let mut p0 = base_position(spec, slice, u, v);
    p0[spec.normal] = plane;
    let mut p1 = p0;
    p1[spec.u_axis] += width;
    let mut p2 = p1;
    p2[spec.v_axis] += height;
    let mut p3 = p0;
    p3[spec.v_axis] += height;
    [p0, p1, p2, p3]
}

fn make_vertex(
    p: [usize; 3],
    face: u8,
    state_id: u32,
    attr: Attr,
    u: usize,
    v: usize,
) -> Vertex {
    Vertex {
        x: p[0] as u8, y: p[1] as u8, z: p[2] as u8, face,
        state_id, sky: attr.sky, block: attr.block, ao: attr.ao,
        u: u as u8, v: v as u8,
    }
}

fn emit_quad(
    out: &mut Vec<Vertex>,
    spec: FaceSpec,
    positions: [[usize; 3]; 4],
    cell: FaceCell,
    width: usize,
    height: usize,
) {
    let attrs = if cell.mergeable { [cell.attrs[0]; 4] } else { cell.attrs };
    let q = [
        make_vertex(positions[0], spec.face_id, cell.state_id, attrs[0], 0, 0),
        make_vertex(positions[1], spec.face_id, cell.state_id, attrs[1], width, 0),
        make_vertex(positions[2], spec.face_id, cell.state_id, attrs[2], width, height),
        make_vertex(positions[3], spec.face_id, cell.state_id, attrs[3], 0, height),
    ];
    // GL 3.3 core does not support GL_QUADS: each quad is emitted as two triangles.
    out.extend_from_slice(&[q[0], q[1], q[2], q[0], q[2], q[3]]);
}

fn mesh_direction(
    spec: FaceSpec,
    palette: &[PaletteEntry],
    cells: &[Cell],
) -> Vec<Vertex> {
    // Typical section faces are much smaller than the worst case. Avoid
    // reserving 64 KiB per direction on every section compile; grow only for
    // unusually complex surfaces. The fixed greedy mask is small stack data.
    let mut out = Vec::with_capacity(512);
    let mut mask: [Option<FaceCell>; 16 * 16] = [None; 16 * 16];

    for slice in 0..16 {
        mask.fill(None);
        for v in 0..16 {
            for u in 0..16 {
                let local = base_position(spec, slice, u, v);
                let pos = [local[0] + 1, local[1] + 1, local[2] + 1];
                let here = cell_at(cells, pos);
                let block = entry_for(palette, here);
                if block.flags & FLAG_MESHABLE == 0 || block.face_mask & (1 << spec.face_id) == 0 {
                    continue;
                }

                let mut neighbor = pos;
                if spec.positive {
                    neighbor[spec.normal] += 1;
                } else {
                    neighbor[spec.normal] -= 1;
                }
                if occludes(palette, cells, neighbor) {
                    continue;
                }

                mask[v * 16 + u] = Some(face_cell(spec, pos, palette, cells));
            }
        }

        for v in 0..16 {
            for u in 0..16 {
                let mi = v * 16 + u;
                let Some(current) = mask[mi] else { continue; };

                let mut width = 1;
                if current.mergeable {
                    while u + width < 16 {
                        match mask[v * 16 + u + width] {
                            Some(candidate) if same_merge_key(current, candidate) => width += 1,
                            _ => break,
                        }
                    }
                }

                let mut height = 1;
                if current.mergeable {
                    'height: while v + height < 16 {
                        for du in 0..width {
                            match mask[(v + height) * 16 + u + du] {
                                Some(candidate) if same_merge_key(current, candidate) => {}
                                _ => break 'height,
                            }
                        }
                        height += 1;
                    }
                }

                for dv in 0..height {
                    for du in 0..width {
                        mask[(v + dv) * 16 + u + du] = None;
                    }
                }

                let positions = face_positions(spec, slice, u, v, width, height);
                emit_quad(&mut out, spec, positions, current, width, height);
            }
        }
    }
    out
}

fn write_vertex(dst: &mut [u8], offset: usize, vertex: Vertex) {
    dst[offset] = vertex.x;
    dst[offset + 1] = vertex.y;
    dst[offset + 2] = vertex.z;
    dst[offset + 3] = vertex.face;
    dst[offset + 4..offset + 8].copy_from_slice(&vertex.state_id.to_le_bytes());
    dst[offset + 8] = vertex.sky;
    dst[offset + 9] = vertex.block;
    dst[offset + 10] = vertex.ao;
    dst[offset + 11] = 0;
    dst[offset + 12] = vertex.u;
    dst[offset + 13] = vertex.v;
    dst[offset + 14] = 0;
    dst[offset + 15] = 0;
}

fn mesh_section(input: &[u8], output: &mut [u8]) -> Result<usize, jint> {
    let (palette, cells) = parse_input(input)?;
    let directions: Vec<Vec<Vertex>> = pool().install(|| {
        FACES.par_iter().map(|face| mesh_direction(*face, &palette, &cells)).collect()
    });
    let vertices: usize = directions.iter().map(Vec::len).sum();
    if vertices > MAX_VERTICES {
        return Err(ERR_OUTPUT_LIMIT);
    }
    let bytes_needed = vertices.checked_mul(VERTEX_STRIDE).ok_or(ERR_OUTPUT_LIMIT)?;
    if bytes_needed > output.len() {
        return Err(ERR_OUTPUT_TOO_SMALL);
    }

    let mut offset = 0;
    for direction in directions {
        for vertex in direction {
            write_vertex(output, offset, vertex);
            offset += VERTEX_STRIDE;
        }
    }
    Ok(vertices)
}

fn mesh_jni(env: JNIEnv, input: JByteBuffer, output: JByteBuffer) -> jint {
    let input_capacity = match env.get_direct_buffer_capacity(&input) {
        Ok(value) if value >= HEADER_BYTES => value,
        _ => return ERR_DIRECT_BUFFER,
    };
    let output_capacity = match env.get_direct_buffer_capacity(&output) {
        Ok(value) => value,
        _ => return ERR_DIRECT_BUFFER,
    };
    let input_ptr = match env.get_direct_buffer_address(&input) {
        Ok(ptr) if !ptr.is_null() => ptr as *const u8,
        _ => return ERR_DIRECT_BUFFER,
    };
    let output_ptr = match env.get_direct_buffer_address(&output) {
        Ok(ptr) if !ptr.is_null() => ptr,
        _ => return ERR_DIRECT_BUFFER,
    };

    // Pointers are used only while both Java direct buffers remain strongly
    // reachable for the duration of this synchronous native call.
    let input_bytes = unsafe { slice::from_raw_parts(input_ptr, input_capacity) };
    let output_bytes = unsafe { slice::from_raw_parts_mut(output_ptr, output_capacity) };
    match mesh_section(input_bytes, output_bytes) {
        Ok(count) => count as jint,
        Err(code) => code,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_euthanized_whatoptimizations_Native_hello0(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        // Construct the bounded helper pool during mod initialization rather
        // than charging its thread-creation cost to the first chunk section.
        let worker_count = pool().current_num_threads();
        println!(
            "[What-Optimizations/Rust] Native.hello() succeeded; {} meshing workers prewarmed",
            worker_count
        );
        0
    }))
    .unwrap_or(ERR_PANIC)
}

/// Return: non-negative count of 16-byte vertices or a negative ABI error code.
#[no_mangle]
pub extern "system" fn Java_dev_euthanized_whatoptimizations_Native_meshSection0(
    env: JNIEnv,
    _class: JClass,
    input: JByteBuffer,
    output: JByteBuffer,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| mesh_jni(env, input, output))).unwrap_or(ERR_PANIC)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(solids: &[[usize; 3]]) -> Vec<u8> {
        let count = if solids.is_empty() { 1 } else { 2 };
        let palette_offset = HEADER_BYTES;
        let cells_offset = palette_offset + count * PALETTE_ENTRY_BYTES;
        let total = cells_offset + CELL_COUNT * CELL_ENTRY_BYTES;
        let mut data = vec![0u8; total];

        data[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        data[4..6].copy_from_slice(&VERSION.to_le_bytes());
        data[6..8].copy_from_slice(&(HEADER_BYTES as u16).to_le_bytes());
        data[8..10].copy_from_slice(&(GRID as u16).to_le_bytes());
        data[10..12].copy_from_slice(&(GRID as u16).to_le_bytes());
        data[12..14].copy_from_slice(&(GRID as u16).to_le_bytes());
        data[14..16].copy_from_slice(&(count as u16).to_le_bytes());
        data[16..20].copy_from_slice(&(CELL_COUNT as u32).to_le_bytes());
        data[20..22].copy_from_slice(&(PALETTE_ENTRY_BYTES as u16).to_le_bytes());
        data[22..24].copy_from_slice(&(CELL_ENTRY_BYTES as u16).to_le_bytes());
        data[24..28].copy_from_slice(&(palette_offset as u32).to_le_bytes());
        data[28..32].copy_from_slice(&(cells_offset as u32).to_le_bytes());
        data[32..36].copy_from_slice(&(total as u32).to_le_bytes());

        if count == 2 {
            let p = palette_offset + PALETTE_ENTRY_BYTES;
            data[p..p + 4].copy_from_slice(&1u32.to_le_bytes());
            data[p + 4] = FLAG_MESHABLE | FLAG_OCCLUDES;
            data[p + 5] = 0x3f;
        }

        for i in 0..CELL_COUNT {
            let p = cells_offset + i * CELL_ENTRY_BYTES;
            data[p..p + 2].copy_from_slice(&0u16.to_le_bytes());
            data[p + 2] = 15;
        }
        for xyz in solids {
            let p = cells_offset + grid_index(xyz[0] + 1, xyz[1] + 1, xyz[2] + 1) * CELL_ENTRY_BYTES;
            data[p..p + 2].copy_from_slice(&1u16.to_le_bytes());
        }
        data
    }

    #[test]
    fn empty_section_has_no_vertices() {
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        assert_eq!(mesh_section(&fixture(&[]), &mut output).unwrap(), 0);
    }

    #[test]
    fn one_cube_has_six_triangle_pairs() {
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        assert_eq!(mesh_section(&fixture(&[[8, 8, 8]]), &mut output).unwrap(), 36);
        assert_eq!(&output[0..4], &[8, 8, 8, 0]);
    }

    #[test]
    fn two_adjacent_cubes_merge_to_a_box() {
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        assert_eq!(mesh_section(&fixture(&[[8, 8, 8], [9, 8, 8]]), &mut output).unwrap(), 36);
    }

    #[test]
    fn padded_neighbor_culls_the_outside_face() {
        let mut data = fixture(&[[15, 8, 8]]);
        let cells_offset = read_u32(&data, 28).unwrap() as usize;
        let border = grid_index(17, 9, 9);
        let p = cells_offset + border * CELL_ENTRY_BYTES;
        data[p..p + 2].copy_from_slice(&1u16.to_le_bytes());
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        assert_eq!(mesh_section(&data, &mut output).unwrap(), 30);
    }

    #[test]
    fn malformed_header_is_rejected() {
        let mut input = fixture(&[[8, 8, 8]]);
        input[0] ^= 0xff;
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        assert_eq!(mesh_section(&input, &mut output), Err(ERR_BAD_HEADER));
    }

    #[test]
    fn invalid_palette_index_is_rejected() {
        let mut input = fixture(&[[8, 8, 8]]);
        let offset = read_u32(&input, 28).unwrap() as usize;
        input[offset..offset + 2].copy_from_slice(&99u16.to_le_bytes());
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        assert_eq!(mesh_section(&input, &mut output), Err(ERR_BAD_PALETTE_INDEX));
    }


    #[test]
    fn face_mask_limits_output_to_enabled_directions() {
        let mut input = fixture(&[[8, 8, 8]]);
        let palette_offset = read_u32(&input, 24).unwrap() as usize;
        let solid_entry = palette_offset + PALETTE_ENTRY_BYTES;
        input[solid_entry + 5] = 1 << 0;

        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        let vertices = mesh_section(&input, &mut output).unwrap();
        assert_eq!(vertices, 6);
        for vertex in output[..vertices * VERTEX_STRIDE].chunks_exact(VERTEX_STRIDE) {
            assert_eq!(vertex[3], 0);
        }
    }

    #[test]
    fn non_meshable_opaque_cells_emit_no_faces() {
        let mut input = fixture(&[[8, 8, 8]]);
        let palette_offset = read_u32(&input, 24).unwrap() as usize;
        let solid_entry = palette_offset + PALETTE_ENTRY_BYTES;
        input[solid_entry + 4] = FLAG_OCCLUDES;

        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        assert_eq!(mesh_section(&input, &mut output).unwrap(), 0);
    }

    #[test]
    fn opposite_section_corners_use_the_padding_without_out_of_bounds_access() {
        let input = fixture(&[[0, 0, 0], [15, 15, 15]]);
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        assert_eq!(mesh_section(&input, &mut output).unwrap(), 72);
    }

    #[test]
    fn checkerboard_opaque_section_fits_maximum_output_capacity() {
        let mut solids = Vec::with_capacity(CELL_COUNT / 2);
        for z in 0..16 {
            for y in 0..16 {
                for x in 0..16 {
                    if (x + y + z) % 2 == 0 {
                        solids.push([x, y, z]);
                    }
                }
            }
        }

        assert_eq!(solids.len(), (16 * 16 * 16) / 2);
        let input = fixture(&solids);
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        assert_eq!(mesh_section(&input, &mut output).unwrap(), MAX_VERTICES);
    }

    #[test]
    fn isolated_cube_keeps_expected_uniform_corner_light_and_ao() {
        let input = fixture(&[[8, 8, 8]]);
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        let vertices = mesh_section(&input, &mut output).unwrap();
        assert_eq!(vertices, 36);

        for vertex in output[..vertices * VERTEX_STRIDE].chunks_exact(VERTEX_STRIDE) {
            assert_eq!(vertex[8], 15, "sky light should remain fully lit in this fixture");
            assert_eq!(vertex[9], 0, "block light should remain zero in this fixture");
            assert_eq!(vertex[10], 3, "unoccluded corners should retain full AO");
        }
    }

    #[test]
    fn single_cube_emits_all_six_faces_with_valid_local_coordinates() {
        let input = fixture(&[[8, 8, 8]]);
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        let vertices = mesh_section(&input, &mut output).unwrap();
        assert_eq!(vertices, 36);

        let mut face_counts = [0usize; 6];
        for vertex in output[..vertices * VERTEX_STRIDE].chunks_exact(VERTEX_STRIDE) {
            assert!(vertex[0] <= 16 && vertex[1] <= 16 && vertex[2] <= 16);
            let face = vertex[3] as usize;
            assert!(face < face_counts.len());
            face_counts[face] += 1;
        }
        assert_eq!(face_counts, [6; 6]);
    }

    #[test]
    fn adjacent_cubes_merge_along_each_axis() {
        for solids in [
            [[8, 8, 8], [9, 8, 8]],
            [[8, 8, 8], [8, 9, 8]],
            [[8, 8, 8], [8, 8, 9]],
        ] {
            let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
            assert_eq!(mesh_section(&fixture(&solids), &mut output).unwrap(), 36);
        }
    }

    #[test]
    fn solid_two_by_two_by_two_box_collapses_to_six_quads() {
        let mut solids = Vec::new();
        for x in 7..=8 {
            for y in 7..=8 {
                for z in 7..=8 {
                    solids.push([x, y, z]);
                }
            }
        }
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        assert_eq!(mesh_section(&fixture(&solids), &mut output).unwrap(), 36);
    }

    #[test]
    fn diagonal_cubes_keep_separate_surfaces() {
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        assert_eq!(
            mesh_section(&fixture(&[[7, 7, 7], [8, 8, 8]]), &mut output).unwrap(),
            72
        );
    }

    #[test]
    fn single_cube_triangles_have_outward_winding() {
        let input = fixture(&[[8, 8, 8]]);
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        let vertices = mesh_section(&input, &mut output).unwrap();
        assert_eq!(vertices, 36);

        for triangle in output[..vertices * VERTEX_STRIDE]
            .chunks_exact(3 * VERTEX_STRIDE)
        {
            let face = triangle[3] as usize;
            let point = |offset: usize| -> [i32; 3] {
                [
                    triangle[offset] as i32,
                    triangle[offset + 1] as i32,
                    triangle[offset + 2] as i32,
                ]
            };
            let a = point(0);
            let b = point(VERTEX_STRIDE);
            let c = point(2 * VERTEX_STRIDE);
            let ab = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
            let ac = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
            let cross = [
                ab[1] * ac[2] - ab[2] * ac[1],
                ab[2] * ac[0] - ab[0] * ac[2],
                ab[0] * ac[1] - ab[1] * ac[0],
            ];
            let (axis, direction) = match face {
                0 => (0, -1),
                1 => (0, 1),
                2 => (1, -1),
                3 => (1, 1),
                4 => (2, -1),
                5 => (2, 1),
                _ => panic!("unexpected face ID {face}"),
            };

            assert!(
                cross[axis] * direction > 0,
                "triangle winding points inward for face {face}: {cross:?}"
            );
            for (component, value) in cross.iter().enumerate() {
                if component != axis {
                    assert_eq!(*value, 0, "face {face} has a non-axis-aligned normal");
                }
            }
        }
    }

    #[test]
    fn inconsistent_total_byte_count_is_rejected() {
        let mut input = fixture(&[[8, 8, 8]]);
        let incorrect_total = (input.len() - 1) as u32;
        input[32..36].copy_from_slice(&incorrect_total.to_le_bytes());
        let mut output = vec![0; MAX_VERTICES * VERTEX_STRIDE];
        assert_eq!(mesh_section(&input, &mut output), Err(ERR_BAD_HEADER));
    }

    #[test]
    fn too_small_output_is_rejected() {
        let input = fixture(&[[8, 8, 8]]);
        let mut output = vec![0; 8];
        assert_eq!(mesh_section(&input, &mut output), Err(ERR_OUTPUT_TOO_SMALL));
    }
}
