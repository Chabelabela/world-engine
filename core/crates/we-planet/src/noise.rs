use crate::hash::{hash3, layer_seed};
use glam::DVec3;

/// Edge midpoints of a cube: the classic Perlin gradient set.
const GRADIENTS: [DVec3; 12] = [
    DVec3::new(1.0, 1.0, 0.0),
    DVec3::new(-1.0, 1.0, 0.0),
    DVec3::new(1.0, -1.0, 0.0),
    DVec3::new(-1.0, -1.0, 0.0),
    DVec3::new(1.0, 0.0, 1.0),
    DVec3::new(-1.0, 0.0, 1.0),
    DVec3::new(1.0, 0.0, -1.0),
    DVec3::new(-1.0, 0.0, -1.0),
    DVec3::new(0.0, 1.0, 1.0),
    DVec3::new(0.0, -1.0, 1.0),
    DVec3::new(0.0, 1.0, -1.0),
    DVec3::new(0.0, -1.0, -1.0),
];

fn fade(t: f64) -> f64 {
    t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
}

fn lerp(a: f64, b: f64, t: f64) -> f64 {
    a + t * (b - a)
}

/// Perlin-style gradient noise at `p`: smooth, roughly in [-1, 1], and zero at
/// every integer lattice point. Gradients come from hashing, so there are no
/// tables and no state.
pub fn gradient(seed: u64, p: DVec3) -> f64 {
    let base = p.floor();
    let f = p - base;
    let (i, j, k) = (base.x as i64, base.y as i64, base.z as i64);
    let corner = |di: i64, dj: i64, dk: i64| {
        let h = hash3(
            seed,
            i.wrapping_add(di),
            j.wrapping_add(dj),
            k.wrapping_add(dk),
        );
        let g = GRADIENTS[(h % 12) as usize];
        g.dot(f - DVec3::new(di as f64, dj as f64, dk as f64))
    };
    let (u, v, w) = (fade(f.x), fade(f.y), fade(f.z));
    lerp(
        lerp(
            lerp(corner(0, 0, 0), corner(1, 0, 0), u),
            lerp(corner(0, 1, 0), corner(1, 1, 0), u),
            v,
        ),
        lerp(
            lerp(corner(0, 0, 1), corner(1, 0, 1), u),
            lerp(corner(0, 1, 1), corner(1, 1, 1), u),
            v,
        ),
        w,
    )
}

/// Fractal sum of `octaves` layers of [`gradient`] noise, each with half the
/// amplitude and twice the frequency of the last, normalised to roughly
/// [-1, 1]. Zero octaves gives 0.
pub fn fbm(seed: u64, p: DVec3, octaves: u32) -> f64 {
    let (mut sum, mut amplitude, mut frequency, mut norm) = (0.0, 1.0, 1.0, 0.0);
    for octave in 0..octaves {
        sum += amplitude * gradient(layer_seed(seed, u64::from(octave)), p * frequency);
        norm += amplitude;
        amplitude *= 0.5;
        frequency *= 2.0;
    }
    if norm > 0.0 { sum / norm } else { 0.0 }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn samples() -> impl Iterator<Item = DVec3> {
        (0..20_000).map(|i| {
            let t = f64::from(i);
            DVec3::new(
                t * 0.137 - 900.0,
                (t * 0.731).rem_euclid(97.0) - 40.0,
                t * 0.053,
            )
        })
    }

    #[test]
    fn zero_at_lattice_points() {
        for i in -5..5 {
            for j in -5..5 {
                let p = DVec3::new(f64::from(i), f64::from(j), 3.0);
                assert_eq!(gradient(11, p), 0.0);
            }
        }
    }

    #[test]
    fn stays_in_range_and_is_not_flat() {
        let (mut lo, mut hi) = (f64::MAX, f64::MIN);
        for p in samples() {
            let v = gradient(5, p);
            lo = lo.min(v);
            hi = hi.max(v);
        }
        assert!(lo >= -1.1 && hi <= 1.1, "range {lo}..{hi}");
        assert!(lo < -0.5 && hi > 0.5, "too flat: {lo}..{hi}");
    }

    #[test]
    fn is_continuous() {
        // Gradient noise is Lipschitz: tiny steps give tiny changes.
        for p in samples().take(2_000) {
            let step = DVec3::new(1e-4, -2e-4, 1e-4);
            let change = (gradient(5, p + step) - gradient(5, p)).abs();
            assert!(change < 4.0 * step.length(), "jump of {change} at {p:?}");
        }
    }

    #[test]
    fn seeds_give_different_fields() {
        let differ = samples()
            .take(100)
            .filter(|&p| gradient(1, p) != gradient(2, p))
            .count();
        assert!(differ > 90);
    }

    #[test]
    fn fbm_is_normalised() {
        assert_eq!(fbm(3, DVec3::splat(0.4), 0), 0.0);
        for p in samples().take(5_000) {
            let v = fbm(3, p * 0.1, 5);
            assert!((-1.1..=1.1).contains(&v), "fbm {v} at {p:?}");
        }
    }
}
