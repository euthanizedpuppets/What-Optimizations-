//! Optional render-thread OpenGL calls through entry points resolved by GLFW.
//!
//! Java resolves the exact OpenGL 3.3 entry points with glfwGetProcAddress while
//! the context is current and hands the addresses to this bulk JNI call. The
//! function saves/restores each GL state it mutates. This function must never
//! be queued onto Rayon: it runs synchronously on the calling render thread.

use jni::objects::{JByteBuffer, JClass, JLongArray};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;

const ERR_DIRECT_BUFFER: jint = -2;
const ERR_PANIC: jint = -1;
const ERR_PROCEDURE: jint = -10;
const ERR_DRAW_COUNT: jint = -11;
const ERR_BUFFER_SIZE: jint = -12;
const PROC_COUNT: usize = 12;
const MAX_DRAWS: usize = 32_768;

type GlGetIntegerv = unsafe extern "system" fn(u32, *mut i32);
type GlGetBooleanv = unsafe extern "system" fn(u32, *mut u8);
type GlIsEnabled = unsafe extern "system" fn(u32) -> u8;
type GlUseProgram = unsafe extern "system" fn(u32);
type GlBindVertexArray = unsafe extern "system" fn(u32);
type GlBindBuffer = unsafe extern "system" fn(u32, u32);
type GlDepthFunc = unsafe extern "system" fn(u32);
type GlDepthMask = unsafe extern "system" fn(u8);
type GlBlendFuncSeparate = unsafe extern "system" fn(u32, u32, u32, u32);
type GlEnable = unsafe extern "system" fn(u32);
type GlDisable = unsafe extern "system" fn(u32);
type GlMultiDrawArrays = unsafe extern "system" fn(u32, *const i32, *const i32, i32);

#[no_mangle]
pub extern "system" fn Java_dev_euthanized_whatoptimizations_Native_drawMultiDrawOpenGL0(
    mut env: JNIEnv,
    _class: JClass,
    program: jint,
    vao: jint,
    array_buffer: jint,
    firsts: JByteBuffer,
    counts: JByteBuffer,
    draw_count: jint,
    procedures: JLongArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if draw_count < 0 || draw_count as usize > MAX_DRAWS {
            return ERR_DRAW_COUNT;
        }
        let first_capacity = match env.get_direct_buffer_capacity(&firsts) {
            Ok(value) if value >= draw_count as usize * 4 => value,
            _ => return ERR_DIRECT_BUFFER,
        };
        let count_capacity = match env.get_direct_buffer_capacity(&counts) {
            Ok(value) if value >= draw_count as usize * 4 => value,
            _ => return ERR_DIRECT_BUFFER,
        };
        if first_capacity < draw_count as usize * 4 || count_capacity < draw_count as usize * 4 {
            return ERR_BUFFER_SIZE;
        }
        let first_ptr = match env.get_direct_buffer_address(&firsts) {
            Ok(pointer) if !pointer.is_null() => pointer as *const i32,
            _ => return ERR_DIRECT_BUFFER,
        };
        let count_ptr = match env.get_direct_buffer_address(&counts) {
            Ok(pointer) if !pointer.is_null() => pointer as *const i32,
            _ => return ERR_DIRECT_BUFFER,
        };
        let first_slice = unsafe { slice::from_raw_parts(first_ptr, draw_count as usize) };
        let count_slice = unsafe { slice::from_raw_parts(count_ptr, draw_count as usize) };
        if first_slice.iter().any(|value| *value < 0) || count_slice.iter().any(|value| *value < 0)
        {
            return ERR_DRAW_COUNT;
        }

        let actual_procs = match env.get_array_length(&procedures) {
            Ok(value) if value as usize == PROC_COUNT => value as usize,
            _ => return ERR_PROCEDURE,
        };
        let mut addresses = [0i64; PROC_COUNT];
        if env
            .get_long_array_region(&procedures, 0, &mut addresses)
            .is_err()
            || addresses.iter().any(|address| *address == 0)
        {
            return ERR_PROCEDURE;
        }
        if actual_procs != PROC_COUNT {
            return ERR_PROCEDURE;
        }

        // SAFETY: all addresses originate from glfwGetProcAddress with a live,
        // current OpenGL context. Signatures match OpenGL 3.3 core prototypes.
        let get_integer: GlGetIntegerv = unsafe { std::mem::transmute(addresses[0] as usize) };
        let get_boolean: GlGetBooleanv = unsafe { std::mem::transmute(addresses[1] as usize) };
        let is_enabled: GlIsEnabled = unsafe { std::mem::transmute(addresses[2] as usize) };
        let use_program: GlUseProgram = unsafe { std::mem::transmute(addresses[3] as usize) };
        let bind_vertex_array: GlBindVertexArray =
            unsafe { std::mem::transmute(addresses[4] as usize) };
        let bind_buffer: GlBindBuffer = unsafe { std::mem::transmute(addresses[5] as usize) };
        let depth_func: GlDepthFunc = unsafe { std::mem::transmute(addresses[6] as usize) };
        let depth_mask: GlDepthMask = unsafe { std::mem::transmute(addresses[7] as usize) };
        let blend_func_separate: GlBlendFuncSeparate =
            unsafe { std::mem::transmute(addresses[8] as usize) };
        let enable: GlEnable = unsafe { std::mem::transmute(addresses[9] as usize) };
        let disable: GlDisable = unsafe { std::mem::transmute(addresses[10] as usize) };
        let multi_draw_arrays: GlMultiDrawArrays =
            unsafe { std::mem::transmute(addresses[11] as usize) };

        const CURRENT_PROGRAM: u32 = 0x8B8D;
        const VERTEX_ARRAY_BINDING: u32 = 0x85B5;
        const ARRAY_BUFFER_BINDING: u32 = 0x8894;
        const DEPTH_FUNC: u32 = 0x0B74;
        const DEPTH_WRITEMASK: u32 = 0x0B72;
        const BLEND_SRC_RGB: u32 = 0x80C9;
        const BLEND_DST_RGB: u32 = 0x80C8;
        const BLEND_SRC_ALPHA: u32 = 0x80CB;
        const BLEND_DST_ALPHA: u32 = 0x80CA;
        const DEPTH_TEST: u32 = 0x0B71;
        const BLEND: u32 = 0x0BE2;
        const CULL_FACE: u32 = 0x0B44;
        const LEQUAL: u32 = 0x0203;
        const TRIANGLES: u32 = 0x0004;
        const ARRAY_BUFFER: u32 = 0x8892;

        let mut previous_program = 0;
        let mut previous_vao = 0;
        let mut previous_array_buffer = 0;
        let mut previous_depth_func = 0;
        let mut previous_blend_src_rgb = 0;
        let mut previous_blend_dst_rgb = 0;
        let mut previous_blend_src_alpha = 0;
        let mut previous_blend_dst_alpha = 0;
        let mut previous_depth_mask = 1u8;
        unsafe {
            get_integer(CURRENT_PROGRAM, &mut previous_program);
            get_integer(VERTEX_ARRAY_BINDING, &mut previous_vao);
            get_integer(ARRAY_BUFFER_BINDING, &mut previous_array_buffer);
            get_integer(DEPTH_FUNC, &mut previous_depth_func);
            get_integer(BLEND_SRC_RGB, &mut previous_blend_src_rgb);
            get_integer(BLEND_DST_RGB, &mut previous_blend_dst_rgb);
            get_integer(BLEND_SRC_ALPHA, &mut previous_blend_src_alpha);
            get_integer(BLEND_DST_ALPHA, &mut previous_blend_dst_alpha);
            get_boolean(DEPTH_WRITEMASK, &mut previous_depth_mask);
        }
        let previous_depth_test = unsafe { is_enabled(DEPTH_TEST) != 0 };
        let previous_blend = unsafe { is_enabled(BLEND) != 0 };
        let previous_cull_face = unsafe { is_enabled(CULL_FACE) != 0 };

        unsafe {
            enable(DEPTH_TEST);
            depth_func(LEQUAL);
            depth_mask(0);
            disable(BLEND);
            disable(CULL_FACE);
            use_program(program as u32);
            bind_vertex_array(vao as u32);
            bind_buffer(ARRAY_BUFFER, array_buffer as u32);
            multi_draw_arrays(TRIANGLES, first_ptr, count_ptr, draw_count);

            use_program(previous_program as u32);
            bind_vertex_array(previous_vao as u32);
            bind_buffer(ARRAY_BUFFER, previous_array_buffer as u32);
            depth_func(previous_depth_func as u32);
            depth_mask(previous_depth_mask);
            blend_func_separate(
                previous_blend_src_rgb as u32,
                previous_blend_dst_rgb as u32,
                previous_blend_src_alpha as u32,
                previous_blend_dst_alpha as u32,
            );
            if previous_depth_test {
                enable(DEPTH_TEST);
            } else {
                disable(DEPTH_TEST);
            }
            if previous_blend {
                enable(BLEND);
            } else {
                disable(BLEND);
            }
            if previous_cull_face {
                enable(CULL_FACE);
            } else {
                disable(CULL_FACE);
            }
        }
        0
    }))
    .unwrap_or(ERR_PANIC)
}
