use glam::DVec3;

/// One of the six faces of a cube-sphere planet. The equatorial faces
/// `E0..E3` run eastward (prograde), starting at +X. `North` is +Y (the spin
/// axis) and `South` is −Y.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Face {
    E0,
    E1,
    E2,
    E3,
    North,
    South,
}

/// A side of a face's block grid. Grid x grows east and grid z grows south,
/// so `West` is x = 0 and `North` is z = 0.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Edge {
    West,
    East,
    North,
    South,
}

/// A rotation of the block grid by whole quarter turns, clockwise seen from
/// above (east turns into south), like Minecraft's `Rotation.CLOCKWISE_90`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct QuarterTurns(pub u8);

impl QuarterTurns {
    pub const NONE: Self = Self(0);

    /// Rotate a grid direction (dx east, dz south).
    pub fn apply(self, dx: f64, dz: f64) -> (f64, f64) {
        match self.0 % 4 {
            0 => (dx, dz),
            1 => (-dz, dx),
            2 => (-dx, -dz),
            _ => (dz, -dx),
        }
    }

    /// Rotate a Minecraft yaw in degrees (0 = south, 90 = west, -90 = east).
    pub fn rotate_yaw(self, yaw: f32) -> f32 {
        yaw + 90.0 * f32::from(self.0 % 4)
    }

    pub fn inverse(self) -> Self {
        Self((4 - self.0 % 4) % 4)
    }

    /// The rotation that turns grid east (1, 0) into `image`, an axis-aligned
    /// unit vector.
    fn from_east_image(image: (f64, f64)) -> Self {
        match (image.0.round() as i8, image.1.round() as i8) {
            (1, 0) => Self(0),
            (0, 1) => Self(1),
            (-1, 0) => Self(2),
            (0, -1) => Self(3),
            other => unreachable!("not an axis direction: {other:?}"),
        }
    }
}

impl Face {
    pub const ALL: [Face; 6] = [
        Face::E0,
        Face::E1,
        Face::E2,
        Face::E3,
        Face::North,
        Face::South,
    ];

    /// Outward normal in the planet body frame.
    pub fn normal(self) -> DVec3 {
        match self {
            Face::E0 => DVec3::X,
            Face::E1 => DVec3::NEG_Z,
            Face::E2 => DVec3::NEG_X,
            Face::E3 => DVec3::Z,
            Face::North => DVec3::Y,
            Face::South => DVec3::NEG_Y,
        }
    }

    /// Direction of increasing grid x (east on the equatorial faces).
    pub fn east(self) -> DVec3 {
        match self {
            Face::E0 => DVec3::NEG_Z,
            Face::E1 => DVec3::NEG_X,
            Face::E2 => DVec3::Z,
            Face::E3 => DVec3::X,
            Face::North | Face::South => DVec3::NEG_X,
        }
    }

    /// Direction of increasing grid z (south on the equatorial faces).
    pub fn south(self) -> DVec3 {
        match self {
            Face::E0 | Face::E1 | Face::E2 | Face::E3 => DVec3::NEG_Y,
            Face::North => DVec3::NEG_Z,
            Face::South => DVec3::Z,
        }
    }

    /// The face whose normal is the axis `normal` (exactly ±X, ±Y or ±Z).
    pub fn from_normal(normal: DVec3) -> Face {
        Face::ALL
            .into_iter()
            .find(|f| f.normal().distance_squared(normal) < 1e-12)
            .unwrap_or_else(|| panic!("not a face normal: {normal:?}"))
    }

    /// The face a direction points through: the axis with the largest
    /// absolute component.
    pub fn of_direction(dir: DVec3) -> Face {
        let a = dir.abs();
        let axis = if a.x >= a.y && a.x >= a.z {
            DVec3::X * dir.x.signum()
        } else if a.y >= a.z {
            DVec3::Y * dir.y.signum()
        } else {
            DVec3::Z * dir.z.signum()
        };
        Face::from_normal(axis)
    }

    /// The face across `edge`, and the edge of that face you arrive through.
    pub fn neighbor(self, edge: Edge) -> (Face, Edge) {
        let (px, pz) = match edge {
            Edge::West => (-1.5, 0.0),
            Edge::East => (1.5, 0.0),
            Edge::North => (0.0, -1.5),
            Edge::South => (0.0, 1.5),
        };
        let (face, x, z, _) = self.fold_centered(px, pz, 1.0).expect("beyond one edge");
        let arrived = if x <= -0.5 {
            Edge::West
        } else if x >= 0.5 {
            Edge::East
        } else if z <= -0.5 {
            Edge::North
        } else {
            Edge::South
        };
        (face, arrived)
    }

    /// Fold a point given in centred grid coordinates (`px`, `pz` measured
    /// from the face centre, face half-width `half`) across the single edge it
    /// lies beyond, onto the neighbouring face, as if the cube's surface were
    /// unfolded flat across that edge. Returns the neighbour, the centred
    /// coordinates there, and the rotation that carries grid directions across.
    /// `None` if the point is inside the face or beyond a corner.
    pub(crate) fn fold_centered(
        self,
        px: f64,
        pz: f64,
        half: f64,
    ) -> Option<(Face, f64, f64, QuarterTurns)> {
        let beyond_x = px.abs() > half;
        let beyond_z = pz.abs() > half;
        if beyond_x == beyond_z {
            return None;
        }
        let (n, e, s) = (self.normal(), self.east(), self.south());
        // Point on the surface of a cube of half-size `half`, folded over the
        // crossed edge, plus the image of grid east (d/dpx) on that surface.
        let (point, next_normal, east_image) = if beyond_x {
            let sign = px.signum();
            (
                (2.0 * half - px.abs()) * n + sign * half * e + pz * s,
                sign * e,
                -sign * n,
            )
        } else {
            let sign = pz.signum();
            (
                (2.0 * half - pz.abs()) * n + px * e + sign * half * s,
                sign * s,
                e,
            )
        };
        let next = Face::from_normal(next_normal);
        let (ne, ns) = (next.east(), next.south());
        let turns = QuarterTurns::from_east_image((east_image.dot(ne), east_image.dot(ns)));
        Some((next, point.dot(ne), point.dot(ns), turns))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const EDGES: [Edge; 4] = [Edge::West, Edge::East, Edge::North, Edge::South];

    #[test]
    fn bases_are_orthonormal_and_oriented_like_minecraft() {
        for face in Face::ALL {
            let (n, e, s) = (face.normal(), face.east(), face.south());
            for v in [n, e, s] {
                assert!((v.length() - 1.0).abs() < 1e-15);
            }
            assert!(n.dot(e).abs() < 1e-15 && n.dot(s).abs() < 1e-15 && e.dot(s).abs() < 1e-15);
            // Minecraft: X (east) x Y (up) = Z (south).
            assert!((e.cross(n) - s).length() < 1e-15, "{face:?}");
        }
    }

    #[test]
    fn equatorial_east_is_prograde() {
        // The planet spins about +Y, so surface velocity points along Y x r.
        for face in [Face::E0, Face::E1, Face::E2, Face::E3] {
            assert_eq!(DVec3::Y.cross(face.normal()), face.east(), "{face:?}");
        }
    }

    #[test]
    fn faces_are_found_from_their_normals_and_directions() {
        for face in Face::ALL {
            assert_eq!(Face::from_normal(face.normal()), face);
            let tilted = face.normal() + 0.3 * face.east() - 0.2 * face.south();
            assert_eq!(Face::of_direction(tilted), face);
        }
    }

    #[test]
    fn neighbors_are_symmetric_and_distinct() {
        for face in Face::ALL {
            let mut seen = Vec::new();
            for edge in EDGES {
                let (next, arrived) = face.neighbor(edge);
                assert_eq!(next.neighbor(arrived), (face, edge), "{face:?} {edge:?}");
                assert_ne!(next, face);
                assert_ne!(
                    next.normal(),
                    -face.normal(),
                    "opposite face is never adjacent"
                );
                seen.push(next);
            }
            seen.sort_by_key(|f| *f as u8);
            seen.dedup();
            assert_eq!(seen.len(), 4, "{face:?} must touch four different faces");
        }
    }

    #[test]
    fn layout_edges_continue_straight_on() {
        // The edges that are contiguous in PlanetLayout's cross-shaped net.
        let straight = [
            (Face::E0, Edge::East, Face::E1, Edge::West),
            (Face::E1, Edge::East, Face::E2, Edge::West),
            (Face::E2, Edge::East, Face::E3, Edge::West),
            (Face::E3, Edge::East, Face::E0, Edge::West),
            (Face::E1, Edge::North, Face::North, Edge::South),
            (Face::E1, Edge::South, Face::South, Edge::North),
        ];
        for (face, edge, next, arrived) in straight {
            assert_eq!(face.neighbor(edge), (next, arrived));
            let (px, pz) = match edge {
                Edge::East => (1.25, 0.4),
                Edge::North => (0.4, -1.25),
                Edge::South => (0.4, 1.25),
                Edge::West => unreachable!(),
            };
            let (_, _, _, turns) = face.fold_centered(px, pz, 1.0).unwrap();
            assert_eq!(turns, QuarterTurns::NONE, "{face:?} {edge:?}");
        }
    }

    #[test]
    fn fold_is_none_inside_and_beyond_corners() {
        assert!(Face::E0.fold_centered(0.3, -0.9, 1.0).is_none());
        assert!(
            Face::E0.fold_centered(1.0, 1.0, 1.0).is_none(),
            "the edge itself is inside"
        );
        assert!(Face::E0.fold_centered(1.2, 1.3, 1.0).is_none());
    }

    #[test]
    fn quarter_turns_compose_and_invert() {
        for k in 0..4 {
            let q = QuarterTurns(k);
            let (x, z) = q.apply(0.3, -0.7);
            let (bx, bz) = q.inverse().apply(x, z);
            assert!((bx - 0.3).abs() < 1e-15 && (bz + 0.7).abs() < 1e-15);
        }
        assert_eq!(
            QuarterTurns(1).apply(1.0, 0.0),
            (-0.0, 1.0),
            "east turns south"
        );
        assert_eq!(
            QuarterTurns(1).rotate_yaw(-90.0),
            0.0,
            "facing east turns to facing south"
        );
    }
}
