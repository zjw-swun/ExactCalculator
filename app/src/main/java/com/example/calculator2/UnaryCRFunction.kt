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

@file:Suppress("MemberVisibilityCanBePrivate")

package com.example.calculator2

// import android.util.Log;

import java.math.BigInteger

/**
 * Unary functions on constructive reals implemented as objects.
 * The <TT>execute</TT> member computes the function result.
 * Unary function objects on constructive reals inherit from
 * <TT>UnaryCRFunction</TT>.
 */
// Naming vaguely follows ObjectSpace JGL convention.
abstract class UnaryCRFunction {
    /**
     * Computes the [CR] created by applying our [UnaryCRFunction] to [x].
     *
     * @param x The [CR] we are to operate upon.
     * @return The [CR] result of applying our [UnaryCRFunction] to [x].
     */
    abstract fun execute(x: CR): CR

    /**
     * Compose *this* function with [f2]. Produces a [UnaryCRFunction] whose `execute` method calls
     * the `execute` method of *this* on the results of the `execute` method of [f2].
     *
     * @param f2 the [UnaryCRFunction] we are to compose with *this*
     * @return a [UnaryCRFunction] created by composing *this* with [f2].
     */
    fun compose(f2: UnaryCRFunction): UnaryCRFunction {
        return ComposeUnaryCRFunction(this, f2)
    }

    /**
     * Compute the inverse of *this* function, which must be defined and strictly monotone on the
     * interval [[low], [high]]. The resulting function is defined only on the image of
     * [[low], [high]]. The original function may be either increasing or decreasing.
     *
     * @param low lower end of the range we are defined on.
     * @param high higher end of the range we are defined on.
     * @return an [InverseMonotoneUnaryCRFunction] of *this* [UnaryCRFunction].
     */
    @Suppress("unused")
    fun inverseMonotone(low: CR, high: CR): UnaryCRFunction {
        return InverseMonotoneUnaryCRFunction(this, low, high)
    }

    /**
     * Compute the derivative of a function. The function must be defined on the interval
     * [[low], [high]], and the derivative must exist, and must be continuous and monotone
     * in the open interval [[low], [high]]. The result is defined only in the open interval.
     *
     * @param low lower end of the range we are defined on.
     * @param high higher end of the range we are defined on.
     * @return an [MonotoneDerivativeUnaryCRFunction] of *this* [UnaryCRFunction].
     */
    @Suppress("unused")
    fun monotoneDerivative(low: CR, high: CR): UnaryCRFunction {
        return MonotoneDerivativeUnaryCRFunction(this, low, high)
    }

    /**
     * Our static constants.
     */
    companion object {

        /**
         * The function object corresponding to the identity function.
         */
        @Suppress("unused")
        val identityFunction: UnaryCRFunction = IdentityUnaryCRFunction()

        /**
         * The function object corresponding to the <TT>negate</TT> method of CR.
         */
        val negateFunction: UnaryCRFunction = NegateUnaryCRFunction()

        /**
         * The function object corresponding to the <TT>inverse</TT> method of CR.
         */
        @Suppress("unused")
        val inverseFunction: UnaryCRFunction = InverseUnaryCRFunction()

        /**
         * The function object corresponding to the <TT>abs</TT> method of CR.
         */
        @Suppress("unused")
        val absFunction: UnaryCRFunction = AbsUnaryCRFunction()

        /**
         * The function object corresponding to the <TT>exp</TT> method of CR.
         */
        @Suppress("unused")
        val expFunction: UnaryCRFunction = ExpUnaryCRFunction()

        /**
         * The function object corresponding to the <TT>cos</TT> method of CR.
         */
        @Suppress("unused")
        val cosFunction: UnaryCRFunction = CosUnaryCRFunction()

        /**
         * The function object corresponding to the <TT>sin</TT> method of CR.
         */
        @Suppress("unused")
        val sinFunction: UnaryCRFunction = SinUnaryCRFunction()

        /**
         * The function object corresponding to the tangent function.
         */
        @Suppress("unused")
        val tanFunction: UnaryCRFunction = TanUnaryCRFunction()

        /**
         * The function object corresponding to the inverse sine (arcsine) function.
         * The argument must be between -1 and 1 inclusive.  The result is between
         * -PI/2 and PI/2.
         */
        @Suppress("unused")
        val asinFunction: UnaryCRFunction = AsinUnaryCRFunction()
        // The following also works, but is slower:
        // CR halfPi = CR.PI.divide(CR.valueOf(2));
        // UnaryCRFunction.sinFunction.inverseMonotone(halfPi.negate(),
        //                                             halfPi);

        /**
         * The function object corresponding to the inverse cosine (arccosine) function.
         * The argument must be between -1 and 1 inclusive.  The result is between
         * 0 and PI.
         */
        @Suppress("unused")
        val acosFunction: UnaryCRFunction = AcosUnaryCRFunction()

        /**
         * The function object corresponding to the inverse cosine (arctangent) function.
         * The result is between -PI/2 and PI/2.
         */
        val atanFunction: UnaryCRFunction = AtanUnaryCRFunction()

        /**
         * The function object corresponding to the <TT>ln</TT> method of CR.
         */
        @Suppress("unused")
        val lnFunction: UnaryCRFunction = LnUnaryCRFunction()

        /**
         * The function object corresponding to the <TT>sqrt</TT> method of CR.
         */
        @Suppress("unused")
        val sqrtFunction: UnaryCRFunction = SqrtUnaryCRFunction()
    }

}

// Subclasses of UnaryCRFunction for various built-in functions.

/**
 * [UnaryCRFunction] whose `execute` method returns the sin of its argument.
 */
internal class SinUnaryCRFunction : UnaryCRFunction() {
    override fun execute(x: CR): CR {
        return x.sin()
    }
}

/**
 * [UnaryCRFunction] whose `execute` method returns the cos of its argument.
 */
internal class CosUnaryCRFunction : UnaryCRFunction() {
    override fun execute(x: CR): CR {
        return x.cos()
    }
}

/**
 * [UnaryCRFunction] whose `execute` method returns the tan of its argument.
 */
internal class TanUnaryCRFunction : UnaryCRFunction() {
    override fun execute(x: CR): CR {
        return x.sin().divide(x.cos())
    }
}

/**
 * [UnaryCRFunction] whose `execute` method returns the asin of its argument.
 */
internal class AsinUnaryCRFunction : UnaryCRFunction() {
    override fun execute(x: CR): CR {
        return x.asin()
    }
}

/**
 * [UnaryCRFunction] whose `execute` method returns the acos of its argument.
 */
internal class AcosUnaryCRFunction : UnaryCRFunction() {
    override fun execute(x: CR): CR {
        return x.acos()
    }
}

/**
 * This uses the identity (sin x)^2 = (tan x)^2/(1 + (tan x)^2). Since we know the tangent of the
 * result, we can get its sine, and then use the asin function. Note that we don't always want the
 * positive square root when computing the sine.
 */
internal class AtanUnaryCRFunction : UnaryCRFunction() {
    var one: CR = CR.valueOf(1)

    override fun execute(x: CR): CR {
        val x2 = x.multiply(x)
        val absSinAtan = x2.divide(one.add(x2)).sqrt()
        val sinAtan = x.select(absSinAtan.negate(), absSinAtan)
        return sinAtan.asin()
    }
}

/**
 * [UnaryCRFunction] whose `execute` method returns the exp of its argument.
 */
internal class ExpUnaryCRFunction : UnaryCRFunction() {
    override fun execute(x: CR): CR {
        return x.exp()
    }
}

/**
 * [UnaryCRFunction] whose `execute` method returns the ln of its argument.
 */
internal class LnUnaryCRFunction : UnaryCRFunction() {
    override fun execute(x: CR): CR {
        return x.ln()
    }
}

/**
 * [UnaryCRFunction] whose `execute` method returns its argument.
 */
internal class IdentityUnaryCRFunction : UnaryCRFunction() {
    override fun execute(x: CR): CR {
        return x
    }
}

/**
 * [UnaryCRFunction] whose `execute` method returns the negation of its argument.
 */
internal class NegateUnaryCRFunction : UnaryCRFunction() {
    override fun execute(x: CR): CR {
        return x.negate()
    }
}

/**
 * [UnaryCRFunction] whose `execute` method returns the inverse of its argument.
 */
internal class InverseUnaryCRFunction : UnaryCRFunction() {
    override fun execute(x: CR): CR {
        return x.inverse()
    }
}

/**
 * [UnaryCRFunction] whose `execute` method returns the abs of its argument.
 */
internal class AbsUnaryCRFunction : UnaryCRFunction() {
    override fun execute(x: CR): CR {
        return x.abs()
    }
}

/**
 * [UnaryCRFunction] whose `execute` method returns the sqrt of its argument.
 */
internal class SqrtUnaryCRFunction : UnaryCRFunction() {
    override fun execute(x: CR): CR {
        return x.sqrt()
    }
}

/**
 * [UnaryCRFunction] whose `execute` method calls the `execute` method of its [f1] parameter and the
 * value returned by the `execute` method of its [f2] parameter.
 */
internal class ComposeUnaryCRFunction(
        var f1: UnaryCRFunction,
        var f2: UnaryCRFunction
) : UnaryCRFunction() {

    override fun execute(x: CR): CR {
        return f1.execute(f2.execute(x))
    }
}

internal class InverseMonotoneUnaryCRFunction(
        func: UnaryCRFunction,
        l: CR,
        h: CR
) : UnaryCRFunction() {
    // The following variables are final, so that they
    // can be referenced from the inner class InverseIncreasingCR.
    // I couldn't find a way to initialize these such that the
    // compiler accepted them as final without turning them into arrays.
    val f = arrayOfNulls<UnaryCRFunction>(1)
    // Monotone increasing.
    // If it was monotone decreasing, we
    // negate it.
    val fNegated = BooleanArray(1)
    val low = arrayOfNulls<CR>(1)
    val high = arrayOfNulls<CR>(1)
    val fLow = arrayOfNulls<CR>(1)
    val fHigh = arrayOfNulls<CR>(1)
    val maxMsd = IntArray(1)
    // Bound on msd of both f(high) and f(low)
    val maxArgPrec = IntArray(1)
    // base**maxArgPrec is a small fraction
    // of low - high.
    val derivMsd = IntArray(1)

    init {
        low[0] = l
        high[0] = h
        val tmpFLow = func.execute(l)
        val tmpFHigh = func.execute(h)
        // Since func is monotone and low < high, the following test
        // converges.
        @Suppress("ReplaceCallWithBinaryOperator")
        if (tmpFLow.compareTo(tmpFHigh) > 0) {
            f[0] = UnaryCRFunction.negateFunction.compose(func)
            fNegated[0] = true
            fLow[0] = tmpFLow.negate()
            fHigh[0] = tmpFHigh.negate()
        } else {
            f[0] = func

            fNegated[0] = false
            fLow[0] = tmpFLow
            fHigh[0] = tmpFHigh
        }
        maxMsd[0] = low[0]!!.abs().max(high[0]!!.abs()).msd()
        maxArgPrec[0] = high[0]!!.subtract(low[0]).msd() - 4
        derivMsd[0] = fHigh[0]!!.subtract(fLow[0])
                .divide(high[0]!!.subtract(low[0])).msd()
    }

    internal inner class InverseIncreasingCR(x: CR) : CR() {
        override fun toShort(): Short {
            return 0
        }

        override fun toChar(): Char {
            return ' '
        }

        val arg: CR = if (fNegated[0]) x.negate() else x

        // Comparison with a difference of one treated as equality.
        fun sloppyCompare(x: BigInteger, y: BigInteger): Int {
            val difference = x.subtract(y)
            @Suppress("ReplaceCallWithBinaryOperator")
            if (difference.compareTo(big1) > 0) {
                return 1
            }
            @Suppress("ReplaceCallWithBinaryOperator")
            return if (difference.compareTo(bigm1) < 0) {
                -1
            } else 0
        }

        override fun approximate(p: Int): BigInteger {
            val extraArgPrec = 4
            val fn = f[0]
            var smallStepDeficit = 0 // Number of ineffective steps not
            // yet compensated for by a binary
            // search step.
            val digitsNeeded = maxMsd[0] - p
            if (digitsNeeded < 0) return big0
            var workingArgPrec = p - extraArgPrec
            if (workingArgPrec > maxArgPrec[0]) {
                workingArgPrec = maxArgPrec[0]
            }
            var workingEvalPrec = workingArgPrec + derivMsd[0] - 20
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
            var atLeft: Boolean
            var atRight: Boolean
            var l: BigInteger
            var fL: BigInteger
            var h: BigInteger
            var fH: BigInteger
            val lowAppr = low[0]!!.approxGet(workingArgPrec)
                    .add(big1)
            val highAppr = high[0]!!.approxGet(workingArgPrec)
                    .subtract(big1)
            var argAppr = arg.approxGet(workingEvalPrec)
            val haveGoodAppr = apprValid && minPrec < maxMsd[0]
            if (digitsNeeded < 30 && !haveGoodAppr) {
                trace("Setting interval to entire domain")
                h = highAppr
                fH = fHigh[0]!!.approxGet(workingEvalPrec)
                l = lowAppr
                fL = fLow[0]!!.approxGet(workingEvalPrec)
                // Check for clear out-of-bounds case.
                // Close cases may fail in other ways.
                @Suppress("ReplaceCallWithBinaryOperator")
                if (fH.compareTo(argAppr.subtract(big1)) < 0 || fL.compareTo(argAppr.add(big1)) > 0) {
                    throw ArithmeticException("inverse(out-of-bounds)")
                }
                atLeft = true
                atRight = true
                smallStepDeficit = 2        // Start with bin search steps.
            } else {
                var roughPrec = p + digitsNeeded / 2

                if (haveGoodAppr && (digitsNeeded < 30 || minPrec < p + 3 * digitsNeeded / 4)) {
                    roughPrec = minPrec
                }
                val roughAppr = approxGet(roughPrec)
                trace("Setting interval based on prev. appr")
                trace("prev. prec = $roughPrec appr = $roughAppr")
                h = roughAppr.add(big1)
                        .shiftLeft(roughPrec - workingArgPrec)
                l = roughAppr.subtract(big1)
                        .shiftLeft(roughPrec - workingArgPrec)
                @Suppress("ReplaceCallWithBinaryOperator")
                if (h.compareTo(highAppr) > 0) {
                    h = highAppr
                    fH = fHigh[0]!!.approxGet(workingEvalPrec)
                    atRight = true
                } else {
                    val hCR = CR.valueOf(h).shiftLeft(workingArgPrec)
                    fH = fn!!.execute(hCR).approxGet(workingEvalPrec)
                    atRight = false
                }
                @Suppress("ReplaceCallWithBinaryOperator")
                if (l.compareTo(lowAppr) < 0) {
                    l = lowAppr
                    fL = fLow[0]!!.approxGet(workingEvalPrec)
                    atLeft = true
                } else {
                    val lCR = CR.valueOf(l).shiftLeft(workingArgPrec)
                    fL = fn!!.execute(lCR).approxGet(workingEvalPrec)
                    atLeft = false
                }
            }
            var difference = h.subtract(l)
            var i = 0
            while (true) {
                if (Thread.interrupted() || pleaseStop)
                    throw AbortedException()
                trace("***Iteration: $i")
                trace("Arg prec = " + workingArgPrec
                        + " eval prec = " + workingEvalPrec
                        + " arg appr. = " + argAppr)
                trace("l = $l")
                trace("h = $h")
                trace("f(l) = $fL")
                trace("f(h) = $fH")
                @Suppress("ReplaceCallWithBinaryOperator")
                if (difference.compareTo(big6) < 0) {
                    // Answer is less than 1/2 ulp away from h.
                    return scale(h, -extraArgPrec)
                }
                val fDifference = fH.subtract(fL)
                // Narrow the interval by dividing at a cleverly
                // chosen point (guess) in the middle.
                run {
                    var guess: BigInteger
                    val binaryStep = smallStepDeficit > 0 || fDifference.signum() == 0
                    if (binaryStep) {
                        // Do a binary search step to guarantee linear
                        // convergence.
                        trace("binary step")
                        guess = l.add(h).shiftRight(1)
                        --smallStepDeficit
                    } else {
                        // interpolate.
                        // fDifference is nonzero here.
                        trace("interpolating")
                        val argDifference = argAppr.subtract(fL)
                        val t = argDifference.multiply(difference)
                        var adj = t.divide(fDifference)
                        // tentative adjustment to l to compute guess
                        // If we are within 1/1024 of either end, back off.
                        // This greatly improves the odds of bounding
                        // the answer within the smaller interval.
                        // Note that interpolation will often get us
                        // MUCH closer than this.
                        @Suppress("ReplaceCallWithBinaryOperator")
                        if (adj.compareTo(difference.shiftRight(10)) < 0) {
                            adj = adj.shiftLeft(8)
                            trace("adjusting left")
                        } else if (adj.compareTo(difference.multiply(BIG1023)
                                        .shiftRight(10)) > 0) {
                            adj = difference.subtract(difference.subtract(adj)
                                    .shiftLeft(8))
                            trace("adjusting right")
                        }
                        if (adj.signum() <= 0)
                            adj = big2
                        @Suppress("ReplaceCallWithBinaryOperator")
                        if (adj.compareTo(difference) >= 0)
                            adj = difference.subtract(big2)
                        guess = if (adj.signum() <= 0) l.add(big2) else l.add(adj)
                    }
                    var outcome: Int
                    var tweak = big2
                    var fGuess: BigInteger
                    var adjPrec = false
                    while (true) {
                        val guessCR = CR.valueOf(guess)
                                .shiftLeft(workingArgPrec)
                        trace("Evaluating at " + guessCR
                                + " with precision " + workingEvalPrec)
                        val fGuessCR = fn!!.execute(guessCR)
                        trace("fn value = $fGuessCR")
                        fGuess = fGuessCR.approxGet(workingEvalPrec)
                        outcome = sloppyCompare(fGuess, argAppr)
                        if (outcome != 0) break
                        // Alternately increase evaluation precision
                        // and adjust guess slightly.
                        // This should be an unlikely case.
                        if (adjPrec) {
                            // adjust workingEvalPrec to get enough
                            // resolution.
                            var adjustment = -fGuess.bitLength() / 4
                            if (adjustment > -20) adjustment = -20
                            val lCR = CR.valueOf(l).shiftLeft(workingArgPrec)
                            val hCR = CR.valueOf(h).shiftLeft(workingArgPrec)
                            workingEvalPrec += adjustment
                            trace("New eval prec = " + workingEvalPrec
                                    + (if (atLeft) "(at left)" else "")
                                    + if (atRight) "(at right)" else "")
                            fL = if (atLeft) {
                                fLow[0]!!.approxGet(workingEvalPrec)
                            } else {
                                fn.execute(lCR)
                                        .approxGet(workingEvalPrec)
                            }
                            fH = if (atRight) {
                                fHigh[0]!!.approxGet(workingEvalPrec)
                            } else {
                                fn.execute(hCR)
                                        .approxGet(workingEvalPrec)
                            }
                            argAppr = arg.approxGet(workingEvalPrec)
                        } else {
                            // guess might be exactly right; tweak it
                            // slightly.
                            trace("tweaking guess")
                            val newGuess = guess.add(tweak)
                            @Suppress("ReplaceCallWithBinaryOperator")
                            guess = if (newGuess.compareTo(h) >= 0) {
                                guess.subtract(tweak)
                            } else {
                                newGuess
                            }
                            // If we keep hitting the right answer, it's
                            // important to alternate which side we move it
                            // to, so that the interval shrinks rapidly.
                            tweak = tweak.negate()
                        }
                        adjPrec = !adjPrec
                    }
                    if (outcome > 0) {
                        h = guess
                        fH = fGuess
                        atRight = false
                    } else {
                        l = guess
                        fL = fGuess
                        atLeft = false
                    }
                    val newDifference = h.subtract(l)
                    if (!binaryStep) {
                        @Suppress("ReplaceCallWithBinaryOperator")
                        if (newDifference.compareTo(difference
                                        .shiftRight(1)) >= 0) {
                            ++smallStepDeficit
                        } else {
                            --smallStepDeficit
                        }
                    }
                    difference = newDifference
                }
                ++i
            }
        }
    }

    override fun execute(x: CR): CR {
        return InverseIncreasingCR(x)
    }

    companion object {
        // Rough approx. of msd of first
        // derivative.
        val BIG1023: BigInteger = BigInteger.valueOf(1023)
        const val ENABLE_TRACE = false  // Change to generate trace

        fun trace(s: String) {
            @Suppress("ConstantConditionIf")
            if (ENABLE_TRACE) {
                println(s)
                // Change to Log.v("UnaryCRFunction", s); for Android use.
            }
        }
    }
}

internal class MonotoneDerivativeUnaryCRFunction
// Rough approx. of msd of second
// derivative.
// This is increased to be an appr. bound
// on the msd of |(f'(y)-f'(x))/(x-y)|
// for any pair of points x and y
// we have considered.
// It may be better to keep a copy per
// derivative value.

(func: UnaryCRFunction, l: CR, h: CR) : UnaryCRFunction() {
    // The following variables are final, so that they
    // can be referenced from the inner class InverseIncreasingCR.
    val f = arrayOfNulls<UnaryCRFunction>(1)
    // Monotone increasing.
    // If it was monotone decreasing, we
    // negate it.
    val low = arrayOfNulls<CR>(1) // endpoints and midpoint of interval
    val mid = arrayOfNulls<CR>(1)
    val high = arrayOfNulls<CR>(1)
    val fLow = arrayOfNulls<CR>(1) // Corresponding function values.
    val fMid = arrayOfNulls<CR>(1)
    val fHigh = arrayOfNulls<CR>(1)
    val differenceMsd = IntArray(1)  // msd of interval len.
    val deriv2Msd = IntArray(1)

    init {
        f[0] = func
        low[0] = l
        high[0] = h
        mid[0] = l.add(h).shiftRight(1)
        fLow[0] = func.execute(l)
        fMid[0] = func.execute(mid[0]!!)
        fHigh[0] = func.execute(h)
        val difference = h.subtract(l)
        // compute approximate msd of
        // ((fHigh - fMid) - (fMid - fLow))/(high - low)
        // This should be a very rough appr to the second derivative.
        // We add a little slop to err on the high side, since
        // a low estimate will cause extra iterations.
        val apprDiff2 = fHigh[0]!!.subtract(fMid[0]!!.shiftLeft(1)).add(fLow[0])
        differenceMsd[0] = difference.msd()
        deriv2Msd[0] = apprDiff2.msd() - differenceMsd[0] + 4
    }

    internal inner class MonotoneDerivativeCR(var arg: CR) : CR() {
        override fun toShort(): Short {
            return 0
        }

        override fun toChar(): Char {
            return ' '
        }

        var fArg: CR = f[0]!!.execute(arg)
        var maxDeltaMsd: Int = 0

        init {
            // The following must converge, since arg must be in the
            // open interval.
            val leftDiff = arg.subtract(low[0])
            val maxDeltaLeftMsd = leftDiff.msd()
            val rightDiff = high[0]!!.subtract(arg)
            val maxDeltaRightMsd = rightDiff.msd()
            if (leftDiff.signum() < 0 || rightDiff.signum() < 0) {
                throw ArithmeticException("fn not monotone")
            }
            maxDeltaMsd = if (maxDeltaLeftMsd < maxDeltaRightMsd)
                maxDeltaLeftMsd
            else
                maxDeltaRightMsd
        }

        override fun approximate(p: Int): BigInteger {
            val extraPrec = 4
            var logDelta = p - deriv2Msd[0]
            // Ensure that we stay within the interval.
            if (logDelta > maxDeltaMsd) logDelta = maxDeltaMsd
            logDelta -= extraPrec
            val delta = CR.ONE.shiftLeft(logDelta)

            val left = arg.subtract(delta)
            val right = arg.add(delta)
            val fLeft = f[0]!!.execute(left)
            val fRight = f[0]!!.execute(right)
            val leftDeriv = fArg.subtract(fLeft).shiftRight(logDelta)
            val rightDeriv = fRight.subtract(fArg).shiftRight(logDelta)
            val evalPrec = p - extraPrec
            val apprLeftDeriv = leftDeriv.approxGet(evalPrec)
            val apprRightDeriv = rightDeriv.approxGet(evalPrec)
            val derivDifference = apprRightDeriv.subtract(apprLeftDeriv).abs()
            @Suppress("ReplaceCallWithBinaryOperator")
            return if (derivDifference.compareTo(big8) < 0) {
                scale(apprLeftDeriv, -extraPrec)
            } else {
                if (Thread.interrupted() || pleaseStop) throw AbortedException()
                deriv2Msd[0] = evalPrec + derivDifference.bitLength() + 4/*slop*/
                deriv2Msd[0] -= logDelta
                approximate(p)
            }
        }
    }

    override fun execute(x: CR): CR {
        return MonotoneDerivativeCR(x)
    }
}
