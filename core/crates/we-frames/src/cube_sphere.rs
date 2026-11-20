use crate::face::{Face, QuarterTurns};
use glam::{DMat3, DVec3};
use std::f64::consts::{FRAC_PI_4, PI};

/// A point in a face's surface frame: `x` and `z` in blocks from the face's
/// north-west corner (0..cells), `y` in metres above the datum sphere.
///
/// `y` is altitude above the datum sphere, **not** Minecraft block y. Mapping
/// block y to altitude (where the sea-level block sits) is the level layout's
/// job (M1d).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SurfacePoint {
    pub face: Face,
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

/// A point carried across a face edge by [`CubeSphere::fold`].
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Folded {
    pub point: SurfacePoint,
    /// Rotation that carries grid directions from the old face to the new
    /// one. It is exact for grid-defined quantities: block facing, yaw and
    /// grid velocities in the surface frame.
    ///
    /// It is *not* a rotation on the sphere. Grid lines kink at seams
    /// everywhere except an edge's midpoint (by up to about 60° near corners).
    /// To carry a physical, body-frame velocity across, go through the body
    /// frame instead: [`CubeSphere::velocity_to_body`] at the old point, then
    /// [`CubeSphere::velocity_to_surface`] at the new one. For the same
    /// reason, a player's yaw is preserved relative to the grid, so it shifts
    /// slightly relative to the sphere at a seam.
    pub turns: QuarterTurns,
}

/// A planet whose six faces are `cells` x `cells` block grids, projected onto
/// a sphere with the equal-angle (tangent-adjusted) cube map: grid lines are
/// evenly spaced in angle, so cell area varies by at most a factor of √2.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CubeSphere {
    cells: u32,
    radius: f64,
}

impl CubeSphere {
    /// One block spans exactly one metre along the equator, so the datum
    /// radius is `2 * cells / π` (16,384 cells gives about 10,430 m).
    ///
    /// `cells` must be even so the face half-width is an integer and folds
    /// across edges map whole cells to whole cells with exact arithmetic.
    ///
    /// # Panics
    /// If `cells` is odd or less than 2. Use [`Self::try_new`] for untrusted
    /// input (config files, FFI).
    pub fn new(cells: u32) -> Self {
        Self::try_new(cells).expect("cells must be even and at least 2")
    }

    /// Like [`Self::new`], but `None` unless `cells` is even and at least 2.
    pub fn try_new(cells: u32) -> Option<Self> {
        (cells >= 2 && cells % 2 == 0).then(|| Self {
            cells,
            radius: 2.0 * f64::from(cells) / PI,
        })
    }

    pub fn cells(self) -> u32 {
        self.cells
    }

    /// Radius of the datum sphere (altitude 0), metres.
    pub fn radius(self) -> f64 {
        self.radius
    }

    fn half(self) -> f64 {
        f64::from(self.cells) / 2.0
    }

    /// Grid position → (tan of the angle along east, along south).
    fn tangents(self, x: f64, z: f64) -> (f64, f64) {
        let a = x / self.half() - 1.0;
        let b = z / self.half() - 1.0;
        // libm (pure Rust), not f64::tan: platform maths libraries differ in
        // the last bit, and terrain must be identical on every OS.
        (libm::tan(a * FRAC_PI_4), libm::tan(b * FRAC_PI_4))
    }

    /// Unit direction (body frame) of grid position (`x`, `z`) on `face`.
    pub fn direction(self, face: Face, x: f64, z: f64) -> DVec3 {
        let (ta, tb) = self.tangents(x, z);
        (face.normal() + ta * face.east() + tb * face.south()).normalize()
    }

    /// The face and grid position (`x`, `z`) a direction points at.
    ///
    /// Precondition: `dir` is finite and non-zero (it need not be unit
    /// length); use [`Self::to_surface`] for unvalidated input. On a seam the
    /// result may have `x` or `z` exactly equal to `cells`; see
    /// [`Self::cell_of`].
    pub fn grid_of(self, dir: DVec3) -> (Face, f64, f64) {
        let face = Face::of_direction(dir);
        let along = dir.dot(face.normal());
        let a = libm::atan(dir.dot(face.east()) / along) / FRAC_PI_4;
        let b = libm::atan(dir.dot(face.south()) / along) / FRAC_PI_4;
        (face, (a + 1.0) * self.half(), (b + 1.0) * self.half())
    }

    /// Body-frame position (metres from the planet centre) of a surface point.
    pub fn to_body(self, p: SurfacePoint) -> DVec3 {
        (self.radius + p.y) * self.direction(p.face, p.x, p.z)
    }

    /// The surface point at a body-frame position (inverse of [`Self::to_body`]).
    /// `None` if the position is non-finite or has zero length.
    pub fn to_surface(self, position: DVec3) -> Option<SurfacePoint> {
        let r = position.length();
        if !(r.is_finite() && r > 0.0) {
            return None;
        }
        let (face, x, z) = self.grid_of(position / r);
        Some(SurfacePoint {
            face,
            x,
            y: r - self.radius,
            z,
        })
    }

    /// The grid cell (`i`, `j`) containing `p`: the floor of `x` and `z`,
    /// clamped to `[0, cells - 1]`.
    ///
    /// Cells are half-open: cell `i` covers `i <= x < i + 1`, and the face
    /// covers `[0, cells)`. A point exactly on the far edge (`x == cells`,
    /// which [`Self::grid_of`] can return on a seam) belongs to the last cell.
    /// Points beyond an edge must be folded first ([`Self::fold`]).
    pub fn cell_of(self, p: SurfacePoint) -> (i64, i64) {
        let n = f64::from(self.cells);
        // Small tolerance: grid_of may overshoot a seam by rounding error.
        debug_assert!(
            (-1e-6..=n + 1e-6).contains(&p.x) && (-1e-6..=n + 1e-6).contains(&p.z),
            "point outside its face: {p:?}"
        );
        let last = i64::from(self.cells) - 1;
        let cell = |v: f64| (v.floor() as i64).clamp(0, last);
        (cell(p.x), cell(p.z))
    }

    /// Columns ∂P/∂x, ∂P/∂y, ∂P/∂z of the Jacobian of [`Self::to_body`].
    pub fn jacobian(self, p: SurfacePoint) -> [DVec3; 3] {
        let (ta, tb) = self.tangents(p.x, p.z);
        let (n, e, s) = (p.face.normal(), p.face.east(), p.face.south());
        let c = n + ta * e + tb * s;
        let len = c.length();
        let d = c / len;
        let angle_per_block = FRAC_PI_4 / self.half();
        let dc_dx = e * (1.0 + ta * ta) * angle_per_block;
        let dc_dz = s * (1.0 + tb * tb) * angle_per_block;
        // d(c/|c|) = (I - d dᵀ) dc / |c|
        let scale = (self.radius + p.y) / len;
        let tangential = |v: DVec3| (v - d * d.dot(v)) * scale;
        [tangential(dc_dx), d, tangential(dc_dz)]
    }

    /// Body-frame velocity of a point moving at `grid_velocity`
    /// (dx/dt, dy/dt, dz/dt) in the surface frame.
    ///
    /// Grid velocity is in blocks/s horizontally and m/s vertically (`y` is
    /// altitude, not block y). One block along x or z spans `(R + y) / R`
    /// metres times the local projection scale (0.71 to 1.0). At 1.5 km
    /// altitude a block is about 1.14 m, so a 150 m/s ship moves about
    /// 131 blocks/s.
    pub fn velocity_to_body(self, p: SurfacePoint, grid_velocity: DVec3) -> DVec3 {
        let [jx, jy, jz] = self.jacobian(p);
        jx * grid_velocity.x + jy * grid_velocity.y + jz * grid_velocity.z
    }

    /// Surface-frame velocity at `p` of a body-frame velocity (inverse of
    /// [`Self::velocity_to_body`]).
    ///
    /// The result is in blocks/s horizontally and m/s vertically (`y` is
    /// altitude, not block y). One block spans `(R + y) / R` metres times the
    /// local projection scale (0.71 to 1.0): at 1.5 km altitude a block is
    /// about 1.14 m, so 150 m/s is about 131 blocks/s.
    pub fn velocity_to_surface(self, p: SurfacePoint, body_velocity: DVec3) -> DVec3 {
        let [jx, jy, jz] = self.jacobian(p);
        DMat3::from_cols(jx, jy, jz).inverse() * body_velocity
    }

    /// If `p` lies beyond exactly one edge of its face (by less than one face
    /// width), the same spot on the neighbouring face, as if the cube's
    /// surface were unfolded flat across that edge. Whole cells map to whole
    /// cells, so blocks line up one-to-one across seams.
    ///
    /// Valid range: no more than one face width (`cells` blocks) beyond the
    /// edge. `None` if `p` is inside its face (edges included), beyond a
    /// corner, further than one face width out, or not finite. What happens
    /// beyond a corner (the missing quadrant of the unfolded cube) is defined
    /// by M1e. See [`Folded::turns`] for what the rotation does and does not
    /// preserve.
    pub fn fold(self, p: SurfacePoint) -> Option<Folded> {
        let h = self.half();
        let (face, x, z, turns) = p.face.fold_centered(p.x - h, p.z - h, h)?;
        Some(Folded {
            point: SurfacePoint {
                face,
                x: x + h,
                y: p.y,
                z: z + h,
            },
            turns,
        })
    }

    /// Fold integer cell (`i`, `j`) of `face`, which lies beyond one edge by
    /// 1..=`cells` cells, onto the neighbouring face. Returns the neighbour,
    /// the cell there and the rotation for grid directions (block facing).
    ///
    /// The fold goes through the cell centre (`i + 0.5`, `j + 0.5`); the
    /// half-integer f64 arithmetic is exact. `None` if the cell is inside the
    /// face, beyond a corner, or more than `cells` cells out. This is the
    /// primitive for mirroring blocks across seams (M1e).
    pub fn fold_cell(self, face: Face, i: i64, j: i64) -> Option<(Face, i64, i64, QuarterTurns)> {
        let n = i64::from(self.cells);
        // Reject far-out cells before the lossy i64 -> f64 conversion.
        if i.unsigned_abs().max(j.unsigned_abs()) > 3 * n.unsigned_abs() {
            return None;
        }
        let centre = SurfacePoint {
            face,
            x: i as f64 + 0.5,
            y: 0.0,
            z: j as f64 + 0.5,
        };
        let Folded { point, turns } = self.fold(centre)?;
        Some((
            point.face,
            point.x.floor() as i64,
            point.z.floor() as i64,
            turns,
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::face::Edge;
    use proptest::prelude::*;
    use std::f64::consts::SQRT_2;

    const N: u32 = 16_384;
    const PROJECTION_GOLDEN: u64 = 0x88c6_ce59_9442_24d1;

    fn sphere() -> CubeSphere {
        CubeSphere::new(N)
    }

    fn face_strategy() -> impl Strategy<Value = Face> {
        prop::sample::select(Face::ALL.to_vec())
    }

    fn point(face: Face, x: f64, y: f64, z: f64) -> SurfacePoint {
        SurfacePoint { face, x, y, z }
    }

    #[test]
    fn radius_makes_equator_blocks_one_metre() {
        let s = sphere();
        assert!((s.radius() - 10_430.378_350_470_453).abs() < 1e-9);
        let row = f64::from(N) / 2.0;
        for x in [0.5, 1_000.5, 8_191.5, 16_382.5] {
            let a = s.direction(Face::E0, x, row);
            let b = s.direction(Face::E0, x + 1.0, row);
            let arc = a.angle_between(b) * s.radius();
            assert!((arc - 1.0).abs() < 1e-6, "x={x} arc={arc}");
        }
    }

    #[test]
    fn face_centres_point_along_normals() {
        let s = sphere();
        let c = f64::from(N) / 2.0;
        for face in Face::ALL {
            assert!((s.direction(face, c, c) - face.normal()).length() < 1e-15);
        }
    }

    proptest! {
        #[test]
        fn grid_direction_round_trip(
            face in face_strategy(),
            x in 0.01f64..16_383.99,
            z in 0.01f64..16_383.99,
        ) {
            let s = sphere();
            let (f, gx, gz) = s.grid_of(s.direction(face, x, z));
            prop_assert_eq!(f, face);
            prop_assert!((gx - x).abs() < 1e-7 && (gz - z).abs() < 1e-7);
        }

        #[test]
        fn body_position_round_trip(
            face in face_strategy(),
            x in 0.01f64..16_383.99,
            y in -2_000.0f64..60_000.0,
            z in 0.01f64..16_383.99,
        ) {
            let s = sphere();
            let p = point(face, x, y, z);
            let q = s.to_surface(s.to_body(p)).unwrap();
            prop_assert_eq!(q.face, face);
            prop_assert!((s.to_body(q) - s.to_body(p)).length() < 1e-6);
        }

        #[test]
        fn velocity_round_trip(
            face in face_strategy(),
            x in 0.01f64..16_383.99,
            y in -100.0f64..3_000.0,
            z in 0.01f64..16_383.99,
            vx in -300.0f64..300.0,
            vy in -300.0f64..300.0,
            vz in -300.0f64..300.0,
        ) {
            let s = sphere();
            let p = point(face, x, y, z);
            let v = DVec3::new(vx, vy, vz);
            let back = s.velocity_to_surface(p, s.velocity_to_body(p, v));
            prop_assert!((back - v).length() < 1e-9);
        }
    }

    #[test]
    fn jacobian_matches_finite_differences() {
        let s = sphere();
        for (face, x, y, z) in [
            (Face::E0, 8_192.0, 0.0, 8_192.0),
            (Face::E2, 100.0, 250.0, 16_000.0),
            (Face::North, 15_000.0, 1_500.0, 300.0),
            (Face::South, 4_000.5, -50.0, 12_345.25),
        ] {
            let p = point(face, x, y, z);
            let jac = s.jacobian(p);
            let h = 1e-3;
            let steps = [DVec3::X, DVec3::Y, DVec3::Z];
            for (column, step) in jac.iter().zip(steps) {
                let plus = point(face, x + h * step.x, y + h * step.y, z + h * step.z);
                let minus = point(face, x - h * step.x, y - h * step.y, z - h * step.z);
                let numeric = (s.to_body(plus) - s.to_body(minus)) / (2.0 * h);
                assert!(
                    (numeric - *column).length() < 1e-6 * column.length(),
                    "{face:?} {step:?}: {numeric:?} vs {column:?}"
                );
            }
        }
    }

    #[test]
    fn distortion_stays_within_equal_angle_bounds() {
        // Cell area relative to the face centre stays in [1/√2, 1]; a cell's
        // aspect ratio stays within √2; grid lines meet at 60–120 degrees.
        let s = sphere();
        let c = f64::from(N) / 2.0;
        let [cx, _, cz] = s.jacobian(point(Face::E0, c, 0.0, c));
        let centre_area = cx.cross(cz).length();
        let steps = 64;
        for i in 0..=steps {
            for j in 0..=steps {
                let x = f64::from(N) * f64::from(i) / f64::from(steps);
                let z = f64::from(N) * f64::from(j) / f64::from(steps);
                let [jx, _, jz] = s.jacobian(point(Face::E0, x, 0.0, z));
                let area = jx.cross(jz).length() / centre_area;
                assert!(
                    (1.0 / SQRT_2 - 1e-9..=1.0 + 1e-9).contains(&area),
                    "area {area} at {x},{z}"
                );
                let aspect = jx.length() / jz.length();
                assert!(
                    (1.0 / SQRT_2 - 1e-9..=SQRT_2 + 1e-9).contains(&aspect),
                    "aspect {aspect}"
                );
                let angle = jx.angle_between(jz).to_degrees();
                assert!(
                    (60.0 - 1e-6..=120.0 + 1e-6).contains(&angle),
                    "angle {angle}"
                );
            }
        }
    }

    #[test]
    fn fold_maps_cell_centres_to_cell_centres() {
        let s = sphere();
        let n = f64::from(N);
        for face in Face::ALL {
            for (x, z) in [
                (n + 0.5, 10.5),
                (-3.5, 9_000.5),
                (77.5, n + 2.5),
                (16_000.5, -0.5),
            ] {
                let folded = s.fold(point(face, x, 12.0, z)).expect("beyond one edge");
                let q = folded.point;
                assert_eq!(q.y, 12.0);
                assert_eq!((q.x - 0.5).fract(), 0.0, "{face:?} {x},{z} -> {q:?}");
                assert_eq!((q.z - 0.5).fract(), 0.0, "{face:?} {x},{z} -> {q:?}");
                assert!((0.0..n).contains(&q.x) && (0.0..n).contains(&q.z));
            }
        }
    }

    #[test]
    fn fold_continues_smoothly_on_the_sphere() {
        // The first cell beyond an edge lands next to the last cell inside it.
        let s = sphere();
        let n = f64::from(N);
        for face in Face::ALL {
            for along in [0.5, 1_234.5, 8_191.5, n - 0.5] {
                for (inside, beyond) in [
                    ((n - 0.5, along), (n + 0.5, along)),
                    ((0.5, along), (-0.5, along)),
                    ((along, n - 0.5), (along, n + 0.5)),
                    ((along, 0.5), (along, -0.5)),
                ] {
                    let a = s.direction(face, inside.0, inside.1);
                    let q = s.fold(point(face, beyond.0, 0.0, beyond.1)).unwrap().point;
                    let b = s.direction(q.face, q.x, q.z);
                    let gap = a.angle_between(b) * s.radius();
                    assert!(
                        (0.5..=1.5).contains(&gap),
                        "{face:?} {beyond:?}: gap {gap} m"
                    );
                }
            }
        }
    }

    #[test]
    fn folding_back_returns_to_the_start() {
        let s = sphere();
        let n = f64::from(N);
        for face in Face::ALL {
            for (x, z, out) in [
                (n + 7.5, 100.5, (1.0, 0.0)),
                (-7.5, 100.5, (-1.0, 0.0)),
                (100.5, n + 7.5, (0.0, 1.0)),
                (100.5, -7.5, (0.0, -1.0)),
            ] {
                let there = s.fold(point(face, x, 0.0, z)).unwrap();
                // Continue in the same direction, now 15 blocks back across the seam.
                let (dx, dz) = there.turns.apply(out.0, out.1);
                let back_beyond = point(
                    there.point.face,
                    there.point.x - 15.0 * dx,
                    0.0,
                    there.point.z - 15.0 * dz,
                );
                let back = s.fold(back_beyond).expect("beyond the seam again");
                assert_eq!(back.point.face, face);
                assert!((back.point.x - (x - 15.0 * out.0)).abs() < 1e-9);
                assert!((back.point.z - (z - 15.0 * out.1)).abs() < 1e-9);
                assert_eq!(back.turns, there.turns.inverse());
            }
        }
    }

    const EDGES: [Edge; 4] = [Edge::West, Edge::East, Edge::North, Edge::South];

    #[test]
    fn to_surface_rejects_bad_positions() {
        let s = sphere();
        assert!(s.to_surface(DVec3::ZERO).is_none());
        assert!(s.to_surface(DVec3::NAN).is_none());
        assert!(s.to_surface(DVec3::new(f64::INFINITY, 0.0, 0.0)).is_none());
        assert!(
            s.to_surface(DVec3::new(1.0, f64::NEG_INFINITY, 1.0))
                .is_none()
        );
        assert!(s.to_surface(DVec3::new(1e200, 1e200, 0.0)).is_none());
        assert!(s.to_surface(DVec3::X * s.radius()).is_some());
    }

    #[test]
    fn try_new_validates_cell_counts() {
        for bad in [0, 1, 3, 16_383] {
            assert!(CubeSphere::try_new(bad).is_none(), "{bad}");
        }
        for good in [2, 16_384] {
            assert_eq!(CubeSphere::try_new(good).unwrap().cells(), good);
        }
    }

    #[test]
    fn cell_of_is_half_open_with_the_far_edge_in_the_last_cell() {
        let s = sphere();
        let n = f64::from(N);
        let last = i64::from(N) - 1;
        assert_eq!(s.cell_of(point(Face::E0, 0.0, 0.0, 0.0)), (0, 0));
        assert_eq!(s.cell_of(point(Face::E0, n, 0.0, n)), (last, last));
        assert_eq!(s.cell_of(point(Face::E0, 1.0, 0.0, 2.999)), (1, 2));
        assert_eq!(s.cell_of(point(Face::E0, n - 1e-9, 0.0, 0.5)), (last, 0));
    }

    /// FNV-1a over the exact bits of many projections. A failure on one
    /// platform only means its maths library differs: the projection must
    /// use `libm` (pure Rust), never `f64::tan` / `f64::atan`.
    #[test]
    fn projection_is_bit_identical_on_every_platform() {
        let s = sphere();
        let mut hash = 0xcbf2_9ce4_8422_2325_u64;
        for face in Face::ALL {
            for i in 0..32 {
                for j in 0..32 {
                    let x = f64::from(i) * 512.0 + 0.5 + f64::from(j) * 0.013;
                    let z = f64::from(j) * 512.0 + 0.5 + f64::from(i) * 0.029;
                    let d = s.direction(face, x, z);
                    let (_, gx, gz) = s.grid_of(d);
                    for v in [d.x, d.y, d.z, gx, gz] {
                        for byte in v.to_bits().to_le_bytes() {
                            hash ^= u64::from(byte);
                            hash = hash.wrapping_mul(0x0100_0000_01b3);
                        }
                    }
                }
            }
        }
        assert_eq!(hash, PROJECTION_GOLDEN, "got {hash:#018x}");
    }

    #[test]
    fn fold_cell_agrees_with_fold_on_cell_centres() {
        let s = sphere();
        let n = i64::from(N);
        for face in Face::ALL {
            for depth in [1, 7, n] {
                for along in [0, 5, n / 2, n - 1] {
                    for (i, j) in [
                        (n + depth - 1, along),
                        (-depth, along),
                        (along, n + depth - 1),
                        (along, -depth),
                    ] {
                        let (f, ci, cj, turns) = s
                            .fold_cell(face, i, j)
                            .unwrap_or_else(|| panic!("{face:?} {i},{j}"));
                        let folded = s
                            .fold(point(face, i as f64 + 0.5, 0.0, j as f64 + 0.5))
                            .unwrap();
                        assert_eq!(f, folded.point.face);
                        assert_eq!(turns, folded.turns);
                        assert_eq!(ci as f64 + 0.5, folded.point.x, "{face:?} {i},{j}");
                        assert_eq!(cj as f64 + 0.5, folded.point.z, "{face:?} {i},{j}");
                        assert!((0..n).contains(&ci) && (0..n).contains(&cj));
                    }
                }
            }
        }
    }

    #[test]
    fn fold_cell_rejects_inside_corners_and_too_far() {
        let s = sphere();
        let n = i64::from(N);
        for face in Face::ALL {
            assert!(s.fold_cell(face, 0, 0).is_none());
            assert!(s.fold_cell(face, n - 1, n - 1).is_none());
            assert!(s.fold_cell(face, -1, -1).is_none(), "corner");
            assert!(s.fold_cell(face, n, n).is_none(), "corner");
            assert!(s.fold_cell(face, -1, n + 3).is_none(), "corner");
            assert!(s.fold_cell(face, 2 * n, 5).is_none(), "N + 1 cells out");
            assert!(s.fold_cell(face, 5, -n - 1).is_none(), "N + 1 cells out");
            assert!(s.fold_cell(face, 2 * n - 1, 5).is_some(), "N cells out");
            assert!(s.fold_cell(face, 5, -n).is_some(), "N cells out");
            assert!(s.fold_cell(face, i64::MIN, 0).is_none());
            assert!(s.fold_cell(face, 0, i64::MAX).is_none());
        }
    }

    #[test]
    fn folded_grid_directions_match_the_old_faces_tangents() {
        // For every edge, rotating grid directions by `turns` and pushing them
        // through the new face's Jacobian must agree with the old face's
        // Jacobian columns at the same continuous point.
        //
        // Along the edge the agreement is exact. Across it the equal-angle
        // grid is not orthogonal away from the edge midpoint, so the grid
        // line bends at the seam: it is the mirror image (about the plane
        // bisecting the two faces) of the old line, i.e. its component along
        // the edge flips sign. The test checks exactly that, and that at the
        // edge midpoint, where the grid is orthogonal, the line is straight.
        let s = sphere();
        let n = f64::from(N);
        let eps = 1e-6;
        let cosine = |a: DVec3, b: DVec3| a.normalize().dot(b.normalize());
        for face in Face::ALL {
            for edge in EDGES {
                let across_is_x = matches!(edge, Edge::West | Edge::East);
                for along in [1.0, 100.0, 1_234.5, n / 2.0, n - 100.0] {
                    let (x, z) = match edge {
                        Edge::West => (-eps, along),
                        Edge::East => (n + eps, along),
                        Edge::North => (along, -eps),
                        Edge::South => (along, n + eps),
                    };
                    let old = point(face, x, 0.0, z);
                    let [jx_old, _, jz_old] = s.jacobian(old);
                    let folded = s.fold(old).expect("just beyond the edge");
                    let [jx_new, _, jz_new] = s.jacobian(folded.point);
                    let carried = |unit: (f64, f64)| {
                        let (dx, dz) = folded.turns.apply(unit.0, unit.1);
                        jx_new * dx + jz_new * dz
                    };
                    let (across_old, along_old, across_new, along_new) = if across_is_x {
                        (jx_old, jz_old, carried((1.0, 0.0)), carried((0.0, 1.0)))
                    } else {
                        (jz_old, jx_old, carried((0.0, 1.0)), carried((1.0, 0.0)))
                    };
                    let ctx = format!("{face:?} {edge:?} at {along}");
                    assert!(cosine(along_new, along_old) > 1.0 - 1e-6, "along, {ctx}");
                    let edge_dir = along_old.normalize();
                    let mirrored = across_old - 2.0 * across_old.dot(edge_dir) * edge_dir;
                    assert!(cosine(across_new, mirrored) > 1.0 - 1e-6, "across, {ctx}");
                    assert!(cosine(across_new, across_old) > 0.0, "forward, {ctx}");
                    if along == n / 2.0 {
                        assert!(cosine(across_new, across_old) > 1.0 - 1e-6, "mid, {ctx}");
                    }
                }
            }
        }
    }

    proptest! {
        #[test]
        fn round_trip_near_face_edges(
            face in face_strategy(),
            near in 0.0f64..0.5,
            far_side in any::<bool>(),
            along in 0.0f64..16_384.0,
            swap in any::<bool>(),
            y in -100.0f64..3_000.0,
        ) {
            let s = sphere();
            let n = f64::from(N);
            let edge_coord = if far_side { n - near } else { near };
            let (x, z) = if swap { (along, edge_coord) } else { (edge_coord, along) };
            let p = point(face, x, y, z);
            // The face may differ (seams are ambiguous); the position may not.
            let q = s.to_surface(s.to_body(p)).unwrap();
            prop_assert!((s.to_body(q) - s.to_body(p)).length() < 1e-6);
        }
    }
}
