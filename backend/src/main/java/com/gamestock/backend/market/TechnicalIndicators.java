package com.gamestock.backend.market;

import java.util.Arrays;

/** Linear-time, causal series. NaN means insufficient history; API encodes it as null. */
final class TechnicalIndicators {
    private TechnicalIndicators() {}
    static double[] empty(int n) {double[] a=new double[n];Arrays.fill(a,Double.NaN);return a;}
    static void period(int p) {if(p<2 || p>500) throw new IllegalArgumentException("period는 2~500입니다.");}
    static double[] sma(double[] x,int p) {
        period(p);double[] out=empty(x.length);double sum=0;int missing=0;
        for(int i=0;i<x.length;i++) {
            if(Double.isFinite(x[i])) sum+=x[i]; else missing++;
            if(i>=p) {if(Double.isFinite(x[i-p])) sum-=x[i-p];else missing--;}
            if(i>=p-1 && missing==0) out[i]=sum/p;
        }return out;
    }
    static double[] ema(double[] x,int p) {
        period(p);double[] out=empty(x.length);double sum=0,previous=Double.NaN;int count=0;
        for(int i=0;i<x.length;i++) {
            if(!Double.isFinite(x[i])) {sum=0;count=0;previous=Double.NaN;continue;}
            if(count<p) {sum+=x[i];count++;if(count==p) previous=sum/p;}
            else previous+=(x[i]-previous)*2/(p+1);
            out[i]=previous;
        }return out;
    }
    static double[] rsi(double[] x,int p) {
        period(p);double[] out=empty(x.length);double gain=0,loss=0;int count=0;
        for(int i=1;i<x.length;i++) {
            if(!Double.isFinite(x[i]) || !Double.isFinite(x[i-1])) {gain=loss=0;count=0;continue;}
            double diff=x[i]-x[i-1],g=Math.max(0,diff),l=Math.max(0,-diff);
            if(count<p) {gain+=g;loss+=l;count++;if(count==p) {gain/=p;loss/=p;}}
            else {gain=(gain*(p-1)+g)/p;loss=(loss*(p-1)+l)/p;}
            if(count>=p) out[i]=loss==0?(gain==0?50:100):100-100/(1+gain/loss);
        }return out;
    }
    static double[] stddev(double[] x,int p) {
        period(p);double[] out=empty(x.length);double mean=0,m2=0;int count=0;
        // Sliding Welford avoids subtracting two very large price-squared totals.
        for(int i=0;i<x.length;i++) {
            if(i>=p && Double.isFinite(x[i-p])) {
                double old=x[i-p];if(count==1) {count=0;mean=m2=0;}
                else {double next=(count*mean-old)/(count-1);m2-=(old-mean)*(old-next);mean=next;count--;}
            }
            if(Double.isFinite(x[i])) {count++;double delta=x[i]-mean;mean+=delta/count;m2+=delta*(x[i]-mean);}
            if(i>=p-1 && count==p) out[i]=Math.sqrt(Math.max(0,m2/p));
        }return out;
    }
    static double[] atr(double[] high,double[] low,double[] close,int p) {
        period(p);double[] out=empty(close.length);double sum=0,previous=0;
        for(int i=0;i<close.length;i++) {
            double tr=high[i]-low[i];if(i>0) tr=Math.max(tr,Math.max(Math.abs(high[i]-close[i-1]),Math.abs(low[i]-close[i-1])));
            if(i<p) {sum+=tr;if(i==p-1) out[i]=previous=sum/p;}
            else out[i]=previous=(previous*(p-1)+tr)/p;
        }return out;
    }
}
