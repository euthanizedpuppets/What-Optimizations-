//! Bounded asynchronous native mesh jobs and section visibility primitives.
//!
//! JNI entry points are bulk operations. Worker threads only touch owned Rust
//! byte vectors; they never attach to or callback into the JVM.

use super::*;
use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use std::collections::{HashMap, VecDeque};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

const ERR_QUEUE_FULL: jint = -7;
const ERR_UNKNOWN_HANDLE: jint = -8;
const ERR_VISIBILITY_ABI: jint = -9;
const MAX_NATIVE_JOBS: usize = 96;
const MAX_RESULT_BYTES: usize = 32 * 1024 * 1024;
const VISIBILITY_HEADER_BYTES: usize = 16;
const VISIBILITY_RECORD_BYTES: usize = 16;
const PLANE_BYTES: usize = 6 * 4 * 4;
const FACE_NEG_X: u8 = 1 << 0;
const FACE_POS_X: u8 = 1 << 1;
const FACE_NEG_Y: u8 = 1 << 2;
const FACE_POS_Y: u8 = 1 << 3;
const FACE_NEG_Z: u8 = 1 << 4;
const FACE_POS_Z: u8 = 1 << 5;

static NEXT_TICKET: AtomicU64 = AtomicU64::new(1);
static QUEUED_RESULT_BYTES: AtomicUsize = AtomicUsize::new(0);
static JOBS: OnceLock<Mutex<HashMap<u64, Arc<JobCell>>>> = OnceLock::new();

fn jobs() -> &'static Mutex<HashMap<u64, Arc<JobCell>>> {
    JOBS.get_or_init(|| Mutex::new(HashMap::with_capacity(MAX_NATIVE_JOBS)))
}

struct JobCell {
    // Native records retain the section key and generation for the whole
    // ticket lifetime. Java independently checks against the latest generation.
    _section_key: i64,
    _generation: i64,
    cancelled: AtomicBool,
    state: Mutex<JobState>,
}

enum JobState {
    Pending,
    Ready(Result<(usize, Vec<u8>), jint>),
    Cancelled,
}

fn input_length(bytes: &[u8]) -> Result<usize, jint> {
    if bytes.len() < HEADER_BYTES {
        return Err(ERR_BAD_HEADER);
    }
    let length = read_u32(bytes, 32).ok_or(ERR_BAD_HEADER)? as usize;
    if length < HEADER_BYTES || length > bytes.len() {
        return Err(ERR_BAD_HEADER);
    }
    Ok(length)
}

fn reserve_result_bytes(bytes: usize) -> bool {
    QUEUED_RESULT_BYTES
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |current| {
            current
                .checked_add(bytes)
                .filter(|next| *next <= MAX_RESULT_BYTES)
        })
        .is_ok()
}

fn finish_job(cell: &JobCell, result: Result<(usize, Vec<u8>), jint>) {
    let mut state = cell
        .state
        .lock()
        .unwrap_or_else(|poison| poison.into_inner());
    if cell.cancelled.load(Ordering::Acquire) || matches!(&*state, JobState::Cancelled) {
        return;
    }

    let final_result = match result {
        Ok((count, bytes)) if reserve_result_bytes(bytes.len()) => Ok((count, bytes)),
        Ok(_) => Err(ERR_QUEUE_FULL),
        Err(code) => Err(code),
    };
    *state = JobState::Ready(final_result);
}

fn run_owned_snapshot(snapshot: Vec<u8>) -> Result<(usize, Vec<u8>), jint> {
    let output_capacity = MAX_VERTICES
        .checked_mul(VERTEX_STRIDE)
        .ok_or(ERR_OUTPUT_LIMIT)?;
    let mut scratch = vec![0u8; output_capacity];
    let vertex_count = mesh_section(&snapshot, &mut scratch)?;
    let used = vertex_count
        .checked_mul(VERTEX_STRIDE)
        .ok_or(ERR_OUTPUT_LIMIT)?;
    scratch.truncate(used);
    scratch.shrink_to_fit();
    Ok((vertex_count, scratch))
}

/// WOM2 snapshot: the result's first tuple element is the total output byte
/// count (Java reads the self-describing layer table from the bytes).
fn run_owned_snapshot_v2(snapshot: Vec<u8>) -> Result<(usize, Vec<u8>), jint> {
    let output = crate::mesh_v2::mesh_section_v2(&snapshot)?;
    let total = output.len();
    Ok((total, output))
}

fn submit_snapshot(
    env: &JNIEnv,
    input: &JByteBuffer,
    section_key: i64,
    generation: i64,
    version: u16,
) -> Result<jlong, jint> {
    let min_header = if version == 2 {
        crate::mesh_v2::V2_HEADER_BYTES
    } else {
        HEADER_BYTES
    };
    let capacity = match env.get_direct_buffer_capacity(input) {
        Ok(value) if value >= min_header => value,
        _ => return Err(ERR_DIRECT_BUFFER),
    };
    let ptr = match env.get_direct_buffer_address(input) {
        Ok(ptr) if !ptr.is_null() => ptr as *const u8,
        _ => return Err(ERR_DIRECT_BUFFER),
    };

    // Copy before returning; Java immediately reuses the thread-local buffer.
    let live = unsafe { slice::from_raw_parts(ptr, capacity) };
    let total = match input_length(live) {
        Ok(value) => value,
        Err(code) => return Err(code),
    };
    if total > capacity {
        return Err(ERR_BAD_HEADER);
    }
    let owned = live[..total].to_vec();

    let mut registry = match jobs().lock() {
        Ok(guard) => guard,
        Err(poison) => poison.into_inner(),
    };
    if registry.len() >= MAX_NATIVE_JOBS {
        return Err(ERR_QUEUE_FULL);
    }

    let raw_ticket = NEXT_TICKET.fetch_add(1, Ordering::Relaxed);
    if raw_ticket == 0 || raw_ticket > i64::MAX as u64 {
        return Err(ERR_QUEUE_FULL);
    }
    let cell = Arc::new(JobCell {
        _section_key: section_key,
        _generation: generation,
        cancelled: AtomicBool::new(false),
        state: Mutex::new(JobState::Pending),
    });
    registry.insert(raw_ticket, Arc::clone(&cell));
    drop(registry);

    super::pool().spawn(move || {
        let result = catch_unwind(AssertUnwindSafe(|| {
            if version == 2 {
                run_owned_snapshot_v2(owned)
            } else {
                run_owned_snapshot(owned)
            }
        }))
        .unwrap_or(Err(ERR_PANIC));
        finish_job(&cell, result);
    });
    Ok(raw_ticket as jlong)
}

#[no_mangle]
pub extern "system" fn Java_dev_euthanized_whatoptimizations_Native_submitSection0(
    env: JNIEnv,
    _class: JClass,
    input: JByteBuffer,
    section_key: jlong,
    generation: jlong,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        match submit_snapshot(&env, &input, section_key, generation, 1) {
            Ok(ticket) => ticket,
            Err(code) => code as jlong,
        }
    }))
    .unwrap_or(ERR_PANIC as jlong)
}

/// WOM2 submission: the input is a captured vanilla vertex stream (see
/// docs/NATIVE_TERRAIN_PIPELINE.md). Returns a ticket handle or a negative
/// error code.
#[no_mangle]
pub extern "system" fn Java_dev_euthanized_whatoptimizations_Native_submitSectionV2Native(
    env: JNIEnv,
    _class: JClass,
    input: JByteBuffer,
    section_key: jlong,
    generation: jlong,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        match submit_snapshot(&env, &input, section_key, generation, 2) {
            Ok(ticket) => ticket,
            Err(code) => code as jlong,
        }
    }))
    .unwrap_or(ERR_PANIC as jlong)
}

/// Return: 0 = pending; positive = vertex_count + 1; negative = an error code.
#[no_mangle]
pub extern "system" fn Java_dev_euthanized_whatoptimizations_Native_pollCompleted0(
    env: JNIEnv,
    _class: JClass,
    ticket: jlong,
    output: JByteBuffer,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if ticket <= 0 {
            return ERR_UNKNOWN_HANDLE;
        }
        let output_capacity = match env.get_direct_buffer_capacity(&output) {
            Ok(value) => value,
            Err(_) => return ERR_DIRECT_BUFFER,
        };
        let output_ptr = match env.get_direct_buffer_address(&output) {
            Ok(ptr) if !ptr.is_null() => ptr,
            _ => return ERR_DIRECT_BUFFER,
        };

        let cell = {
            let registry = match jobs().lock() {
                Ok(guard) => guard,
                Err(poison) => poison.into_inner(),
            };
            match registry.get(&(ticket as u64)) {
                Some(value) => Arc::clone(value),
                None => return ERR_UNKNOWN_HANDLE,
            }
        };
        let (_section_key, _generation) = (cell._section_key, cell._generation);
        let state = match cell.state.lock() {
            Ok(guard) => guard,
            Err(poison) => poison.into_inner(),
        };
        match &*state {
            JobState::Pending => 0,
            JobState::Cancelled => ERR_UNKNOWN_HANDLE,
            JobState::Ready(Err(code)) => *code,
            JobState::Ready(Ok((vertices, bytes))) => {
                if bytes.len() > output_capacity {
                    return ERR_OUTPUT_TOO_SMALL;
                }
                unsafe {
                    std::ptr::copy_nonoverlapping(bytes.as_ptr(), output_ptr, bytes.len());
                }
                (*vertices as jint).saturating_add(1)
            }
        }
    }))
    .unwrap_or(ERR_PANIC)
}

#[no_mangle]
pub extern "system" fn Java_dev_euthanized_whatoptimizations_Native_release0(
    _env: JNIEnv,
    _class: JClass,
    ticket: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if ticket <= 0 {
            return ERR_UNKNOWN_HANDLE;
        }
        let cell = {
            let mut registry = match jobs().lock() {
                Ok(guard) => guard,
                Err(poison) => poison.into_inner(),
            };
            registry.remove(&(ticket as u64))
        };
        let Some(cell) = cell else {
            return ERR_UNKNOWN_HANDLE;
        };
        cell.cancelled.store(true, Ordering::Release);
        let mut state = match cell.state.lock() {
            Ok(guard) => guard,
            Err(poison) => poison.into_inner(),
        };
        if let JobState::Ready(Ok((_, bytes))) = &*state {
            QUEUED_RESULT_BYTES.fetch_sub(bytes.len(), Ordering::AcqRel);
        }
        *state = JobState::Cancelled;
        0
    }))
    .unwrap_or(ERR_PANIC)
}

#[derive(Clone, Copy)]
struct SectionNode {
    origin: [i32; 3],
    open_faces: u8,
}

fn is_in_frustum(origin: [i32; 3], planes: &[[f32; 4]; 6]) -> bool {
    for plane in planes {
        let x = origin[0] as f32 + if plane[0] >= 0.0 { 16.0 } else { 0.0 };
        let y = origin[1] as f32 + if plane[1] >= 0.0 { 16.0 } else { 0.0 };
        let z = origin[2] as f32 + if plane[2] >= 0.0 { 16.0 } else { 0.0 };
        if plane[0] * x + plane[1] * y + plane[2] * z + plane[3] < 0.0 {
            return false;
        }
    }
    true
}

fn visible_indices(nodes: &[SectionNode], planes: &[[f32; 4]; 6], camera: [i32; 3]) -> Vec<usize> {
    let frustum: Vec<bool> = nodes
        .iter()
        .map(|node| is_in_frustum(node.origin, planes))
        .collect();
    let by_origin: HashMap<[i32; 3], usize> = nodes
        .iter()
        .enumerate()
        .map(|(index, node)| (node.origin, index))
        .collect();
    let camera_section = [
        camera[0].div_euclid(16) * 16,
        camera[1].div_euclid(16) * 16,
        camera[2].div_euclid(16) * 16,
    ];
    let Some(&start) = by_origin.get(&camera_section) else {
        return frustum
            .iter()
            .enumerate()
            .filter_map(|(i, yes)| yes.then_some(i))
            .collect();
    };

    let steps: [([i32; 3], u8, u8); 6] = [
        ([-16, 0, 0], FACE_NEG_X, FACE_POS_X),
        ([16, 0, 0], FACE_POS_X, FACE_NEG_X),
        ([0, -16, 0], FACE_NEG_Y, FACE_POS_Y),
        ([0, 16, 0], FACE_POS_Y, FACE_NEG_Y),
        ([0, 0, -16], FACE_NEG_Z, FACE_POS_Z),
        ([0, 0, 16], FACE_POS_Z, FACE_NEG_Z),
    ];
    let mut reachable = vec![false; nodes.len()];
    let mut queue = VecDeque::with_capacity(nodes.len().min(256));
    reachable[start] = true;
    queue.push_back(start);
    while let Some(index) = queue.pop_front() {
        for (offset, face, opposite) in steps {
            if nodes[index].open_faces & face == 0 {
                continue;
            }
            let p = nodes[index].origin;
            let neighbor_origin = [p[0] + offset[0], p[1] + offset[1], p[2] + offset[2]];
            let Some(&next) = by_origin.get(&neighbor_origin) else {
                continue;
            };
            if !reachable[next] && nodes[next].open_faces & opposite != 0 {
                reachable[next] = true;
                queue.push_back(next);
            }
        }
    }
    nodes
        .iter()
        .enumerate()
        .filter_map(|(i, _)| (reachable[i] && frustum[i]).then_some(i))
        .collect()
}

/// Input: u32 count + 16-byte records (i32 origin xyz, u8 open-face mask,
/// u8 reserved, u16 vertex count). Planes are six native-endian
/// vec4 equations with ax+by+cz+d >= 0 on the inside. Output is u32 indices.
#[no_mangle]
pub extern "system" fn Java_dev_euthanized_whatoptimizations_Native_visibleSections0(
    env: JNIEnv,
    _class: JClass,
    sections: JByteBuffer,
    planes: JByteBuffer,
    output: JByteBuffer,
    camera_x: jint,
    camera_y: jint,
    camera_z: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let sections_len = match env.get_direct_buffer_capacity(&sections) {
            Ok(value) if value >= VISIBILITY_HEADER_BYTES => value,
            _ => return ERR_DIRECT_BUFFER,
        };
        let planes_len = match env.get_direct_buffer_capacity(&planes) {
            Ok(value) if value >= PLANE_BYTES => value,
            _ => return ERR_DIRECT_BUFFER,
        };
        let output_len = match env.get_direct_buffer_capacity(&output) {
            Ok(value) => value,
            _ => return ERR_DIRECT_BUFFER,
        };
        let sections_ptr = match env.get_direct_buffer_address(&sections) {
            Ok(ptr) if !ptr.is_null() => ptr as *const u8,
            _ => return ERR_DIRECT_BUFFER,
        };
        let planes_ptr = match env.get_direct_buffer_address(&planes) {
            Ok(ptr) if !ptr.is_null() => ptr as *const u8,
            _ => return ERR_DIRECT_BUFFER,
        };
        let output_ptr = match env.get_direct_buffer_address(&output) {
            Ok(ptr) if !ptr.is_null() => ptr,
            _ => return ERR_DIRECT_BUFFER,
        };
        let section_bytes = unsafe { slice::from_raw_parts(sections_ptr, sections_len) };
        let plane_bytes = unsafe { slice::from_raw_parts(planes_ptr, planes_len) };
        let count = match read_u32(section_bytes, 0) {
            Some(value) => value as usize,
            None => return ERR_VISIBILITY_ABI,
        };
        let records_bytes = match count.checked_mul(VISIBILITY_RECORD_BYTES) {
            Some(value) => value,
            None => return ERR_VISIBILITY_ABI,
        };
        if VISIBILITY_HEADER_BYTES
            .checked_add(records_bytes)
            .map_or(true, |required| required > sections_len)
            || count
                .checked_mul(4)
                .map_or(true, |bytes| bytes > output_len)
        {
            return ERR_VISIBILITY_ABI;
        }

        let mut plane_data = [[0.0f32; 4]; 6];
        for (index, plane) in plane_data.iter_mut().enumerate() {
            for (component, value) in plane.iter_mut().enumerate() {
                let offset = index * 16 + component * 4;
                let Some(raw) = plane_bytes.get(offset..offset + 4) else {
                    return ERR_VISIBILITY_ABI;
                };
                *value = f32::from_ne_bytes([raw[0], raw[1], raw[2], raw[3]]);
            }
        }

        let mut nodes = Vec::with_capacity(count);
        for index in 0..count {
            let offset = VISIBILITY_HEADER_BYTES + index * VISIBILITY_RECORD_BYTES;
            let Some(x) = read_u32(section_bytes, offset) else {
                return ERR_VISIBILITY_ABI;
            };
            let Some(y) = read_u32(section_bytes, offset + 4) else {
                return ERR_VISIBILITY_ABI;
            };
            let Some(z) = read_u32(section_bytes, offset + 8) else {
                return ERR_VISIBILITY_ABI;
            };
            nodes.push(SectionNode {
                origin: [x as i32, y as i32, z as i32],
                open_faces: section_bytes[offset + 12],
            });
        }

        let visible = visible_indices(&nodes, &plane_data, [camera_x, camera_y, camera_z]);
        for (index, visible_index) in visible.iter().enumerate() {
            let bytes = (*visible_index as u32).to_ne_bytes();
            unsafe {
                std::ptr::copy_nonoverlapping(bytes.as_ptr(), output_ptr.add(index * 4), 4);
            }
        }
        visible.len() as jint
    }))
    .unwrap_or(ERR_PANIC)
}

#[cfg(test)]
mod async_visibility_tests {
    use super::*;

    fn all_inside_planes() -> [[f32; 4]; 6] {
        [[0.0, 0.0, 0.0, 1.0]; 6]
    }

    #[test]
    fn frustum_culling_rejects_outside_section() {
        let planes = [
            [1.0, 0.0, 0.0, 0.0],
            [-1.0, 0.0, 0.0, 31.0],
            [0.0, 1.0, 0.0, 100.0],
            [0.0, -1.0, 0.0, 100.0],
            [0.0, 0.0, 1.0, 100.0],
            [0.0, 0.0, -1.0, 100.0],
        ];
        let nodes = [
            SectionNode {
                origin: [0, 0, 0],
                open_faces: 0x3f,
            },
            SectionNode {
                origin: [32, 0, 0],
                open_faces: 0x3f,
            },
        ];
        let visible = visible_indices(&nodes, &planes, [0, 0, 0]);
        assert!(visible.contains(&0));
        assert!(!visible.contains(&1));
    }

    #[test]
    fn cave_graph_walk_traverses_only_mutually_open_section_faces() {
        let nodes = [
            SectionNode {
                origin: [0, 0, 0],
                open_faces: FACE_POS_X,
            },
            SectionNode {
                origin: [16, 0, 0],
                open_faces: FACE_NEG_X | FACE_POS_X,
            },
            SectionNode {
                origin: [32, 0, 0],
                open_faces: FACE_NEG_X,
            },
            SectionNode {
                origin: [0, 0, 16],
                open_faces: 0x3f,
            },
        ];
        let visible = visible_indices(&nodes, &all_inside_planes(), [2, 2, 2]);
        assert!(visible.contains(&0));
        assert!(visible.contains(&1));
        assert!(visible.contains(&2));
        assert!(!visible.contains(&3));
    }

    #[test]
    fn missing_camera_section_fails_open_to_frustum_results() {
        let nodes = [SectionNode {
            origin: [32, 0, 0],
            open_faces: 0,
        }];
        assert_eq!(
            visible_indices(&nodes, &all_inside_planes(), [0, 0, 0]),
            vec![0]
        );
    }
}
