"""Reference HDR->SDR math, mirroring the app shader (app/src/main/java/dev/tgprep/Gl.kt)."""

import numpy as np

HLG_WHITE = 0.62
SHOULDER = 0.55
K2020 = np.array([0.2627, 0.6780, 0.0593])
K709 = np.array([0.2126, 0.7152, 0.0722])
BT2020_TO_BT709 = np.array(
    [
        [1.660491, -0.587641, -0.072850],
        [-0.124550, 1.132900, -0.008349],
        [-0.018151, -0.100579, 1.118730],
    ]
)


def hlg_inverse_oetf(e: np.ndarray) -> np.ndarray:
    """Return scene-linear light (0..1) for HLG signal values."""
    e = np.asarray(e, dtype=np.float64)
    return np.where(e <= 0.5, e * e / 3.0, (np.exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0)


def hlg_oetf(x: np.ndarray) -> np.ndarray:
    """Return the HLG signal for scene-linear light (0..1)."""
    x = np.asarray(x, dtype=np.float64)
    return np.where(
        x <= 1 / 12, np.sqrt(3 * x), 0.17883277 * np.log(np.maximum(12 * x - 0.28466892, 1e-12)) + 0.55991073
    )


def hlg_to_sdr_code(rgb_hlg: np.ndarray) -> np.ndarray:
    """Return 8-bit-scale BT.709 R'G'B' (0..255, BT.709 OETF) for one HLG BT.2020 R'G'B' pixel."""
    # Scene-light conversion, mirroring Gl.kt: HLG 75% -> HLG_WHITE linear, exponential shoulder.
    rel = hlg_inverse_oetf(rgb_hlg) * (HLG_WHITE / float(hlg_inverse_oetf(np.array(0.75))))
    y = float(K2020 @ rel)
    if y > SHOULDER:
        rel = rel * (SHOULDER + (1 - SHOULDER) * (1 - np.exp(-(y - SHOULDER) / (1 - SHOULDER)))) / y
    c = BT2020_TO_BT709 @ rel
    lum = float(np.clip(K709 @ c, 0, 1))
    mn = c.min()
    if mn < 0:
        c = lum + (c - lum) * (lum / (lum - mn))
    mx = c.max()
    if mx > 1:
        c = lum + (c - lum) * ((1 - lum) / (mx - lum))
    c = np.clip(c, 0, 1)
    return np.where(c < 0.018, 4.5 * c, 1.099 * c**0.45 - 0.099) * 255.0
