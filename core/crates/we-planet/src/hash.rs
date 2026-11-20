//! Integer hashing: the only source of randomness in terrain.

/// SplitMix64's finaliser: a fast, well-mixed permutation of 64-bit values.
pub fn mix(mut z: u64) -> u64 {
    z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    z ^ (z >> 31)
}

/// Hash of the integer lattice point (`i`, `j`, `k`) under `seed`.
pub fn hash3(seed: u64, i: i64, j: i64, k: i64) -> u64 {
    let mut h = mix(seed);
    for v in [i, j, k] {
        h = mix(h ^ (v as u64).wrapping_mul(0x9e37_79b9_7f4a_7c15));
    }
    h
}

/// Seed of one independent noise layer, derived from a planet seed.
pub fn layer_seed(seed: u64, layer: u64) -> u64 {
    mix(seed ^ mix(layer.wrapping_add(0x632b_e59b_d9b4_e019)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hashes_are_stable_values() {
        // Pinned: changing the hash changes every world generated so far.
        assert_eq!(mix(0), 0);
        assert_eq!(mix(1), 0x5692_161d_100b_05e5);
        assert_eq!(hash3(42, 1, -2, 3), hash3(42, 1, -2, 3));
    }

    #[test]
    fn nearby_points_and_seeds_hash_differently() {
        let mut seen = std::collections::HashSet::new();
        for i in -20..20 {
            for j in -20..20 {
                for k in -5..5 {
                    assert!(seen.insert(hash3(7, i, j, k)), "collision at {i},{j},{k}");
                }
            }
        }
        assert_ne!(hash3(1, 0, 0, 0), hash3(2, 0, 0, 0));
        assert_ne!(layer_seed(1, 0), layer_seed(1, 1));
    }

    #[test]
    fn low_bits_are_balanced() {
        let ones = (0..20_000)
            .filter(|&i| hash3(99, i, i / 7, -i) & 1 == 1)
            .count();
        let share = ones as f64 / 20_000.0;
        assert!((0.48..0.52).contains(&share), "share of ones {share}");
    }
}
