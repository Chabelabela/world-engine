use crate::terrain::Terrain;
use we_frames::{CubeSphere, Face};

/// Most columns one call may fill: a 1024 × 1024 region.
pub const MAX_COLUMNS: u64 = 1 << 20;

/// Why [`fill_columns`] refused a request.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ColumnsError {
    /// Part of the region lies outside the face.
    OutsideFace,
    /// More than [`MAX_COLUMNS`] columns.
    TooMany,
    /// An output buffer is shorter than `count_x * count_z`.
    BufferTooSmall,
}

/// Fill a `count_x` × `count_z` region of columns whose north-west cell is
/// (`x0`, `z0`) on `face`. Each cell is sampled at its centre. Outputs are
/// row-major (index `dz * count_x + dx`): the surface as whole blocks above
/// the datum (`Column::height_block`) and the biome index.
///
/// An empty region (a zero count) still has its position validated.
///
/// Pure and thread-safe: call it from as many world-generation threads as
/// you like.
#[allow(clippy::too_many_arguments)]
pub fn fill_columns(
    sphere: CubeSphere,
    terrain: Terrain,
    face: Face,
    x0: i64,
    z0: i64,
    count_x: u32,
    count_z: u32,
    heights: &mut [i32],
    biomes: &mut [u8],
) -> Result<(), ColumnsError> {
    let total = u64::from(count_x) * u64::from(count_z);
    if total > MAX_COLUMNS {
        return Err(ColumnsError::TooMany);
    }
    let cells = i64::from(sphere.cells());
    let fits = |start: i64, count: u32| {
        start >= 0
            && start
                .checked_add(i64::from(count))
                .is_some_and(|end| end <= cells)
    };
    if !fits(x0, count_x) || !fits(z0, count_z) {
        return Err(ColumnsError::OutsideFace);
    }
    let total = total as usize;
    if heights.len() < total || biomes.len() < total {
        return Err(ColumnsError::BufferTooSmall);
    }
    let width = count_x as usize;
    for dz in 0..count_z {
        let z = (z0 + i64::from(dz)) as f64 + 0.5;
        for dx in 0..count_x {
            let x = (x0 + i64::from(dx)) as f64 + 0.5;
            let column = terrain.sample(sphere.direction(face, x, z));
            let index = dz as usize * width + dx as usize;
            heights[index] = column.height_block();
            biomes[index] = column.biome.index();
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::terrain::PlanetKind;

    fn home() -> (CubeSphere, Terrain) {
        let sphere = CubeSphere::new(16_384);
        (sphere, Terrain::new(42, PlanetKind::Terrestrial, sphere))
    }

    #[test]
    fn fills_row_major_cell_centres() {
        let (sphere, terrain) = home();
        let (x0, z0, w, h) = (8_000, 300, 5, 3);
        let mut heights = vec![0; 15];
        let mut biomes = vec![0; 15];
        fill_columns(
            sphere,
            terrain,
            Face::E2,
            x0,
            z0,
            w,
            h,
            &mut heights,
            &mut biomes,
        )
        .unwrap();
        for dz in 0..h {
            for dx in 0..w {
                let x = (x0 + i64::from(dx)) as f64 + 0.5;
                let z = (z0 + i64::from(dz)) as f64 + 0.5;
                let column = terrain.sample(sphere.direction(Face::E2, x, z));
                let i = (dz * w + dx) as usize;
                assert_eq!(heights[i], column.height_block());
                assert_eq!(biomes[i], column.biome.index());
            }
        }
    }

    #[test]
    fn rejects_bad_requests() {
        let (sphere, terrain) = home();
        let n = i64::from(sphere.cells());
        let mut heights = vec![0; 16];
        let mut biomes = vec![0; 16];
        let fill = |x0, z0, w, h, heights: &mut [i32], biomes: &mut [u8]| {
            fill_columns(sphere, terrain, Face::North, x0, z0, w, h, heights, biomes)
        };
        assert_eq!(
            fill(-1, 0, 4, 4, &mut heights, &mut biomes),
            Err(ColumnsError::OutsideFace)
        );
        assert_eq!(
            fill(n - 3, 0, 4, 4, &mut heights, &mut biomes),
            Err(ColumnsError::OutsideFace)
        );
        assert_eq!(
            fill(0, i64::MAX, 4, 4, &mut heights, &mut biomes),
            Err(ColumnsError::OutsideFace)
        );
        assert_eq!(
            fill(0, 0, 2048, 1024, &mut heights, &mut biomes),
            Err(ColumnsError::TooMany)
        );
        assert_eq!(
            fill(0, 0, 5, 4, &mut heights, &mut biomes),
            Err(ColumnsError::BufferTooSmall)
        );
        assert_eq!(fill(n - 4, n - 4, 4, 4, &mut heights, &mut biomes), Ok(()));
        assert_eq!(fill(0, 0, 0, 7, &mut [], &mut []), Ok(()));
    }

    #[test]
    fn concurrent_callers_get_identical_results() {
        fn assert_send_sync<T: Send + Sync>() {}
        assert_send_sync::<Terrain>();
        assert_send_sync::<CubeSphere>();
        let (sphere, terrain) = home();
        let region = move || {
            let mut heights = vec![0; 32 * 32];
            let mut biomes = vec![0; 32 * 32];
            fill_columns(
                sphere,
                terrain,
                Face::E0,
                100,
                100,
                32,
                32,
                &mut heights,
                &mut biomes,
            )
            .unwrap();
            (heights, biomes)
        };
        let expected = region();
        let threads: Vec<_> = (0..8).map(|_| std::thread::spawn(region)).collect();
        for thread in threads {
            assert_eq!(thread.join().unwrap(), expected);
        }
    }
}
