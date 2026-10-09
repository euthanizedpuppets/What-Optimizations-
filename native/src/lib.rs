//! Phase 0 JNI smoke test for the What-Optimizations Minecraft mod.
//!
//! Keep all exported JNI entry points panic-contained. Later phases will add
//! bulk section-buffer APIs; do not add per-block JNI calls.

use jni::objects::JClass;
use jni::sys::jint;
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

fn hello_impl() -> jint {
    println!("[What-Optimizations/Rust] Native.hello() says hello from Rust!");
    0
}

/// JNI entry point for dev.euthanized.whatoptimizations.Native.hello0.
///
/// Returns 0 on success and a negative error code if Rust panics. Panics never
/// unwind across the JVM's native ABI boundary.
#[no_mangle]
pub extern "system" fn Java_dev_euthanized_whatoptimizations_Native_hello0(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    catch_unwind(AssertUnwindSafe(hello_impl)).unwrap_or(-1)
}

#[cfg(test)]
mod tests {
    #[test]
    fn hello_implementation_reports_success() {
        assert_eq!(super::hello_impl(), 0);
    }
}
