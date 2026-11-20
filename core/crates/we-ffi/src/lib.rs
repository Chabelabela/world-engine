//! C ABI of the World Engine core.
//!
//! Every function here is mirrored by `NativeCore.java` in the mod. Rules:
//! - Bump `WE_ABI_VERSION` on any signature or semantic change.
//! - Never let a panic cross the boundary: wrap work in `guard`.
//! - Return `i32` status codes (`WE_OK` or a negative `WE_ERR_*`).

use std::panic::{AssertUnwindSafe, catch_unwind};
use we_orbit::CelestialSystem;

pub const WE_ABI_VERSION: u32 = 1;
pub const WE_OK: i32 = 0;
pub const WE_ERR_NULL: i32 = -1;
pub const WE_ERR_BAD_INDEX: i32 = -2;
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
///
/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_universe_set_time(universe: *mut WeUniverse, seconds: f64) -> i32 {
    let Some(universe) = (unsafe { universe.as_mut() }) else {
        return WE_ERR_NULL;
    };
    universe.time = seconds;
    WE_OK
}

/// Number of celestial bodies; 0 for a null handle.
///
/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_universe_body_count(universe: *const WeUniverse) -> u32 {
    unsafe { universe.as_ref() }.map_or(0, |u| u.system.len() as u32)
}

/// Write the position (metres, Y-up, relative to the root body) of body
/// `index` at the current time into `out_xyz[0..3]`.
///
/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`;
/// `out_xyz` must be null or valid for writing 3 doubles.
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
        let out = unsafe { std::slice::from_raw_parts_mut(out_xyz, 3) };
        out.copy_from_slice(&[p.x, p.y, p.z]);
        WE_OK
    })
}

/// Copy body `index`'s UTF-8 name (no terminator) into `buf`, truncated to
/// `buf_len` bytes. Returns the full name length in bytes, or a `WE_ERR_*`.
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
        name.len() as i32
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
}
