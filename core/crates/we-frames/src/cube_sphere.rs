use crate::face::{Face, QuarterTurns};
use glam::{DMat3, DVec3};
use std::f64::consts::{FRAC_PI_4, PI};

/// A point in a face's surface frame: `x` and `z` in blocks from the face's
/// north-west corner (0..cells), `y` in metres above the datum sphere.
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
    /// Rotation that carries grid directions (velocities, yaw, block facing)
    /// from the old face to the new one.
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
    pub fn new(cells: u32) -> Self {
        assert!(
            cells >= 2 && cells % 2 == 0,
            "cells must be even and at least 2"
        );
        Self {
            cells,
            radius: 2.0 * f64::from(cells) / PI,
        }
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
        ((a * FRAC_PI_4).tan(), (b * FRAC_PI_4).tan())
    }

    /// Unit direction (body frame) of grid position (`x`, `z`) on `face`.
    pub fn direction(self, face: Face, x: f64, z: f64) -> DVec3 {
        let (ta, tb) = self.tangents(x, z);
        (face.normal() + ta * face.east() + tb * face.south()).normalize()
    }

    /// The face and grid position (`x`, `z`) a unit direction points at.
    pub fn grid_of(self, dir: DVec3) -> (Face, f64, f64) {
        let face = Face::of_direction(dir);
        let along = dir.dot(face.normal());
        let a = (dir.dot(face.east()) / along).atan() / FRAC_PI_4;
        let b = (dir.dot(face.south()) / along).atan() / FRAC_PI_4;
        (face, (a + 1.0) * self.half(), (b + 1.0) * self.half())
    }

    /// Body-frame position (metres from the planet centre) of a surface point.
    pub fn to_body(self, p: SurfacePoint) -> DVec3 {
        (self.radius + p.y) * self.direction(p.face, p.x, p.z)
    }

    /// The surface point at a body-frame position (inverse of [`Self::to_body`]).
    pub fn to_surface(self, position: DVec3) -> SurfacePoint {
        let r = position.length();
        let (face, x, z) = self.grid_of(position / r);
        SurfacePoint {
            face,
            x,
            y: r - self.radius,
            z,
        }
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
    pub fn velocity_to_body(self, p: SurfacePoint, grid_velocity: DVec3) -> DVec3 {
        let [jx, jy, jz] = self.jacobian(p);
        jx * grid_velocity.x + jy * grid_velocity.y + jz * grid_velocity.z
    }

    /// Surface-frame velocity at `p` of a body-frame velocity (inverse of
    /// [`Self::velocity_to_body`]).
    pub fn velocity_to_surface(self, p: SurfacePoint, body_velocity: DVec3) -> DVec3 {
        let [jx, jy, jz] = self.jacobian(p);
        DMat3::from_cols(jx, jy, jz).inverse() * body_velocity
    }

    /// If `p` lies beyond exactly one edge of its face (by less than one face
    /// width), the same spot on the neighbouring face, as if the cube's
    /// surface were unfolded flat across that edge. Whole cells map to whole
    /// cells, so blocks line up one-to-one across seams. `None` if `p` is
    /// inside its face (edges included) or beyond a corner.
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
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;
    use std::f64::consts::SQRT_2;

    const N: u32 = 16_384;

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
            let q = s.to_surface(s.to_body(p));
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
}
