//! C ABI of the World Engine core.
//!
//! Every function here is mirrored by `NativeCore.java` in the mod. Rules:
//! - Bump `WE_ABI_VERSION` on any signature or semantic change.
//! - Never let a panic cross the boundary: wrap work in `guard`.
//! - Return `i32` status codes (`WE_OK` or a negative `WE_ERR_*`).
