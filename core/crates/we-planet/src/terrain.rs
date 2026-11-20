use crate::hash::layer_seed;
use crate::noise::fbm;
use glam::DVec3;
use we_frames::CubeSphere;

/// What kind of world a planet is. The discriminants cross the C ABI, so
/// they are frozen: changing them requires bumping `WE_ABI_VERSION`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum PlanetKind {
    /// Oceans, climate bands and biomes (the home planet).
    Terrestrial = 0,
    /// Airless, waterless rolling regolith (the moon).
    Barren = 1,
}

impl PlanetKind {
    pub fn index(self) -> u8 {
        self as u8
    }

    pub fn from_index(index: u32) -> Option<Self> {
        match index {
            0 => Some(Self::Terrestrial),
            1 => Some(Self::Barren),
            _ => None,
        }
    }
}

/// Surface biome. The discriminants cross the C ABI (and match Java's
/// `PlanetBiome`), so they are frozen: changing them requires bumping
/// `WE_ABI_VERSION`. M1d maps each one to Minecraft biomes.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum Biome {
    DeepOcean = 0,
    Ocean = 1,
    Beach = 2,
    Plains = 3,
    Forest = 4,
    Taiga = 5,
    Tundra = 6,
    Desert = 7,
    Savanna = 8,
    Jungle = 9,
    Mountains = 10,
    SnowyPeaks = 11,
    Barren = 12,
}

impl Biome {
    pub const ALL: [Biome; 13] = [
        Biome::DeepOcean,
        Biome::Ocean,
        Biome::Beach,
        Biome::Plains,
        Biome::Forest,
        Biome::Taiga,
        Biome::Tundra,
        Biome::Desert,
        Biome::Savanna,
        Biome::Jungle,
        Biome::Mountains,
        Biome::SnowyPeaks,
        Biome::Barren,
    ];

    pub fn index(self) -> u8 {
        self as u8
    }

    pub fn from_index(index: u8) -> Option<Self> {
        Self::ALL.get(usize::from(index)).copied()
    }

    pub fn is_water(self) -> bool {
        matches!(self, Biome::DeepOcean | Biome::Ocean)
    }
}

/// One terrain column.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Column {
    /// Surface height in metres above (negative: below) the datum sphere,
    /// which is sea level on terrestrial planets.
    pub height: f64,
    /// Surface air temperature, °C.
    pub temperature: f64,
    /// 0 (arid) to 1 (wet).
    pub moisture: f64,
    pub biome: Biome,
}

impl Column {
    /// The surface as a whole-block offset from the datum: `floor(height)`.
    pub fn height_block(self) -> i32 {
        self.height.floor() as i32
    }
}

/// cos 4°: within this angle of a cube corner terrain is fully forced down.
const CORNER_INNER_COS: f64 = 0.997_564_050_259_824_2;
/// cos 8°: beyond this angle from every corner terrain is untouched.
const CORNER_OUTER_COS: f64 = 0.990_268_068_741_570_4;
/// Terrestrial terrain sinks to deep ocean at cube corners, where three seam
/// overlap bands meet (ROADMAP §4.3; M1e keeps those spots unbuildable).
pub const TERRESTRIAL_CORNER_FLOOR: f64 = -300.0;
/// Barren worlds get a deep crater floor at cube corners instead.
pub const BARREN_CORNER_FLOOR: f64 = -150.0;

fn smoothstep(edge0: f64, edge1: f64, x: f64) -> f64 {
    let t = ((x - edge0) / (edge1 - edge0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

fn lerp(a: f64, b: f64, t: f64) -> f64 {
    a + t * (b - a)
}

/// 1 within 4° of a cube corner, 0 beyond 8° from every corner.
fn corner_weight(dir: DVec3) -> f64 {
    // For a unit vector, the cosine of the angle to the nearest corner
    // direction (±1, ±1, ±1)/√3 is (|x| + |y| + |z|)/√3.
    let a = dir.abs();
    let nearest = (a.x + a.y + a.z) / 3.0_f64.sqrt();
    smoothstep(CORNER_OUTER_COS, CORNER_INNER_COS, nearest)
}

fn classify(height: f64, temperature: f64, moisture: f64) -> Biome {
    if height < -60.0 {
        Biome::DeepOcean
    } else if height < 0.0 {
        Biome::Ocean
    } else if height < 3.0 {
        Biome::Beach
    } else if height > 260.0 {
        if temperature < 0.0 {
            Biome::SnowyPeaks
        } else {
            Biome::Mountains
        }
    } else if temperature < -3.0 {
        Biome::Tundra
    } else if temperature < 8.0 {
        if moisture > 0.45 {
            Biome::Taiga
        } else {
            Biome::Tundra
        }
    } else if temperature < 22.0 {
        if moisture > 0.55 {
            Biome::Forest
        } else {
            Biome::Plains
        }
    } else if moisture < 0.3 {
        Biome::Desert
    } else if moisture < 0.55 {
        Biome::Savanna
    } else {
        Biome::Jungle
    }
}

/// Deterministic terrain for one planet: a seed, a kind, and the planet's
/// size (noise wavelengths are in metres on its datum sphere).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Terrain {
    seed: u64,
    kind: PlanetKind,
    radius: f64,
}

impl Terrain {
    pub fn new(seed: u64, kind: PlanetKind, sphere: CubeSphere) -> Self {
        Self {
            seed,
            kind,
            radius: sphere.radius(),
        }
    }

    pub fn seed(self) -> u64 {
        self.seed
    }

    pub fn kind(self) -> PlanetKind {
        self.kind
    }

    /// The column under unit direction `dir` (planet body frame; +Y is the
    /// spin axis, so `dir.y` is the sine of the latitude).
    pub fn sample(self, dir: DVec3) -> Column {
        let p = dir * self.radius;
        let corner = corner_weight(dir);
        match self.kind {
            PlanetKind::Terrestrial => self.terrestrial(dir, p, corner),
            PlanetKind::Barren => self.barren(p, corner),
        }
    }

    fn layer(self, layer: u64) -> u64 {
        layer_seed(self.seed, layer)
    }

    fn terrestrial(self, dir: DVec3, p: DVec3, corner: f64) -> Column {
        // Continents: one smooth large-scale field, continuous across the
        // coastline (both branches give -8 m at continent = 0).
        let continent = fbm(self.layer(1), p / 7_000.0, 5) * 1.6 + 0.1;
        let mut height = if continent < 0.0 {
            -8.0 + 220.0 * continent
        } else {
            -8.0 + 140.0 * continent
        };
        // Ridged mountains, only well inland.
        let ridge = 1.0 - fbm(self.layer(2), p / 2_200.0, 4).abs();
        height += smoothstep(0.2, 0.6, continent) * ridge * ridge * 420.0;
        // Small hills and dips everywhere.
        height += fbm(self.layer(3), p / 180.0, 3) * 10.0;
        height = lerp(height, TERRESTRIAL_CORNER_FLOOR, corner);

        let sin_latitude = dir.y;
        let temperature =
            28.0 - 50.0 * sin_latitude * sin_latitude - 18.0 * (height.max(0.0) / 1_000.0)
                + 5.0 * fbm(self.layer(4), p / 5_000.0, 2);
        let moisture = (0.5 + 0.6 * fbm(self.layer(5), p / 4_000.0, 3)).clamp(0.0, 1.0);
        Column {
            height,
            temperature,
            moisture,
            biome: classify(height, temperature, moisture),
        }
    }

    fn barren(self, p: DVec3, corner: f64) -> Column {
        let mut height =
            fbm(self.layer(1), p / 1_500.0, 5) * 90.0 + fbm(self.layer(3), p / 120.0, 3) * 12.0;
        height = lerp(height, BARREN_CORNER_FLOOR, corner);
        Column {
            height,
            temperature: -40.0,
            moisture: 0.0,
            biome: Biome::Barren,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use we_frames::{Face, SurfacePoint};

    const SEED: u64 = 42;
    const TERRAIN_GOLDEN: u64 = 0xc03c_eaa0_22c8_43dc;

    fn home() -> (CubeSphere, Terrain) {
        let sphere = CubeSphere::new(16_384);
        (sphere, Terrain::new(SEED, PlanetKind::Terrestrial, sphere))
    }

    /// Directions spread over the whole planet: a 64 × 64 grid on every face.
    fn planet_samples(sphere: CubeSphere) -> impl Iterator<Item = DVec3> {
        let n = f64::from(sphere.cells());
        Face::ALL.into_iter().flat_map(move |face| {
            (0..64).flat_map(move |i| {
                (0..64).map(move |j| {
                    let x = (f64::from(i) + 0.5) / 64.0 * n;
                    let z = (f64::from(j) + 0.5) / 64.0 * n;
                    sphere.direction(face, x, z)
                })
            })
        })
    }

    #[test]
    fn same_seed_same_terrain_and_seeds_differ() {
        let (sphere, terrain) = home();
        let other = Terrain::new(SEED + 1, PlanetKind::Terrestrial, sphere);
        let mut differ = 0;
        for dir in planet_samples(sphere).take(500) {
            assert_eq!(terrain.sample(dir), terrain.sample(dir));
            if terrain.sample(dir).height != other.sample(dir).height {
                differ += 1;
            }
        }
        assert!(
            differ > 450,
            "only {differ} of 500 columns changed with the seed"
        );
    }

    #[test]
    fn cube_corners_are_deep_ocean() {
        let (_, terrain) = home();
        for sx in [-1.0, 1.0] {
            for sy in [-1.0, 1.0] {
                for sz in [-1.0, 1.0] {
                    let column = terrain.sample(DVec3::new(sx, sy, sz).normalize());
                    assert!(
                        column.height <= TERRESTRIAL_CORNER_FLOOR + 1e-9,
                        "{column:?}"
                    );
                    assert_eq!(column.biome, Biome::DeepOcean);
                }
            }
        }
    }

    #[test]
    fn corner_bias_fades_out_by_eight_degrees() {
        for face in Face::ALL {
            assert_eq!(corner_weight(face.normal()), 0.0);
        }
        // 10 degrees from the (1,1,1) corner, towards +X.
        let corner = DVec3::ONE.normalize();
        let away = (corner * 10f64.to_radians().cos()
            + DVec3::new(2.0, -1.0, -1.0).normalize() * 10f64.to_radians().sin())
        .normalize();
        assert_eq!(corner_weight(away), 0.0);
        assert_eq!(corner_weight(corner), 1.0);
    }

    #[test]
    fn poles_are_colder_than_the_equator() {
        let (_, terrain) = home();
        let mean_temperature = |sin_lat: f64| {
            let cos_lat = (1.0 - sin_lat * sin_lat).sqrt();
            let total: f64 = (0..360)
                .map(|k| {
                    let lon = f64::from(k).to_radians();
                    terrain
                        .sample(DVec3::new(
                            cos_lat * lon.cos(),
                            sin_lat,
                            cos_lat * lon.sin(),
                        ))
                        .temperature
                })
                .sum();
            total / 360.0
        };
        let equator = mean_temperature(0.0);
        let north = mean_temperature(0.98);
        let south = mean_temperature(-0.98);
        assert!(
            north < equator - 30.0 && south < equator - 30.0,
            "{equator} {north} {south}"
        );
    }

    #[test]
    fn heights_stay_in_range_with_land_and_sea() {
        let (sphere, terrain) = home();
        let mut land = 0;
        let mut total = 0;
        let mut biomes = std::collections::HashSet::new();
        for dir in planet_samples(sphere) {
            let column = terrain.sample(dir);
            assert!((-320.0..=600.0).contains(&column.height), "{column:?}");
            assert!((0.0..=1.0).contains(&column.moisture));
            if !column.biome.is_water() {
                land += 1;
            }
            total += 1;
            biomes.insert(column.biome);
        }
        let share = f64::from(land) / f64::from(total);
        assert!((0.25..=0.75).contains(&share), "land share {share}");
        assert!(
            biomes.len() >= 8,
            "only {} biomes: {biomes:?}",
            biomes.len()
        );
        assert!(!biomes.contains(&Biome::Barren));
    }

    #[test]
    fn terrain_is_continuous_across_seams() {
        // Sampling on the sphere means the last block inside an edge and the
        // first block past it (on the neighbouring face) are about 1 m apart.
        let (sphere, terrain) = home();
        let n = f64::from(sphere.cells());
        for face in Face::ALL {
            for k in 0..50 {
                let along = (f64::from(k) + 0.5) / 50.0 * n;
                for (inside, beyond) in [
                    ((n - 0.5, along), (n + 0.5, along)),
                    ((0.5, along), (-0.5, along)),
                    ((along, n - 0.5), (along, n + 0.5)),
                    ((along, 0.5), (along, -0.5)),
                ] {
                    let a = terrain.sample(sphere.direction(face, inside.0, inside.1));
                    let q = sphere
                        .fold(SurfacePoint {
                            face,
                            x: beyond.0,
                            y: 0.0,
                            z: beyond.1,
                        })
                        .expect("one cell beyond the edge")
                        .point;
                    let b = terrain.sample(sphere.direction(q.face, q.x, q.z));
                    let step = (a.height - b.height).abs();
                    assert!(step < 5.0, "{face:?} {beyond:?}: {step} m step");
                }
            }
        }
    }

    #[test]
    fn barren_worlds_have_no_water_and_crater_corners() {
        let sphere = CubeSphere::new(4_096);
        let moon = Terrain::new(SEED, PlanetKind::Barren, sphere);
        for dir in planet_samples(sphere) {
            let column = moon.sample(dir);
            assert_eq!(column.biome, Biome::Barren);
            assert!((-160.0..=150.0).contains(&column.height), "{column:?}");
        }
        let corner = moon.sample(DVec3::ONE.normalize());
        assert!(corner.height <= BARREN_CORNER_FLOOR + 1e-9);
    }

    #[test]
    fn height_blocks_round_down() {
        let column = |height| Column {
            height,
            temperature: 0.0,
            moisture: 0.0,
            biome: Biome::Plains,
        };
        assert_eq!(column(12.9).height_block(), 12);
        assert_eq!(column(-0.5).height_block(), -1);
        assert_eq!(column(-3.0).height_block(), -3);
    }

    #[test]
    fn indexes_round_trip_and_match_the_abi_numbering() {
        for (i, biome) in Biome::ALL.into_iter().enumerate() {
            assert_eq!(usize::from(biome.index()), i);
            assert_eq!(Biome::from_index(biome.index()), Some(biome));
        }
        assert_eq!(Biome::Barren.index(), 12);
        assert_eq!(Biome::from_index(13), None);
        assert_eq!(PlanetKind::from_index(1), Some(PlanetKind::Barren));
        assert_eq!(PlanetKind::from_index(2), None);
    }

    /// FNV-1a over the exact bits of many columns of both planet kinds. A
    /// failure on one platform only means some maths differs there; terrain
    /// must be bit-identical everywhere (client LOD vs server chunks, worlds
    /// moved between machines).
    #[test]
    fn terrain_is_bit_identical_on_every_platform() {
        let (sphere, terrain) = home();
        let moon = Terrain::new(SEED, PlanetKind::Barren, CubeSphere::new(4_096));
        let mut hash = 0xcbf2_9ce4_8422_2325_u64;
        let mut feed = |bits: u64| {
            for byte in bits.to_le_bytes() {
                hash ^= u64::from(byte);
                hash = hash.wrapping_mul(0x0100_0000_01b3);
            }
        };
        for dir in planet_samples(sphere).step_by(7) {
            for column in [terrain.sample(dir), moon.sample(dir)] {
                feed(column.height.to_bits());
                feed(column.temperature.to_bits());
                feed(column.moisture.to_bits());
                feed(u64::from(column.biome.index()));
            }
        }
        assert_eq!(hash, TERRAIN_GOLDEN, "got {hash:#018x}");
    }
}
