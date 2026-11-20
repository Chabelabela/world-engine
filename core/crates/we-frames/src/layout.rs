use crate::cube_sphere::CubeSphere;
use crate::face::Face;

/// Where a planet's six faces sit in a Minecraft level, as a cross-shaped
/// cube net: the equatorial faces side by side along +x (so walking east
/// around the equator is ordinary walking until the wrap from E3 back to E0),
/// with `North` directly north of `E1` and `South` directly south of it.
///
/// ```text
///            [North]
///   [E0] [E1] [E2] [E3]
///            [South]
/// ```
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PlanetLayout {
    origin_x: i64,
    origin_z: i64,
    cells: u32,
}

impl PlanetLayout {
    /// Place `sphere`'s faces with E0's north-west corner at level block
    /// (`origin_x`, `origin_z`). The face size always comes from the sphere,
    /// so the layout and the projection can't disagree.
    pub fn new(origin_x: i64, origin_z: i64, sphere: CubeSphere) -> Self {
        Self {
            origin_x,
            origin_z,
            cells: sphere.cells(),
        }
    }

    /// Level block x of E0's north-west corner.
    pub fn origin_x(self) -> i64 {
        self.origin_x
    }

    /// Level block z of E0's north-west corner.
    pub fn origin_z(self) -> i64 {
        self.origin_z
    }

    /// Blocks per face edge.
    pub fn cells(self) -> u32 {
        self.cells
    }

    fn offset(self, face: Face) -> (i64, i64) {
        let n = i64::from(self.cells);
        match face {
            Face::E0 => (0, 0),
            Face::E1 => (n, 0),
            Face::E2 => (2 * n, 0),
            Face::E3 => (3 * n, 0),
            Face::North => (n, -n),
            Face::South => (n, n),
        }
    }

    /// Level coordinates of grid position (`x`, `z`) on `face`.
    pub fn to_level(self, face: Face, x: f64, z: f64) -> (f64, f64) {
        let (ox, oz) = self.offset(face);
        (
            (self.origin_x + ox) as f64 + x,
            (self.origin_z + oz) as f64 + z,
        )
    }

    /// The face and grid position at level coordinates (`lx`, `lz`), or
    /// `None` outside every face.
    pub fn from_level(self, lx: f64, lz: f64) -> Option<(Face, f64, f64)> {
        if !(lx.is_finite() && lz.is_finite()) {
            return None;
        }
        let n = f64::from(self.cells);
        let rx = lx - self.origin_x as f64;
        let rz = lz - self.origin_z as f64;
        let column = (rx / n).floor();
        let row = (rz / n).floor();
        let face = match (row as i64, column as i64) {
            (0, 0) => Face::E0,
            (0, 1) => Face::E1,
            (0, 2) => Face::E2,
            (0, 3) => Face::E3,
            (-1, 1) => Face::North,
            (1, 1) => Face::South,
            _ => return None,
        };
        Some((face, rx - column * n, rz - row * n))
    }

    /// The face and grid cell of level block (`bx`, `bz`), or `None`.
    pub fn block_to_cell(self, bx: i64, bz: i64) -> Option<(Face, u32, u32)> {
        let (face, x, z) = self.from_level(bx as f64 + 0.5, bz as f64 + 0.5)?;
        Some((face, x as u32, z as u32))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cube_sphere::{CubeSphere, SurfacePoint};
    use crate::face::QuarterTurns;

    const N: u32 = 16_384;

    fn layout() -> PlanetLayout {
        PlanetLayout::new(-40_000, 1_000_000, CubeSphere::new(N))
    }

    #[test]
    fn level_round_trip() {
        let l = layout();
        for face in Face::ALL {
            for (x, z) in [(0.0, 0.0), (0.5, 16_383.5), (9_000.25, 77.75)] {
                let (lx, lz) = l.to_level(face, x, z);
                assert_eq!(l.from_level(lx, lz), Some((face, x, z)));
            }
        }
    }

    #[test]
    fn outside_the_net_is_no_face() {
        let l = layout();
        let n = f64::from(N);
        let (ox, oz) = (l.origin_x as f64, l.origin_z as f64);
        for (rx, rz) in [
            (-1.0, 5.0),
            (4.0 * n + 1.0, 5.0),
            (5.0, -5.0),
            (2.5 * n, n + 5.0),
        ] {
            assert_eq!(l.from_level(ox + rx, oz + rz), None);
        }
    }

    #[test]
    fn non_finite_level_coordinates_are_no_face() {
        let l = layout();
        assert_eq!(l.from_level(f64::NAN, 0.0), None);
        assert_eq!(l.from_level(0.0, f64::INFINITY), None);
        assert_eq!(l.from_level(f64::NEG_INFINITY, f64::NAN), None);
    }

    #[test]
    fn blocks_map_to_cells() {
        let l = layout();
        let n = i64::from(N);
        assert_eq!(
            l.block_to_cell(l.origin_x, l.origin_z),
            Some((Face::E0, 0, 0))
        );
        assert_eq!(
            l.block_to_cell(l.origin_x + n + 3, l.origin_z - 1),
            Some((Face::North, 3, N - 1))
        );
        assert_eq!(l.block_to_cell(l.origin_x - 1, l.origin_z), None);
    }

    #[test]
    fn contiguous_edges_agree_with_fold() {
        // Wherever two faces touch in the level, stepping across that boundary
        // must land exactly where folding the cube would put you, unrotated.
        let l = layout();
        let s = CubeSphere::new(N);
        let n = f64::from(N);
        let crossings = [
            (Face::E0, n + 0.5, 4_321.5),
            (Face::E1, n + 0.5, 4_321.5),
            (Face::E2, n + 0.5, 4_321.5),
            (Face::E1, 4_321.5, -0.5),
            (Face::E1, 4_321.5, n + 0.5),
            (Face::North, 4_321.5, n + 0.5),
            (Face::South, 4_321.5, -0.5),
        ];
        for (face, x, z) in crossings {
            let (lx, lz) = l.to_level(face, x, z);
            let in_level = l.from_level(lx, lz).expect("neighbour face in the net");
            let folded = s
                .fold(SurfacePoint { face, x, y: 0.0, z })
                .expect("beyond one edge");
            assert_eq!(folded.turns, QuarterTurns::NONE, "{face:?}");
            assert_eq!(
                in_level,
                (folded.point.face, folded.point.x, folded.point.z),
                "{face:?} {x},{z}"
            );
        }
    }

    #[test]
    fn equator_wraps_by_pure_translation() {
        // East of E3 the net ends; the fold lands on E0 at the same z, x
        // shifted by four faces.
        let l = layout();
        let s = CubeSphere::new(N);
        let n = f64::from(N);
        let folded = s
            .fold(SurfacePoint {
                face: Face::E3,
                x: n + 2.5,
                y: 0.0,
                z: 777.5,
            })
            .unwrap();
        assert_eq!(folded.turns, QuarterTurns::NONE);
        let (lx, lz) = l.to_level(Face::E3, n + 2.5, 777.5);
        let (wx, wz) = l.to_level(folded.point.face, folded.point.x, folded.point.z);
        assert_eq!((lx - wx, lz - wz), (4.0 * n, 0.0));
    }
}
