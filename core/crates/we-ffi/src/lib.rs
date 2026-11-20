//! C ABI of the World Engine core.
//!
//! Every function here is mirrored by `NativeCore.java` in the mod. Rules:
//! - Bump `WE_ABI_VERSION` on any signature or semantic change.
//! - Never let a panic cross the boundary: wrap work in `guard`.
//! - Return `i32` status codes (`WE_OK` or a negative `WE_ERR_*`).
//!
//! Threading and lifetime: a `WeUniverse` is **not** thread-safe. All calls on
//! one handle must be externally serialized (the mod uses it from the server
//! thread only), and nothing may call into a handle during or after
//! `we_universe_free`.
//!
//! Panics: functions that can panic run inside `guard` and return
//! `WE_ERR_PANIC`. Functions that cannot panic skip it; should one ever panic,
//! Rust aborts at the `extern "C"` boundary (Rust >= 1.81) instead of unwinding
//! into the JVM.

use std::panic::{AssertUnwindSafe, catch_unwind};
use we_orbit::CelestialSystem;

pub const WE_ABI_VERSION: u32 = 1;
pub const WE_OK: i32 = 0;
pub const WE_ERR_NULL: i32 = -1;
pub const WE_ERR_BAD_INDEX: i32 = -2;
pub const WE_ERR_INVALID: i32 = -3;
pub const WE_ERR_PANIC: i32 = -99;

/// Opaque handle owned by the Java side.
pub struct WeUniverse {
    system: CelestialSystem,
    time: f64,
}

fn guard(f: impl FnOnce() -> i32) -> i32 {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(WE_ERR_PANIC)
}

#[unsafe(no_mangle)]
pub extern "C" fn we_abi_version() -> u32 {
    WE_ABI_VERSION
}

/// Create the demo universe (home planet + moon). Returns null on failure.
/// Free with [`we_universe_free`].
#[unsafe(no_mangle)]
pub extern "C" fn we_universe_new_demo() -> *mut WeUniverse {
    catch_unwind(|| {
        Box::into_raw(Box::new(WeUniverse {
            system: CelestialSystem::demo(),
            time: 0.0,
        }))
    })
    .unwrap_or(std::ptr::null_mut())
}

/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`,
/// and must not be used after this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_universe_free(universe: *mut WeUniverse) {
    if !universe.is_null() {
        drop(unsafe { Box::from_raw(universe) });
    }
}

/// Set the simulation time in seconds.
/// Returns `WE_ERR_INVALID` for NaN or infinite times.
///
/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_universe_set_time(universe: *mut WeUniverse, seconds: f64) -> i32 {
    let Some(universe) = (unsafe { universe.as_mut() }) else {
        return WE_ERR_NULL;
    };
    if !seconds.is_finite() {
        return WE_ERR_INVALID;
    }
    universe.time = seconds;
    WE_OK
}

/// Number of celestial bodies; 0 for a null handle.
///
/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_universe_body_count(universe: *const WeUniverse) -> u32 {
    unsafe { universe.as_ref() }.map_or(0, |u| u32::try_from(u.system.len()).unwrap_or(u32::MAX))
}

/// Write the position (metres, Y-up, relative to the root body) of body
/// `index` at the current time into `out_xyz[0..3]`.
///
/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`;
/// `out_xyz` must be null or valid for writing 3 doubles (24 bytes); it need not be aligned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_universe_body_position(
    universe: *const WeUniverse,
    index: u32,
    out_xyz: *mut f64,
) -> i32 {
    let Some(universe) = (unsafe { universe.as_ref() }) else {
        return WE_ERR_NULL;
    };
    if out_xyz.is_null() {
        return WE_ERR_NULL;
    }
    let index = index as usize;
    if index >= universe.system.len() {
        return WE_ERR_BAD_INDEX;
    }
    guard(|| {
        let p = universe.system.position_at(index, universe.time);
        for (i, v) in [p.x, p.y, p.z].into_iter().enumerate() {
            unsafe { out_xyz.add(i).write_unaligned(v) };
        }
        WE_OK
    })
}

/// Copy body `index`'s UTF-8 name (no terminator) into `buf`, truncated to
/// `buf_len` bytes. Returns the full name length in bytes, or a `WE_ERR_*`.
/// Truncation is byte-wise and may split a multi-byte UTF-8 sequence. To get the
/// whole name, call again with a buffer of the returned length. Passing
/// `buf = null, buf_len = 0` is a valid way to ask for the length.
///
/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`;
/// `buf` must be valid for writing `buf_len` bytes (may be null if `buf_len` is 0).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_universe_body_name(
    universe: *const WeUniverse,
    index: u32,
    buf: *mut u8,
    buf_len: u32,
) -> i32 {
    let Some(universe) = (unsafe { universe.as_ref() }) else {
        return WE_ERR_NULL;
    };
    let index = index as usize;
    if index >= universe.system.len() {
        return WE_ERR_BAD_INDEX;
    }
    if buf.is_null() && buf_len > 0 {
        return WE_ERR_NULL;
    }
    guard(|| {
        let name = universe.system.body(index).name.as_bytes();
        let n = name.len().min(buf_len as usize);
        if n > 0 {
            unsafe { std::ptr::copy_nonoverlapping(name.as_ptr(), buf, n) };
        }
        i32::try_from(name.len()).unwrap_or(i32::MAX)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Handle(*mut WeUniverse);
    impl Drop for Handle {
        fn drop(&mut self) {
            unsafe { we_universe_free(self.0) }
        }
    }

    fn demo() -> Handle {
        let h = we_universe_new_demo();
        assert!(!h.is_null());
        Handle(h)
    }

    #[test]
    fn reports_abi_version() {
        assert_eq!(we_abi_version(), 1);
    }

    #[test]
    fn demo_universe_has_two_bodies() {
        let u = demo();
        assert_eq!(unsafe { we_universe_body_count(u.0) }, 2);
    }

    #[test]
    fn moon_position_is_60_km_from_origin() {
        let u = demo();
        let mut out = [0.0f64; 3];
        unsafe {
            assert_eq!(we_universe_set_time(u.0, 500.0), WE_OK);
            assert_eq!(we_universe_body_position(u.0, 1, out.as_mut_ptr()), WE_OK);
        }
        let d = (out[0] * out[0] + out[1] * out[1] + out[2] * out[2]).sqrt();
        assert!((d - 60_000.0).abs() < 1e-6, "distance {d}");
    }

    #[test]
    fn reports_body_names() {
        let u = demo();
        let mut buf = [0u8; 16];
        let len = unsafe { we_universe_body_name(u.0, 1, buf.as_mut_ptr(), buf.len() as u32) };
        assert_eq!(len, 4);
        assert_eq!(&buf[..4], b"moon");
    }

    #[test]
    fn truncates_names_to_buffer() {
        let u = demo();
        let mut buf = [0u8; 2];
        let len = unsafe { we_universe_body_name(u.0, 0, buf.as_mut_ptr(), 2) };
        assert_eq!(len, 4);
        assert_eq!(&buf, b"ho");
    }

    #[test]
    fn rejects_bad_index_and_nulls() {
        let u = demo();
        let mut out = [0.0f64; 3];
        unsafe {
            assert_eq!(
                we_universe_body_position(u.0, 9, out.as_mut_ptr()),
                WE_ERR_BAD_INDEX
            );
            assert_eq!(
                we_universe_body_position(std::ptr::null(), 0, out.as_mut_ptr()),
                WE_ERR_NULL
            );
            assert_eq!(
                we_universe_body_position(u.0, 0, std::ptr::null_mut()),
                WE_ERR_NULL
            );
            assert_eq!(we_universe_set_time(std::ptr::null_mut(), 1.0), WE_ERR_NULL);
            assert_eq!(we_universe_body_count(std::ptr::null()), 0);
        }
    }

    #[test]
    fn rejects_non_finite_time() {
        let u = demo();
        unsafe {
            assert_eq!(we_universe_set_time(u.0, f64::NAN), WE_ERR_INVALID);
            assert_eq!(we_universe_set_time(u.0, f64::INFINITY), WE_ERR_INVALID);
        }
    }

    #[test]
    fn position_changes_with_time() {
        let u = demo();
        let mut a = [0.0f64; 3];
        let mut b = [0.0f64; 3];
        unsafe {
            assert_eq!(we_universe_set_time(u.0, 0.0), WE_OK);
            assert_eq!(we_universe_body_position(u.0, 1, a.as_mut_ptr()), WE_OK);
            assert_eq!(we_universe_set_time(u.0, 500.0), WE_OK);
            assert_eq!(we_universe_body_position(u.0, 1, b.as_mut_ptr()), WE_OK);
        }
        let moved = ((a[0] - b[0]).powi(2) + (a[1] - b[1]).powi(2) + (a[2] - b[2]).powi(2)).sqrt();
        assert!(moved > 1_000.0, "moved {moved}");
    }

    #[test]
    fn writes_position_through_unaligned_pointer() {
        let u = demo();
        let mut raw = [0u8; 32];
        let out = raw.as_mut_ptr().wrapping_add(1) as *mut f64;
        unsafe {
            assert_eq!(we_universe_set_time(u.0, 500.0), WE_OK);
            assert_eq!(we_universe_body_position(u.0, 1, out), WE_OK);
        }
        let p: Vec<f64> = (0..3)
            .map(|i| unsafe { out.add(i).read_unaligned() })
            .collect();
        let d = (p[0] * p[0] + p[1] * p[1] + p[2] * p[2]).sqrt();
        assert!((d - 60_000.0).abs() < 1e-6, "distance {d}");
    }

    #[test]
    fn name_probe_and_errors() {
        let u = demo();
        unsafe {
            assert_eq!(we_universe_body_name(u.0, 1, std::ptr::null_mut(), 0), 4);
            assert_eq!(
                we_universe_body_name(u.0, 9, std::ptr::null_mut(), 0),
                WE_ERR_BAD_INDEX
            );
            assert_eq!(
                we_universe_body_name(std::ptr::null(), 0, std::ptr::null_mut(), 0),
                WE_ERR_NULL
            );
            assert_eq!(
                we_universe_body_name(u.0, 0, std::ptr::null_mut(), 8),
                WE_ERR_NULL
            );
        }
    }

    #[test]
    fn free_accepts_null() {
        unsafe { we_universe_free(std::ptr::null_mut()) };
    }

    #[test]
    fn guard_turns_panics_into_status() {
        assert_eq!(guard(|| -> i32 { panic!("boom") }), WE_ERR_PANIC);
    }
}
