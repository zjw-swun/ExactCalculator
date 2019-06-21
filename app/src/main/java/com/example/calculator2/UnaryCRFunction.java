// Copyright (c) 1999, Silicon Graphics, Inc. -- ALL RIGHTS RESERVED
//
// Permission is granted free of charge to copy, modify, use and distribute
// this software  provided you include the entirety of this notice in all
// copies made.
//
// THIS SOFTWARE IS PROVIDED ON AN AS IS BASIS, WITHOUT WARRANTY OF ANY
// KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, WITHOUT LIMITATION,
// WARRANTIES THAT THE SUBJECT SOFTWARE IS FREE OF DEFECTS, MERCHANTABLE, FIT
// FOR A PARTICULAR PURPOSE OR NON-INFRINGING.   SGI ASSUMES NO RISK AS TO THE
// QUALITY AND PERFORMANCE OF THE SOFTWARE.   SHOULD THE SOFTWARE PROVE
// DEFECTIVE IN ANY RESPECT, SGI ASSUMES NO COST OR LIABILITY FOR ANY
// SERVICING, REPAIR OR CORRECTION.  THIS DISCLAIMER OF WARRANTY CONSTITUTES
// AN ESSENTIAL PART OF THIS LICENSE. NO USE OF ANY SUBJECT SOFTWARE IS
// AUTHORIZED HEREUNDER EXCEPT UNDER THIS DISCLAIMER.
//
// UNDER NO CIRCUMSTANCES AND UNDER NO LEGAL THEORY, WHETHER TORT (INCLUDING,
// WITHOUT LIMITATION, NEGLIGENCE OR STRICT LIABILITY), CONTRACT, OR
// OTHERWISE, SHALL SGI BE LIABLE FOR ANY DIRECT, INDIRECT, SPECIAL,
// INCIDENTAL, OR CONSEQUENTIAL DAMAGES OF ANY CHARACTER WITH RESPECT TO THE
// SOFTWARE INCLUDING, WITHOUT LIMITATION, DAMAGES FOR LOSS OF GOODWILL, WORK
// STOPPAGE, LOSS OF DATA, COMPUTER FAILURE OR MALFUNCTION, OR ANY AND ALL
// OTHER COMMERCIAL DAMAGES OR LOSSES, EVEN IF SGI SHALL HAVE BEEN INFORMED OF
// THE POSSIBILITY OF SUCH DAMAGES.  THIS LIMITATION OF LIABILITY SHALL NOT
// APPLY TO LIABILITY RESULTING FROM SGI's NEGLIGENCE TO THE EXTENT APPLICABLE
// LAW PROHIBITS SUCH LIMITATION.  SOME JURISDICTIONS DO NOT ALLOW THE
// EXCLUSION OR LIMITATION OF INCIDENTAL OR CONSEQUENTIAL DAMAGES, SO THAT
// EXCLUSION AND LIMITATION MAY NOT APPLY TO YOU.
//
// These license terms shall be governed by and construed in accordance with
// the laws of the United States and the State of California as applied to
// agreements entered into and to be performed entirely within California
// between California residents.  Any litigation relating to these license
// terms shall be subject to the exclusive jurisdiction of the Federal Courts
// of the Northern District of California (or, absent subject matter
// jurisdiction in such courts, the courts of the State of California), with
// venue lying exclusively in Santa Clara County, California.
//
// 5/2014 Added Strings to ArithmeticExceptions.
// 5/2015 Added support for direct asin() implementation in CR.

package com.example.calculator2;
// import android.util.Log;

import java.math.BigInteger;

/**
* Unary functions on constructive reals implemented as objects.
* The <TT>execute</tt> member computes the function result.
* Unary function objects on constructive reals inherit from
* <TT>UnaryCRFunction</tt>.
*/
// Naming vaguely follows ObjectSpace JGL convention.
@SuppressWarnings({"unused", "WeakerAccess"})
public abstract class UnaryCRFunction {
    abstract public CR execute(CR x);

/**
* The function object corresponding to the identity function.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction identityFunction =
        new IdentityUnaryCRFunction();

/**
* The function object corresponding to the <TT>negate</tt> method of CR.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction negateFunction =
        new NegateUnaryCRFunction();

/**
* The function object corresponding to the <TT>inverse</tt> method of CR.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction inverseFunction =
        new InverseUnaryCRFunction();

/**
* The function object corresponding to the <TT>abs</tt> method of CR.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction absFunction =
        new AbsUnaryCRFunction();

/**
* The function object corresponding to the <TT>exp</tt> method of CR.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction expFunction =
        new ExpUnaryCRFunction();

/**
* The function object corresponding to the <TT>cos</tt> method of CR.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction cosFunction =
        new CosUnaryCRFunction();

/**
* The function object corresponding to the <TT>sin</tt> method of CR.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction sinFunction =
        new SinUnaryCRFunction();

/**
* The function object corresponding to the tangent function.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction tanFunction =
        new TanUnaryCRFunction();

/**
* The function object corresponding to the inverse sine (arcsine) function.
* The argument must be between -1 and 1 inclusive.  The result is between
* -PI/2 and PI/2.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction asinFunction =
        new AsinUnaryCRFunction();
        // The following also works, but is slower:
        // CR halfPi = CR.PI.divide(CR.valueOf(2));
        // UnaryCRFunction.sinFunction.inverseMonotone(halfPi.negate(),
        //                                             halfPi);

/**
* The function object corresponding to the inverse cosine (arccosine) function.
* The argument must be between -1 and 1 inclusive.  The result is between
* 0 and PI.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction acosFunction =
        new AcosUnaryCRFunction();

/**
* The function object corresponding to the inverse cosine (arctangent) function.
* The result is between -PI/2 and PI/2.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction atanFunction =
        new AtanUnaryCRFunction();

/**
* The function object corresponding to the <TT>ln</tt> method of CR.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction lnFunction =
        new LnUnaryCRFunction();

/**
* The function object corresponding to the <TT>sqrt</tt> method of CR.
*/
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final UnaryCRFunction sqrtFunction =
        new SqrtUnaryCRFunction();

/**
* Compose this function with <TT>f2</tt>.
*/
    public UnaryCRFunction compose(UnaryCRFunction f2) {
        return new ComposeUnaryCRFunction(this, f2);
    }

/**
* Compute the inverse of this function, which must be defined
* and strictly monotone on the interval [<TT>low</tt>, <TT>high</tt>].
* The resulting function is defined only on the image of
* [<TT>low</tt>, <TT>high</tt>].
* The original function may be either increasing or decreasing.
*/
    public UnaryCRFunction inverseMonotone(CR low, CR high) {
        return new InverseMonotoneUnaryCRFunction(this, low, high);
    }

/**
* Compute the derivative of a function.
* The function must be defined on the interval [<TT>low</tt>, <TT>high</tt>],
* and the derivative must exist, and must be continuous and
* monotone in the open interval [<TT>low</tt>, <TT>high</tt>].
* The result is defined only in the open interval.
*/
    public UnaryCRFunction monotoneDerivative(CR low, CR high) {
        return new MonotoneDerivativeUnaryCRFunction(this, low, high);
    }

}

// Subclasses of UnaryCRFunction for various built-in functions.
class SinUnaryCRFunction extends UnaryCRFunction {
    public CR execute(CR x) {
        return x.sin();
    }
}

class CosUnaryCRFunction extends UnaryCRFunction {
    public CR execute(CR x) {
        return x.cos();
    }
}

class TanUnaryCRFunction extends UnaryCRFunction {
    public CR execute(CR x) {
        return x.sin().divide(x.cos());
    }
}

class AsinUnaryCRFunction extends UnaryCRFunction {
    public CR execute(CR x) {
        return x.asin();
    }
}

class AcosUnaryCRFunction extends UnaryCRFunction {
    public CR execute(CR x) {
        return x.acos();
    }
}

// This uses the identity (sin x)^2 = (tan x)^2/(1 + (tan x)^2)
// Since we know the tangent of the result, we can get its sine,
// and then use the asin function.  Note that we don't always
// want the positive square root when computing the sine.
class AtanUnaryCRFunction extends UnaryCRFunction {
    CR one = CR.valueOf(1);
    public CR execute(CR x) {
        CR x2 = x.multiply(x);
        CR absSinAtan = x2.divide(one.add(x2)).sqrt();
        CR sinAtan = x.select(absSinAtan.negate(), absSinAtan);
        return sinAtan.asin();
    }
}

class ExpUnaryCRFunction extends UnaryCRFunction {
    public CR execute(CR x) {
        return x.exp();
    }
}

class LnUnaryCRFunction extends UnaryCRFunction {
    public CR execute(CR x) {
        return x.ln();
    }
}

class IdentityUnaryCRFunction extends UnaryCRFunction {
    public CR execute(CR x) {
        return x;
    }
}

class NegateUnaryCRFunction extends UnaryCRFunction {
    public CR execute(CR x) {
        return x.negate();
    }
}

class InverseUnaryCRFunction extends UnaryCRFunction {
    public CR execute(CR x) {
        return x.inverse();
    }
}

class AbsUnaryCRFunction extends UnaryCRFunction {
    public CR execute(CR x) {
        return x.abs();
    }
}

class SqrtUnaryCRFunction extends UnaryCRFunction {
    public CR execute(CR x) {
        return x.sqrt();
    }
}

@SuppressWarnings("WeakerAccess")
class ComposeUnaryCRFunction extends UnaryCRFunction {
    UnaryCRFunction f1;
    UnaryCRFunction f2;
    ComposeUnaryCRFunction(UnaryCRFunction func1,
                           UnaryCRFunction func2) {
        f1 = func1; f2 = func2;
    }
    public CR execute(CR x) {
        return f1.execute(f2.execute(x));
    }
}

@SuppressWarnings("WeakerAccess")
class InverseMonotoneUnaryCRFunction extends UnaryCRFunction {
    // The following variables are final, so that they
    // can be referenced from the inner class InverseIncreasingCR.
    // I couldn't find a way to initialize these such that the
    // compiler accepted them as final without turning them into arrays.
    final UnaryCRFunction[] f = new UnaryCRFunction[1];
    // Monotone increasing.
    // If it was monotone decreasing, we
    // negate it.
    final boolean[] fNegated = new boolean[1];
    final CR[] low = new CR[1];
    final CR[] high = new CR[1];
    final CR[] fLow = new CR[1];
    final CR[] fHigh = new CR[1];
    final int[] maxMsd = new int[1];
    // Bound on msd of both f(high) and f(low)
    final int[] maxArgPrec = new int[1];
    // base**maxArgPrec is a small fraction
    // of low - high.
    final int[] derivMsd = new int[1];
                                // Rough approx. of msd of first
                                // derivative.
    final static BigInteger BIG1023 = BigInteger.valueOf(1023);
    static final boolean ENABLE_TRACE = false;  // Change to generate trace
    static void trace(String s) {
        if (ENABLE_TRACE) {
            System.out.println(s);
            // Change to Log.v("UnaryCRFunction", s); for Android use.
        }
    }
    InverseMonotoneUnaryCRFunction(UnaryCRFunction func, CR l, CR h) {
        low[0] = l; high[0] = h;
        CR tmpFLow = func.execute(l);
        CR tmpFHigh = func.execute(h);
        // Since func is monotone and low < high, the following test
        // converges.
        if (tmpFLow.compareTo(tmpFHigh) > 0) {
            f[0] = UnaryCRFunction.negateFunction.compose(func);
            fNegated[0] = true;
            fLow[0] = tmpFLow.negate();
            fHigh[0] = tmpFHigh.negate();
        } else {
            f[0] = func;
            //noinspection ConstantConditions
            fNegated[0] = false;
            fLow[0] = tmpFLow;
            fHigh[0] = tmpFHigh;
        }
        maxMsd[0] = low[0].abs().max(high[0].abs()).msd();
        maxArgPrec[0] = high[0].subtract(low[0]).msd() - 4;
        derivMsd[0] = fHigh[0].subtract(fLow[0])
                    .divide(high[0].subtract(low[0])).msd();
    }
    class InverseIncreasingCR extends CR {
        final CR arg;
        InverseIncreasingCR(CR x) {
            arg = fNegated[0]? x.negate() : x;
        }
        // Comparison with a difference of one treated as equality.
        int sloppyCompare(BigInteger x, BigInteger y) {
            BigInteger difference = x.subtract(y);
            if (difference.compareTo(big1) > 0) {
                return 1;
            }
            if (difference.compareTo(bigm1) < 0) {
                return -1;
            }
            return 0;
        }
        protected BigInteger approximate(int p) {
            final int extraArgPrec = 4;
            final UnaryCRFunction fn = f[0];
            int smallStepDeficit = 0; // Number of ineffective steps not
                                        // yet compensated for by a binary
                                        // search step.
            int digitsNeeded = maxMsd[0] - p;
            if (digitsNeeded < 0) return big0;
            int workingArgPrec = p - extraArgPrec;
            if (workingArgPrec > maxArgPrec[0]) {
                workingArgPrec = maxArgPrec[0];
            }
            int workingEvalPrec = workingArgPrec + derivMsd[0] - 20;
                        // initial guess
            // We use a combination of binary search and something like
            // the secant method.  This always converges linearly,
            // and should converge quadratically under favorable assumptions.
            // fL and fH are always the approximate images of l and h.
            // At any point, arg is between fL and fH, or no more than
            // one outside [fL, fH].
            // L and h are implicitly scaled by workingArgPrec.
            // The scaled values of l and h are strictly between low and high.
            // If atLeft is true, then l is logically at the left
            // end of the interval.  We approximate this by setting l to
            // a point slightly inside the interval, and letting fL
            // approximate the function value at the endpoint.
            // If atRight is true, r and fR are set correspondingly.
            // At the endpoints of the interval, fL and fH may correspond
            // to the endpoints, even if l and h are slightly inside.
            // fL and fH are scaled by workingEvalPrec.
            // workingEvalPrec may need to be adjusted depending
            // on the derivative of f.
            boolean atLeft, atRight;
            BigInteger l, fL;
            BigInteger h, fH;
            BigInteger lowAppr = low[0].approxGet(workingArgPrec)
                                        .add(big1);
            BigInteger highAppr = high[0].approxGet(workingArgPrec)
                                          .subtract(big1);
            BigInteger argAppr = arg.approxGet(workingEvalPrec);
            boolean haveGoodAppr = (apprValid && minPrec < maxMsd[0]);
            if (digitsNeeded < 30 && !haveGoodAppr) {
                trace("Setting interval to entire domain");
                h = highAppr;
                fH = fHigh[0].approxGet(workingEvalPrec);
                l = lowAppr;
                fL = fLow[0].approxGet(workingEvalPrec);
                // Check for clear out-of-bounds case.
                // Close cases may fail in other ways.
                  if (fH.compareTo(argAppr.subtract(big1)) < 0
                    || fL.compareTo(argAppr.add(big1)) > 0) {
                    throw new ArithmeticException("inverse(out-of-bounds)");
                  }
                atLeft = true;
                atRight = true;
                smallStepDeficit = 2;        // Start with bin search steps.
            } else {
                int roughPrec = p + digitsNeeded /2;

                if (haveGoodAppr &&
                    (digitsNeeded < 30 || minPrec < p + 3* digitsNeeded /4)) {
                    roughPrec = minPrec;
                }
                BigInteger roughAppr = approxGet(roughPrec);
                trace("Setting interval based on prev. appr");
                trace("prev. prec = " + roughPrec + " appr = " + roughAppr);
                h = roughAppr.add(big1)
                              .shiftLeft(roughPrec - workingArgPrec);
                l = roughAppr.subtract(big1)
                              .shiftLeft(roughPrec - workingArgPrec);
                if (h.compareTo(highAppr) > 0)  {
                    h = highAppr;
                    fH = fHigh[0].approxGet(workingEvalPrec);
                    atRight = true;
                } else {
                    CR hCR = CR.valueOf(h).shiftLeft(workingArgPrec);
                    fH = fn.execute(hCR).approxGet(workingEvalPrec);
                    atRight = false;
                }
                if (l.compareTo(lowAppr) < 0) {
                    l = lowAppr;
                    fL = fLow[0].approxGet(workingEvalPrec);
                    atLeft = true;
                } else {
                    CR lCR = CR.valueOf(l).shiftLeft(workingArgPrec);
                    fL = fn.execute(lCR).approxGet(workingEvalPrec);
                    atLeft = false;
                }
            }
            BigInteger difference = h.subtract(l);
            for(int i = 0;; ++i) {
                if (Thread.interrupted() || pleaseStop)
                    throw new AbortedException();
                trace("***Iteration: " + i);
                trace("Arg prec = " + workingArgPrec
                      + " eval prec = " + workingEvalPrec
                      + " arg appr. = " + argAppr);
                trace("l = " + l); trace("h = " + h);
                trace("f(l) = " + fL); trace("f(h) = " + fH);
                if (difference.compareTo(big6) < 0) {
                    // Answer is less than 1/2 ulp away from h.
                    return scale(h, -extraArgPrec);
                }
                BigInteger fDifference = fH.subtract(fL);
                // Narrow the interval by dividing at a cleverly
                // chosen point (guess) in the middle.
                {
                    BigInteger guess;
                    boolean binaryStep = (smallStepDeficit > 0 || fDifference.signum() == 0);
                    if (binaryStep) {
                        // Do a binary search step to guarantee linear
                        // convergence.
                        trace("binary step");
                        guess = l.add(h).shiftRight(1);
                        --smallStepDeficit;
                    } else {
                      // interpolate.
                      // fDifference is nonzero here.
                      trace("interpolating");
                      BigInteger argDifference = argAppr.subtract(fL);
                      BigInteger t = argDifference.multiply(difference);
                      BigInteger adj = t.divide(fDifference);
                          // tentative adjustment to l to compute guess
                      // If we are within 1/1024 of either end, back off.
                      // This greatly improves the odds of bounding
                      // the answer within the smaller interval.
                      // Note that interpolation will often get us
                      // MUCH closer than this.
                      if (adj.compareTo(difference.shiftRight(10)) < 0) {
                        adj = adj.shiftLeft(8);
                        trace("adjusting left");
                      } else if (adj.compareTo(difference.multiply(BIG1023)
                                                       .shiftRight(10)) > 0){
                        adj = difference.subtract(difference.subtract(adj)
                                                  .shiftLeft(8));
                        trace("adjusting right");
                      }
                      if (adj.signum() <= 0)
                          adj = big2;
                      if (adj.compareTo(difference) >= 0)
                          adj = difference.subtract(big2);
                      guess = (adj.signum() <= 0? l.add(big2) : l.add(adj));
                    }
                    int outcome;
                    BigInteger tweak = big2;
                    BigInteger fGuess;
                    for(boolean adjPrec = false;; adjPrec = !adjPrec) {
                        CR guessCR = CR.valueOf(guess)
                                        .shiftLeft(workingArgPrec);
                        trace("Evaluating at " + guessCR
                              + " with precision " + workingEvalPrec);
                        CR fGuessCR = fn.execute(guessCR);
                        trace("fn value = " + fGuessCR);
                        fGuess = fGuessCR.approxGet(workingEvalPrec);
                        outcome = sloppyCompare(fGuess, argAppr);
                        if (outcome != 0) break;
                        // Alternately increase evaluation precision
                        // and adjust guess slightly.
                        // This should be an unlikely case.
                        if (adjPrec) {
                            // adjust workingEvalPrec to get enough
                            // resolution.
                            int adjustment = -fGuess.bitLength()/4;
                            if (adjustment > -20) adjustment = - 20;
                            CR lCR = CR.valueOf(l).shiftLeft(workingArgPrec);
                            CR hCR = CR.valueOf(h).shiftLeft(workingArgPrec);
                            workingEvalPrec += adjustment;
                            trace("New eval prec = " + workingEvalPrec
                                  + (atLeft ? "(at left)" : "")
                                  + (atRight ? "(at right)" : ""));
                            if (atLeft) {
                                fL = fLow[0].approxGet(workingEvalPrec);
                            } else {
                                fL = fn.execute(lCR)
                                        .approxGet(workingEvalPrec);
                            }
                            if (atRight) {
                                fH = fHigh[0].approxGet(workingEvalPrec);
                            } else {
                                fH = fn.execute(hCR)
                                        .approxGet(workingEvalPrec);
                            }
                            argAppr = arg.approxGet(workingEvalPrec);
                        } else {
                            // guess might be exactly right; tweak it
                            // slightly.
                            trace("tweaking guess");
                            BigInteger newGuess = guess.add(tweak);
                            if (newGuess.compareTo(h) >= 0) {
                                guess = guess.subtract(tweak);
                            } else {
                                guess = newGuess;
                            }
                            // If we keep hitting the right answer, it's
                            // important to alternate which side we move it
                            // to, so that the interval shrinks rapidly.
                            tweak = tweak.negate();
                        }
                    }
                    if (outcome > 0) {
                        h = guess;
                        fH = fGuess;
                        atRight = false;
                    } else {
                        l = guess;
                        fL = fGuess;
                        atLeft = false;
                    }
                    BigInteger newDifference = h.subtract(l);
                    if (!binaryStep) {
                        if (newDifference.compareTo(difference
                                                     .shiftRight(1)) >= 0) {
                            ++smallStepDeficit;
                        } else {
                            --smallStepDeficit;
                        }
                    }
                    difference = newDifference;
                }
            }
        }
    }
    public CR execute(CR x) {
        return new InverseIncreasingCR(x);
    }
}

@SuppressWarnings("WeakerAccess")
class MonotoneDerivativeUnaryCRFunction extends UnaryCRFunction {
    // The following variables are final, so that they
    // can be referenced from the inner class InverseIncreasingCR.
    final UnaryCRFunction[] f = new UnaryCRFunction[1];
    // Monotone increasing.
    // If it was monotone decreasing, we
    // negate it.
    final CR[] low = new CR[1]; // endpoints and midpoint of interval
    final CR[] mid = new CR[1];
    final CR[] high = new CR[1];
    final CR[] fLow = new CR[1]; // Corresponding function values.
    final CR[] fMid = new CR[1];
    final CR[] fHigh = new CR[1];
    final int[] differenceMsd = new int[1];  // msd of interval len.
    final int[] deriv2Msd = new int[1];
                                // Rough approx. of msd of second
                                // derivative.
                                // This is increased to be an appr. bound
                                // on the msd of |(f'(y)-f'(x))/(x-y)|
                                // for any pair of points x and y
                                // we have considered.
                                // It may be better to keep a copy per
                                // derivative value.

    MonotoneDerivativeUnaryCRFunction(UnaryCRFunction func, CR l, CR h) {
        f[0] = func;
        low[0] = l; high[0] = h;
        mid[0] = l.add(h).shiftRight(1);
        fLow[0] = func.execute(l);
        fMid[0] = func.execute(mid[0]);
        fHigh[0] = func.execute(h);
        CR difference = h.subtract(l);
        // compute approximate msd of
        // ((fHigh - fMid) - (fMid - fLow))/(high - low)
        // This should be a very rough appr to the second derivative.
        // We add a little slop to err on the high side, since
        // a low estimate will cause extra iterations.
        CR apprDiff2 = fHigh[0].subtract(fMid[0].shiftLeft(1)).add(fLow[0]);
        differenceMsd[0] = difference.msd();
        deriv2Msd[0] = apprDiff2.msd() - differenceMsd[0] + 4;
    }
    class MonotoneDerivativeCR extends CR {
        CR arg;
        CR fArg;
        int maxDeltaMsd;
        MonotoneDerivativeCR(CR x) {
            arg = x;
            fArg = f[0].execute(x);
            // The following must converge, since arg must be in the
            // open interval.
            CR leftDiff = arg.subtract(low[0]);
            int maxDeltaLeftMsd = leftDiff.msd();
            CR rightDiff = high[0].subtract(arg);
            int maxDeltaRightMsd = rightDiff.msd();
            if (leftDiff.signum() < 0 || rightDiff.signum() < 0) {
                throw new ArithmeticException("fn not monotone");
            }
            maxDeltaMsd = (maxDeltaLeftMsd < maxDeltaRightMsd ?
                    maxDeltaLeftMsd
                                : maxDeltaRightMsd);
        }
        protected BigInteger approximate(int p) {
            final int extraPrec = 4;
            int logDelta = p - deriv2Msd[0];
            // Ensure that we stay within the interval.
              if (logDelta > maxDeltaMsd) logDelta = maxDeltaMsd;
            logDelta -= extraPrec;
            CR delta = ONE.shiftLeft(logDelta);

            CR left = arg.subtract(delta);
            CR right = arg.add(delta);
            CR fLeft = f[0].execute(left);
            CR fRight = f[0].execute(right);
            CR leftDeriv = fArg.subtract(fLeft).shiftRight(logDelta);
            CR rightDeriv = fRight.subtract(fArg).shiftRight(logDelta);
            int evalPrec = p - extraPrec;
            BigInteger apprLeftDeriv = leftDeriv.approxGet(evalPrec);
            BigInteger apprRightDeriv = rightDeriv.approxGet(evalPrec);
            BigInteger derivDifference = apprRightDeriv.subtract(apprLeftDeriv).abs();
            if (derivDifference.compareTo(big8) < 0) {
                return scale(apprLeftDeriv, -extraPrec);
            } else {
                if (Thread.interrupted() || pleaseStop) throw new AbortedException();
                deriv2Msd[0] = evalPrec + derivDifference.bitLength() + 4/*slop*/;
                deriv2Msd[0] -= logDelta;
                return approximate(p);
            }
        }
    }
    public CR execute(CR x) {
        return new MonotoneDerivativeCR(x);
    }
}
