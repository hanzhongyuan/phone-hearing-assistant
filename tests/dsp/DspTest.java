package org.example.phonehearingassistant.dsp;

import java.util.Arrays;
import java.util.Random;

/** Deterministic, dependency-free signal tests; run with assertions enabled or directly main(). */
public final class DspTest {
    private static int passed;
    public static void main(String[] args) {
        fftRoundTrip();
        overlapAddUnityAndStereo();
        independentEarGain();
        compressorRatio();
        frequencyMapping();
        highFrequencyMapping();
        limiterAndInvalidInput();
        quietComfortCeilings();
        feedbackToneSuppression();
        wienerNoiseSuppression();
        thresholdsAndSnapshot();
        runtimeBudget();
        System.out.println("PASS: " + passed + " DSP signal tests");
    }
    private static DspConfig transparent() {
        DspConfig c = new DspConfig();
        c.compressionEnabled = false; c.feedbackSuppression = false; c.noiseReduction = false;
        c.ceilingDb = -6;
        return c;
    }
    private static void fftRoundTrip() {
        Fft f = new Fft(512); Random r = new Random(2);
        double[] re = new double[512], im = new double[512], original = new double[512];
        for (int i=0;i<512;i++) original[i] = re[i] = r.nextDouble()*2-1;
        f.transform(re,im,false); f.transform(re,im,true);
        for (int i=0;i<512;i++) { check(Math.abs(re[i]-original[i])<1e-12,"FFT roundtrip"); check(Math.abs(im[i])<1e-12,"FFT imaginary residue"); }
        pass("FFT normalized inverse");
    }
    private static void overlapAddUnityAndStereo() {
        HearingDsp d = new HearingDsp(transparent());
        float[] in = new float[256]; double error=0, energy=0;
        for(int block=0;block<150;block++) {
            tone(in,block,1000,0.04); float[] out=d.process(in);
            if(block>10) for(int i=0;i<256;i++) {
                double expected=0.04*Math.sin(2*Math.PI*1000*(block*256+i-256)/16000.0);
                error+=(out[i*2]-expected)*(out[i*2]-expected); energy+=expected*expected;
                check(out[i*2]==out[i*2+1],"Stereo mismatch");
            }
        }
        double relative=Math.sqrt(error/energy);
        check(relative<0.002,"OLA unity relative error="+relative);
        check(d.getAlgorithmicLatencySamples()==256,"Framing latency");
        pass("sqrt-Hann overlap/add unity, stereo and 256-sample delay (error="+relative+")");
    }
    private static void independentEarGain() {
        DspConfig c=transparent(); Arrays.fill(c.gainDb[1],6);
        HearingDsp d=new HearingDsp(c);float[] in=new float[256];double l=0,r=0;
        for(int b=0;b<150;b++) {tone(in,b,1000,0.015);float[] out=d.process(in);if(b>30)for(int i=0;i<256;i++){l+=out[2*i]*out[2*i];r+=out[2*i+1]*out[2*i+1];}}
        double difference=10*Math.log10(r/l);
        check(Math.abs(difference-6)<0.05,"Ear gain difference="+difference);
        pass("independent channel fitting (difference="+difference+" dB)");
    }
    private static void compressorRatio() {
        DspConfig c=transparent();c.compressionEnabled=true;c.compressionRatio=2;c.compressionThresholdDb=-50;
        double quiet=rms(new HearingDsp(c),1000,0.02), loud=rms(new HearingDsp(c),1000,0.08);
        double difference=20*Math.log10(loud/quiet);
        check(difference>5.6&&difference<6.8,"2:1 compression output difference="+difference);
        pass("2:1 WDRC response (12.04 dB input -> "+difference+" dB output)");
    }
    private static void frequencyMapping() {
        DspConfig c=transparent();c.mode=2;c.targetLowHz=new float[]{300,600};c.targetHighHz=new float[]{3000,2600};
        float[][] audio=capture(new HearingDsp(c),6000,0.02);
        double left=dominant(audio[0]),right=dominant(audio[1]);
        check(Math.abs(left-2325)<80,"All-band left mapping peak="+left);
        check(Math.abs(right-2100)<80,"All-band right mapping peak="+right);
        double contained=containedEnergy(audio[0],240,3100);
        check(contained>0.97,"Mapped energy in target band="+contained);
        c.mode=3;
        double ttsPeak=dominant(capture(new HearingDsp(c),6000,0.02)[0]);
        check(Math.abs(ttsPeak-left)<1,"TTS mapping differs from mode2");
        pass("all-band + TTS mapping; distinct channel targets (peaks="+left+", "+right+" Hz; in-band energy="+contained+")");
    }
    private static void highFrequencyMapping() {
        DspConfig c=transparent();c.mode=2;c.mappingType=1;
        double low=dominant(capture(new HearingDsp(c),125,0.02)[0]);
        double high=dominant(capture(new HearingDsp(c),6000,0.02)[0]);
        check(Math.abs(low-125)<20,"Low frequency changed="+low);
        check(high>=300&&high<3100,"High frequency not lowered="+high);
        pass("piecewise frequency lowering preserves bass ("+low+", "+high+" Hz)");
    }
    private static void limiterAndInvalidInput() {
        DspConfig c=new DspConfig(); c.masterGainDb=12; Arrays.fill(c.gainDb[0],24); Arrays.fill(c.gainDb[1],24);
        c.compressionEnabled=false; c.feedbackSuppression=false;
        HearingDsp d=new HearingDsp(c);Random r=new Random(5);float[] in=new float[256];
        double ceiling=Math.pow(10,-18/20.0),peak=0;
        for(int b=0;b<300;b++) {
            for(int i=0;i<256;i++)in[i]=(float)(r.nextGaussian()*3);
            if(b%10==0){in[0]=Float.NaN;in[1]=Float.POSITIVE_INFINITY;in[2]=Float.NEGATIVE_INFINITY;}
            float[] out=d.process(in);for(float v:out){check(Float.isFinite(v),"Nonfinite output");peak=Math.max(peak,Math.abs(v));check(Math.abs(v)<=ceiling+1e-7,"Ceiling exceeded");}
        }
        check(d.getSanitizedInputSamples()==90,"Invalid-sample count");
        Arrays.fill(in,0);for(int b=0;b<100;b++)d.process(in);
        float[] silent=d.process(in);for(float v:silent)check(v==0,"Nonzero after silence");
        pass("hard digital limit and NaN/Inf handling (peak="+peak+" <= "+ceiling+")");
    }
    private static void quietComfortCeilings() {
        for (float limit : new float[]{-60, -70, -81, -90}) {
            DspConfig c = transparent(); c.ceilingDb = limit; c.masterGainDb = 12;
            check(c.copy().ceilingDb == limit, "Quiet comfort limit was raised: " + limit);
            HearingDsp d = new HearingDsp(c); float[] in = new float[256];
            double bound = Math.pow(10, limit / 20.0); double maximum = 0;
            for (int frame = 0; frame < 100; frame++) {
                tone(in, frame, 1000, 0.8);
                for (float value : d.process(in)) {
                    check(Float.isFinite(value), "Nonfinite quiet-ceiling output");
                    maximum = Math.max(maximum, Math.abs(value));
                    check(Math.abs(value) <= bound * 1.000001, "Comfort ceiling exceeded at " + limit);
                }
            }
            check(maximum > 0, "Quiet ceiling unexpectedly silenced output");
        }
        pass("comfort ceilings remain effective down to -90 dBFS, including -78 minus 3 dB margin");
    }
    private static void feedbackToneSuppression() {
        DspConfig c=transparent();c.feedbackSuppression=true;
        HearingDsp d=new HearingDsp(c);double suppressed=rms(d,2000,0.04);
        double original=rms(new HearingDsp(transparent()),2000,0.04);
        double reduction=20*Math.log10(original/suppressed);
        check(reduction>8,"Tonal suppression="+reduction);
        check(d.getFeedbackReductionDb()>10,"Feedback meter");
        pass("persistent narrowband tone suppression ("+reduction+" dB; not full adaptive cancellation)");
    }
    private static void wienerNoiseSuppression() {
        DspConfig c=transparent();c.noiseReduction=true;
        HearingDsp wet=new HearingDsp(c),dry=new HearingDsp(transparent());
        Random random=new Random(7);float[] in=new float[256];double a=0,b=0;
        for(int frame=0;frame<300;frame++) {
            for(int i=0;i<256;i++)in[i]=(float)(random.nextGaussian()*0.02);
            float[] w=wet.process(in),d=dry.process(in);
            if(frame>100)for(int i=0;i<256;i++){a+=w[2*i]*w[2*i];b+=d[2*i]*d[2*i];}
        }
        check(a<b,"Wiener failed to attenuate noise");check(a>b*0.02,"Wiener excessive collapse");
        pass("causal Wiener path attenuates stationary noise ("+(10*Math.log10(a/b))+" dB)");
    }
    private static void thresholdsAndSnapshot() {
        DspConfig c=new DspConfig();float[] t={-60,-50,-40,Float.NaN,-20,10,-55,-30};c.fitRelativeThresholds(t,t);
        check(c.gainDb[0][0]==0&&c.gainDb[0][1]==4.5f&&c.gainDb[0][3]==0&&c.gainDb[0][4]==18&&c.gainDb[0][5]==0,"Relative fit");
        DspConfig s=c.copy();c.gainDb[0][1]=24;check(s.gainDb[0][1]==4.5f,"Deep snapshot");
        c.ceilingDb=Float.NaN;c.masterGainDb=Float.POSITIVE_INFINITY;c.targetLowHz[0]=Float.NaN;c.targetHighHz[0]=-500;
        s=c.copy();check(s.ceilingDb==-18&&s.masterGainDb==0&&s.targetHighHz[0]>s.targetLowHz[0],"Sanitization");
        pass("relative-fit missing data and config sanitization/snapshot");
    }
    private static void runtimeBudget() {
        HearingDsp d=new HearingDsp(new DspConfig());float[] in=new float[256];for(int i=0;i<200;i++)d.process(in);
        long start=System.nanoTime();for(int i=0;i<3000;i++)d.process(in);double millis=(System.nanoTime()-start)/1e6/3000;
        check(millis<16,"Desktop DSP slower than real time="+millis+" ms/hop");
        pass("desktop CPU timing " + millis + " ms/hop (phone performance must be measured)");
    }
    private static double rms(HearingDsp d,double hz,double amplitude) {
        double e=0;long count=0;float[] in=new float[256];
        for(int b=0;b<200;b++){tone(in,b,hz,amplitude);float[] out=d.process(in);if(b>=100)for(int i=0;i<256;i++){e+=out[2*i]*out[2*i];count++;}}
        return Math.sqrt(e/count);
    }
    private static float[][] capture(HearingDsp d,double hz,double amplitude) {
        float[][] audio=new float[2][8192];float[] in=new float[256];
        for(int b=0;b<160;b++){tone(in,b,hz,amplitude);float[] out=d.process(in);if(b>=128)for(int i=0;i<256;i++){audio[0][(b-128)*256+i]=out[2*i];audio[1][(b-128)*256+i]=out[2*i+1];}}
        return audio;
    }
    private static double[] spectrum(float[] data) {
        int n=data.length;double[] re=new double[n],im=new double[n];for(int i=0;i<n;i++)re[i]=data[i]*(0.5-0.5*Math.cos(2*Math.PI*i/n));
        new Fft(n).transform(re,im,false);for(int i=0;i<n/2;i++)re[i]=re[i]*re[i]+im[i]*im[i];return re;
    }
    private static double dominant(float[] data) {
        double[] power=spectrum(data);int best=1;for(int k=2;k<data.length/2;k++)if(power[k]>power[best])best=k;return best*16000.0/data.length;
    }
    private static double containedEnergy(float[] data,double min,double max) {
        double[] p=spectrum(data);double all=0,contained=0;for(int k=1;k<data.length/2;k++){all+=p[k];double hz=k*16000.0/data.length;if(hz>=min&&hz<=max)contained+=p[k];}return contained/all;
    }
    private static void tone(float[] data,int block,double hz,double amp){for(int i=0;i<data.length;i++)data[i]=(float)(amp*Math.sin(2*Math.PI*hz*(block*256+i)/16000.0));}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void pass(String label){passed++;System.out.println("PASS "+label);}
}

