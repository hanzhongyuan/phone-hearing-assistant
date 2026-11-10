package org.example.phonehearingassistant.dsp;

/** Allocation-free in-place radix-2 FFT with a normalized inverse transform. */
final class Fft {
    private final int size;
    private final int[] reverse;
    private final double[] cosine, sine;
    Fft(int n) {
        if (n < 2 || Integer.bitCount(n) != 1) throw new IllegalArgumentException("FFT size must be power of two");
        size = n;
        reverse = new int[n];
        cosine = new double[n / 2]; sine = new double[n / 2];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) reverse[i] = Integer.reverse(i) >>> (32 - bits);
        for (int i = 0; i < n / 2; i++) {
            cosine[i] = Math.cos(2 * Math.PI * i / n);
            sine[i] = Math.sin(2 * Math.PI * i / n);
        }
    }
    void transform(double[] re, double[] im, boolean inverse) {
        for (int i = 0; i < size; i++) {
            int j = reverse[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= size; len <<= 1) {
            int half = len / 2, step = size / len;
            for (int base = 0; base < size; base += len) for (int j = 0; j < half; j++) {
                int t = j * step;
                double wr = cosine[t], wi = inverse ? sine[t] : -sine[t];
                int a = base + j, b = a + half;
                double br = re[b] * wr - im[b] * wi, bi = re[b] * wi + im[b] * wr;
                re[b] = re[a] - br; im[b] = im[a] - bi;
                re[a] += br; im[a] += bi;
            }
        }
        if (inverse) for (int i = 0; i < size; i++) { re[i] /= size; im[i] /= size; }
    }
}
