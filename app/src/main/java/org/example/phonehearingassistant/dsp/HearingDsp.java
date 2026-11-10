package org.example.phonehearingassistant.dsp;

import java.util.Arrays;

/**
 * Stateful 16 kHz / 256-sample streaming processor. Call only from one audio thread.
 * No claim of acoustic calibration, clinical prescription, or guaranteed feedback cancellation.
 */
public final class HearingDsp {
    public static final int SAMPLE_RATE = 16000, HOP_SIZE = 256, FFT_SIZE = 512;
    private static final int N = FFT_SIZE, H = HOP_SIZE, BINS = N / 2 + 1;
    private static final double TWO_PI = 2 * Math.PI;
    private final Fft fft = new Fft(N);
    private DspConfig config;
    private final double[] history = new double[N], window = new double[N];
    private final double[] re = new double[N], im = new double[N], power = new double[BINS];
    private final double[] phase = new double[BINS], previousPhase = new double[BINS];
    private final double[][] synthesisPhase = new double[2][BINS];
    private final double[][] outputRe = new double[2][N], outputIm = new double[2][N];
    private final double[][] overlap = new double[2][H];
    private final double[] noise = new double[BINS], minimum = new double[BINS], noiseGain = new double[BINS];
    private final double[] feedbackGain = new double[BINS];
    private final int[] toneFrames = new int[BINS], binBand = new int[BINS];
    private final double[][] detectorPower = new double[2][8], smoothGainDb = new double[2][8];
    private final double[] bandEnergy = new double[8], limiterGain = {1, 1};
    private final float[] reusedOutput = new float[H * 2];
    private int frames, fadeSamples;
    private volatile float outputPeakDb = -120, feedbackReductionDb;
    private volatile long sanitizedInputSamples;

    public HearingDsp(DspConfig config) {
        for (int n = 0; n < N; n++) window[n] = Math.sqrt(0.5 - 0.5 * Math.cos(TWO_PI * n / N));
        for (int k = 0; k < BINS; k++) {
            double hz = k * (double) SAMPLE_RATE / N;
            int b = 0;
            while (b < 7 && hz > Math.sqrt(DspConfig.BAND_HZ[b] * DspConfig.BAND_HZ[b + 1])) b++;
            binBand[k] = b;
        }
        this.config = config == null ? new DspConfig() : config.copy();
        reset();
    }

    /** Takes a deep snapshot. Invoke between blocks from the processing thread. */
    public void setConfig(DspConfig next) {
        if (next == null) throw new IllegalArgumentException("config is null");
        DspConfig safe = next.copy();
        boolean changedMapping = config.mode != safe.mode || config.mappingType != safe.mappingType
                || !Arrays.equals(config.targetLowHz, safe.targetLowHz)
                || !Arrays.equals(config.targetHighHz, safe.targetHighHz);
        config = safe;
        if (changedMapping) reset();
    }

    /** Resets all audio state; safe on the audio thread when starting/stopping a stream. */
    public void reset() {
        Arrays.fill(history, 0); Arrays.fill(previousPhase, 0);
        Arrays.fill(noise, 1e-12); Arrays.fill(minimum, Double.POSITIVE_INFINITY);
        Arrays.fill(noiseGain, 1); Arrays.fill(feedbackGain, 1); Arrays.fill(toneFrames, 0);
        for (int ear = 0; ear < 2; ear++) {
            Arrays.fill(synthesisPhase[ear], 0); Arrays.fill(overlap[ear], 0);
            Arrays.fill(detectorPower[ear], 0); Arrays.fill(smoothGainDb[ear], 0);
        }
        Arrays.fill(limiterGain, 1); Arrays.fill(reusedOutput, 0);
        frames = 0; fadeSamples = 0; outputPeakDb = -120; feedbackReductionDb = 0;
    }

    /** Returns a reused stereo interleaved array: consume it before the next call. */
    public float[] process(float[] mono) { process(mono, reusedOutput); return reusedOutput; }

    /** Processes exactly one hop, without allocation. Output must have at least 512 elements. */
    public void process(float[] mono, float[] stereo) {
        if (mono == null || mono.length != H || stereo == null || stereo.length < H * 2)
            throw new IllegalArgumentException("Expected mono[256], stereo[512]");
        System.arraycopy(history, H, history, 0, H);
        for (int i = 0; i < H; i++) {
            float x = mono[i];
            if (!Float.isFinite(x)) { x = 0; sanitizedInputSamples++; }
            history[H + i] = Math.max(-1, Math.min(1, x));
        }
        for (int i = 0; i < N; i++) { re[i] = history[i] * window[i]; im[i] = 0; }
        fft.transform(re, im, false);
        for (int k = 0; k < BINS; k++) {
            power[k] = re[k] * re[k] + im[k] * im[k];
            phase[k] = Math.atan2(im[k], re[k]);
        }
        suppressNoiseAndTones();
        double peak = 0;
        for (int ear = 0; ear < 2; ear++) {
            double[] er = outputRe[ear], ei = outputIm[ear];
            Arrays.fill(er, 0); Arrays.fill(ei, 0);
            if (config.mode == 1) {
                for (int k = 1; k < N / 2; k++) { er[k] = re[k]; ei[k] = im[k]; }
            } else remap(ear, er, ei);
            applyCompressionAndGains(ear, er, ei);
            // DC and Nyquist are deliberately removed. Recreate a conjugate-symmetric real spectrum.
            for (int k = 1; k < N / 2; k++) { er[N-k] = er[k]; ei[N-k] = -ei[k]; }
            fft.transform(er, ei, true);
            double ceiling = dbToLinear(config.ceilingDb);
            double release = Math.exp(-1.0 / (SAMPLE_RATE * 0.08));
            for (int i = 0; i < H; i++) {
                double sample = er[i] * window[i] + overlap[ear][i];
                overlap[ear][i] = er[H+i] * window[H+i];
                double fade = Math.min(1, (fadeSamples + i) / (SAMPLE_RATE * 0.02));
                sample *= fade;
                double need = Math.abs(sample) > ceiling ? ceiling / Math.abs(sample) : 1;
                limiterGain[ear] = need < limiterGain[ear] ? need : release * limiterGain[ear] + (1-release) * need;
                sample *= limiterGain[ear];
                // Smooth knee for the last 20 percent of the available digital range.
                double a = Math.abs(sample), knee = ceiling * 0.8;
                if (a > knee) sample = Math.copySign(knee + ceiling * 0.2 * (1 - Math.exp(-(a-knee)/(ceiling*0.2))), sample);
                sample = Math.max(-ceiling, Math.min(ceiling, sample));
                if (!Double.isFinite(sample)) sample = 0;
                stereo[i*2+ear] = (float) sample;
                peak = Math.max(peak, Math.abs(sample));
            }
        }
        System.arraycopy(phase, 0, previousPhase, 0, BINS);
        fadeSamples = Math.min(SAMPLE_RATE, fadeSamples + H);
        frames++;
        outputPeakDb = (float) (20 * Math.log10(Math.max(peak, 1e-6)));
    }

    private void suppressNoiseAndTones() {
        double mostAttenuation = 1;
        for (int k = 1; k < N / 2; k++) {
            double p = Math.max(power[k], 1e-14);
            // Minimum-statistics estimate. Updates remain causal and do not require recording a noise file.
            minimum[k] = Math.min(minimum[k], p);
            if (frames == 0) noise[k] = p * 0.2;
            else {
                double candidate = Math.min(p, minimum[k] * 1.5);
                double a = candidate < noise[k] ? 0.85 : 0.997;
                noise[k] = a * noise[k] + (1-a) * candidate;
            }
            double wantedNoiseGain = config.noiseReduction ? Math.max(0.18, Math.sqrt(Math.max(0, 1 - 1.4 * noise[k] / p))) : 1;
            noiseGain[k] = 0.7 * noiseGain[k] + 0.3 * wantedNoiseGain;
            double neighborhood = 0; int count = 0;
            for (int d = -5; d <= 5; d++) if (Math.abs(d) >= 2 && k+d > 0 && k+d < N/2) {
                neighborhood += power[k+d]; count++;
            }
            boolean narrow = config.feedbackSuppression && k >= 8 && count > 0
                    && p > 30 * (neighborhood / count + 1e-12) && p > 0.0001;
            toneFrames[k] = narrow ? Math.min(1000, toneFrames[k] + 1) : Math.max(0, toneFrames[k] - 2);
            double target = toneFrames[k] >= 10 ? 0.125 : 1;
            if (!config.feedbackSuppression) target = 1;
            double a = target < feedbackGain[k] ? 0.6 : 0.985;
            feedbackGain[k] = a * feedbackGain[k] + (1-a) * target;
        }
        for (int k = 1; k < N/2; k++) {
            double fg = feedbackGain[k];
            if (k > 1) fg = Math.min(fg, Math.sqrt(feedbackGain[k-1]));
            if (k < N/2-1) fg = Math.min(fg, Math.sqrt(feedbackGain[k+1]));
            double g = noiseGain[k] * fg;
            re[k] *= g; im[k] *= g;
            mostAttenuation = Math.min(mostAttenuation, fg);
        }
        if (frames % 64 == 63) Arrays.fill(minimum, Double.POSITIVE_INFINITY);
        feedbackReductionDb = (float) (-20 * Math.log10(Math.max(mostAttenuation, 1e-6)));
    }

    private void remap(int ear, double[] er, double[] ei) {
        double low = config.targetLowHz[ear], high = config.targetHighHz[ear];
        int minBin = config.mappingType == 0 ? (int) Math.ceil(low * N / SAMPLE_RATE) : 1;
        int maxBin = Math.min(N/2-1, (int) Math.floor(high * N / SAMPLE_RATE));
        double inputEnergy = 0;
        for (int k = 1; k < N/2; k++) {
            double hz = k * (double) SAMPLE_RATE / N;
            double mapped = mapHz(hz, low, high);
            int targetBin = Math.max(minBin, Math.min(maxBin, (int) Math.round(mapped * N / SAMPLE_RATE)));
            double magnitude = Math.hypot(re[k], im[k]);
            double expected = TWO_PI * k * H / N;
            double delta = wrap(phase[k] - previousPhase[k] - expected);
            double instantaneousHz = hz + delta * SAMPLE_RATE / (TWO_PI * H);
            instantaneousHz = Math.max(0, Math.min(SAMPLE_RATE/2.0, instantaneousHz));
            if (frames == 0) synthesisPhase[ear][k] = phase[k];
            else synthesisPhase[ear][k] = wrap(synthesisPhase[ear][k] + TWO_PI * mapHz(instantaneousHz, low, high) * H / SAMPLE_RATE);
            er[targetBin] += magnitude * Math.cos(synthesisPhase[ear][k]);
            ei[targetBin] += magnitude * Math.sin(synthesisPhase[ear][k]);
            inputEnergy += magnitude * magnitude;
        }
        double outputEnergy = 0;
        for (int k = 1; k < N/2; k++) outputEnergy += er[k]*er[k] + ei[k]*ei[k];
        // Collisions can increase a frame's energy. Attenuate only; never normalize silence upwards.
        double scale = outputEnergy > inputEnergy && outputEnergy > 1e-18 ? Math.sqrt(inputEnergy/outputEnergy) : 1;
        for (int k = 1; k < N/2; k++) { er[k] *= scale; ei[k] *= scale; }
    }

    private double mapHz(double hz, double low, double high) {
        if (config.mappingType == 1 && hz <= low) return hz;
        if (config.mappingType == 1) return low + (hz-low) * (high-low) / (SAMPLE_RATE/2.0-low);
        return low + hz * (high-low) / (SAMPLE_RATE/2.0);
    }

    private void applyCompressionAndGains(int ear, double[] er, double[] ei) {
        Arrays.fill(bandEnergy, 0);
        for (int k = 1; k < N/2; k++) bandEnergy[binBand[k]] += er[k]*er[k] + ei[k]*ei[k];
        double attack = Math.exp(-H / (SAMPLE_RATE * config.attackMs / 1000.0));
        double release = Math.exp(-H / (SAMPLE_RATE * config.releaseMs / 1000.0));
        double gainSmooth = Math.exp(-H / (SAMPLE_RATE * 0.03));
        for (int b = 0; b < 8; b++) {
            double p = bandEnergy[b] * 4 / (N*(double)N);
            double a = p > detectorPower[ear][b] ? attack : release;
            detectorPower[ear][b] = a * detectorPower[ear][b] + (1-a) * p;
            double levelDb = 10 * Math.log10(Math.max(detectorPower[ear][b], 1e-12));
            double compressionDb = config.compressionEnabled
                    ? Math.max(0, levelDb-config.compressionThresholdDb) * (1.0/config.compressionRatio - 1) : 0;
            double wanted = config.masterGainDb + config.gainDb[ear][b] + compressionDb;
            smoothGainDb[ear][b] = gainSmooth * smoothGainDb[ear][b] + (1-gainSmooth) * wanted;
        }
        for (int k = 1; k < N/2; k++) {
            double hz = k * (double) SAMPLE_RATE / N;
            double db = interpolateGain(ear, hz), g = dbToLinear(db);
            er[k] *= g; ei[k] *= g;
        }
    }

    private double interpolateGain(int ear, double hz) {
        if (hz <= DspConfig.BAND_HZ[0]) return smoothGainDb[ear][0];
        for (int b = 1; b < 8; b++) if (hz <= DspConfig.BAND_HZ[b]) {
            double t = Math.log(hz / DspConfig.BAND_HZ[b-1]) / Math.log((double)DspConfig.BAND_HZ[b] / DspConfig.BAND_HZ[b-1]);
            return smoothGainDb[ear][b-1] * (1-t) + smoothGainDb[ear][b] * t;
        }
        return smoothGainDb[ear][7];
    }

    public float getOutputPeakDb() { return outputPeakDb; }
    public float getFeedbackReductionDb() { return feedbackReductionDb; }
    public long getSanitizedInputSamples() { return sanitizedInputSamples; }
    /** DSP framing delay only; excludes Android, model and Bluetooth buffers. */
    public int getAlgorithmicLatencySamples() { return H; }
    private static double dbToLinear(double db) { return Math.pow(10, db/20); }
    private static double wrap(double phase) { return phase - TWO_PI * Math.floor((phase + Math.PI) / TWO_PI); }
}
