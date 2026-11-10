package org.example.phonehearingassistant.dsp;

/** Digital-domain experiment settings. No value in this class is dB SPL or dB HL. */
public final class DspConfig {
    public static final int SAMPLE_RATE = 16000;
    public static final int HOP_SIZE = 256;
    public static final int FFT_SIZE = 512;
    public static final int[] BAND_HZ = {125, 250, 500, 1000, 2000, 3000, 4000, 6000};
    public int mode = 1;
    /** 0: all-band affine remapping; 1: preserve lows, compress frequencies above targetLowHz. */
    public int mappingType = 0;
    public float[][] gainDb = new float[2][8];
    public float[] targetLowHz = {300, 300};
    public float[] targetHighHz = {3000, 3000};
    public float masterGainDb = 0;
    public float compressionThresholdDb = -42;
    public float compressionRatio = 2;
    public float attackMs = 8;
    public float releaseMs = 120;
    public float ceilingDb = -18;
    public boolean compressionEnabled = true;
    public boolean noiseReduction = false;
    public boolean feedbackSuppression = true;

    /**
     * An explicitly experimental relative-threshold fit, NOT NAL-NL2 or DSL.
     * Input: eight measured digital thresholds per output channel, in dBFS.
     * NaN means not measured; unmeasured bands receive zero gain.
     * Bone conduction crosses the skull, so channel-specific results are not isolated cochlear thresholds.
     */
    public void fitRelativeThresholds(float[] left, float[] right) {
        float[][] thresholds = {left, right};
        for (int ear = 0; ear < 2; ear++) {
            float[] values = thresholds[ear];
            float best = Float.POSITIVE_INFINITY;
            if (values != null) for (int b = 0; b < Math.min(8, values.length); b++) {
                float v = values[b];
                if (Float.isFinite(v) && v >= -100 && v <= 0) best = Math.min(best, v);
            }
            for (int b = 0; b < 8; b++) {
                float v = values != null && b < values.length ? values[b] : Float.NaN;
                gainDb[ear][b] = Float.isFinite(best) && Float.isFinite(v) && v >= -100 && v <= 0
                        ? clamp((v - best) * 0.45f, 0, 18) : 0;
            }
        }
    }

    /** Deep, validated snapshot: mutating UI-owned arrays cannot affect a running audio block. */
    public DspConfig copy() {
        DspConfig c = new DspConfig();
        c.mode = mode >= 1 && mode <= 3 ? mode : 1;
        c.mappingType = mappingType == 1 ? 1 : 0;
        for (int e = 0; e < 2; e++) {
            c.targetLowHz[e] = clean(targetLowHz != null && e < targetLowHz.length ? targetLowHz[e] : 300, 80, 5000, 300);
            c.targetHighHz[e] = clean(targetHighHz != null && e < targetHighHz.length ? targetHighHz[e] : 3000,
                    c.targetLowHz[e] + 250, 7600, Math.max(3000, c.targetLowHz[e] + 250));
            for (int b = 0; b < 8; b++) c.gainDb[e][b] = clean(gainDb != null && e < gainDb.length
                    && gainDb[e] != null && b < gainDb[e].length ? gainDb[e][b] : 0, -24, 24, 0);
        }
        c.masterGainDb = clean(masterGainDb, -60, 12, 0);
        c.compressionThresholdDb = clean(compressionThresholdDb, -70, -12, -42);
        c.compressionRatio = clean(compressionRatio, 1, 6, 2);
        c.attackMs = clean(attackMs, 1, 100, 8);
        c.releaseMs = clean(releaseMs, 20, 2000, 120);
        // This bounds only floating-point samples; absolute bone-conduction force is uncalibrated.
        c.ceilingDb = clean(ceilingDb, -90, -6, -18);
        c.compressionEnabled = compressionEnabled;
        c.noiseReduction = noiseReduction;
        c.feedbackSuppression = feedbackSuppression;
        return c;
    }

    private static float clean(float v, float min, float max, float fallback) {
        return Float.isFinite(v) ? clamp(v, min, max) : clamp(fallback, min, max);
    }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
}

