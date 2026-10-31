"""Reference HDR->SDR math, mirroring the app shader (app/src/main/java/dev/tgprep/Gl.kt)."""

import numpy as np

SDR_WHITE = 203.0
K2020 = np.array([0.2627, 0.6780, 0.0593])
K709 = np.array([0.2126, 0.7152, 0.0722])
BT2020_TO_BT709 = np.array(
    [
        [1.660491, -0.587641, -0.072850],
        [-0.124550, 1.132900, -0.008349],
        [-0.018151, -0.100579, 1.118730],
    ]
)
M1, M2, C1, C2, C3 = 0.1593017578125, 78.84375, 0.8359375, 18.8515625, 18.6875


def hlg_inverse_oetf(e: np.ndarray) -> np.ndarray:
    """Return scene-linear light (0..1) for HLG signal values."""
    e = np.asarray(e, dtype=np.float64)
    return np.where(e <= 0.5, e * e / 3.0, (np.exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0)


def hlg_oetf(x: np.ndarray) -> np.ndarray:
    """Return the HLG signal for scene-linear light (0..1)."""
    x = np.asarray(x, dtype=np.float64)
    return np.where(x <= 1 / 12, np.sqrt(3 * x), 0.17883277 * np.log(np.maximum(12 * x - 0.28466892, 1e-12)) + 0.55991073)


def pq_to_nits(e: float) -> float:
    """Return display luminance in nits for a PQ signal value."""
    p = np.clip(e, 0, 1) ** (1 / M2)
    return 10000.0 * (np.maximum(p - C1, 0) / (C2 - C3 * p)) ** (1 / M1)


def nits_to_pq(n: float) -> float:
    """Return the PQ signal value for a luminance in nits."""
    y = np.clip(n / 10000.0, 0, 1) ** M1
    return ((C1 + C2 * y) / (1 + C3 * y)) ** M2


def eetf(nits: float, src_peak: float) -> float:
    """Apply the ITU-R BT.2390 EETF (black level 0) towards SDR_WHITE."""
    src_pq = nits_to_pq(src_peak)
    max_lum = nits_to_pq(SDR_WHITE) / src_pq
    if max_lum >= 1:
        return min(nits, SDR_WHITE)
    e = min(nits_to_pq(nits) / src_pq, 1.0)
    ks = 1.5 * max_lum - 0.5
    if e > ks:
        t = (e - ks) / (1 - ks)
        e = (2 * t**3 - 3 * t**2 + 1) * ks + (t**3 - 2 * t**2 + t) * (1 - ks) + (-2 * t**3 + 3 * t**2) * max_lum
    return pq_to_nits(e * src_pq)


def hlg_to_sdr_code(rgb_hlg: np.ndarray) -> np.ndarray:
    """Return 8-bit-scale BT.709 R'G'B' (0..255, gamma 2.4) for one HLG BT.2020 R'G'B' pixel."""
    scene = hlg_inverse_oetf(rgb_hlg)
    nits = 1000.0 * max(float(K2020 @ scene), 1e-6) ** 0.2 * scene
    y = float(K2020 @ nits)
    if y > 0:
        nits = nits * eetf(y, 1000.0) / y
    c = BT2020_TO_BT709 @ (nits / SDR_WHITE)
    lum = float(np.clip(K709 @ c, 0, 1))
    mn = c.min()
    if mn < 0:
        c = lum + (c - lum) * (lum / (lum - mn))
    mx = c.max()
    if mx > 1:
        c = lum + (c - lum) * ((1 - lum) / (mx - lum))
    return np.clip(c, 0, 1) ** (1 / 2.4) * 255.0
