/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * The above license covers additions and changes by AOSP authors.
 * The original code is licensed as follows:
 */

/*
 *
 * Copyright (c) 1999, Silicon Graphics, Inc. -- ALL RIGHTS RESERVED
 *
 * Permission is granted free of charge to copy, modify, use and distribute
 * this software  provided you include the entirety of this notice in all
 * copies made.
 *
 * THIS SOFTWARE IS PROVIDED ON AN AS IS BASIS, WITHOUT WARRANTY OF ANY
 * KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, WITHOUT LIMITATION,
 * WARRANTIES THAT THE SUBJECT SOFTWARE IS FREE OF DEFECTS, MERCHANTABLE, FIT
 * FOR A PARTICULAR PURPOSE OR NON-INFRINGING.   SGI ASSUMES NO RISK AS TO THE
 * QUALITY AND PERFORMANCE OF THE SOFTWARE.   SHOULD THE SOFTWARE PROVE
 * DEFECTIVE IN ANY RESPECT, SGI ASSUMES NO COST OR LIABILITY FOR ANY
 * SERVICING, REPAIR OR CORRECTION.  THIS DISCLAIMER OF WARRANTY CONSTITUTES
 * AN ESSENTIAL PART OF THIS LICENSE. NO USE OF ANY SUBJECT SOFTWARE IS
 * AUTHORIZED HEREUNDER EXCEPT UNDER THIS DISCLAIMER.
 *
 * UNDER NO CIRCUMSTANCES AND UNDER NO LEGAL THEORY, WHETHER TORT (INCLUDING,
 * WITHOUT LIMITATION, NEGLIGENCE OR STRICT LIABILITY), CONTRACT, OR
 * OTHERWISE, SHALL SGI BE LIABLE FOR ANY DIRECT, INDIRECT, SPECIAL,
 * INCIDENTAL, OR CONSEQUENTIAL DAMAGES OF ANY CHARACTER WITH RESPECT TO THE
 * SOFTWARE INCLUDING, WITHOUT LIMITATION, DAMAGES FOR LOSS OF GOODWILL, WORK
 * STOPPAGE, LOSS OF DATA, COMPUTER FAILURE OR MALFUNCTION, OR ANY AND ALL
 * OTHER COMMERCIAL DAMAGES OR LOSSES, EVEN IF SGI SHALL HAVE BEEN INFORMED OF
 * THE POSSIBILITY OF SUCH DAMAGES.  THIS LIMITATION OF LIABILITY SHALL NOT
 * APPLY TO LIABILITY RESULTING FROM SGI's NEGLIGENCE TO THE EXTENT APPLICABLE
 * LAW PROHIBITS SUCH LIMITATION.  SOME JURISDICTIONS DO NOT ALLOW THE
 * EXCLUSION OR LIMITATION OF INCIDENTAL OR CONSEQUENTIAL DAMAGES, SO THAT
 * EXCLUSION AND LIMITATION MAY NOT APPLY TO YOU.
 *
 * These license terms shall be governed by and construed in accordance with
 * the laws of the United States and the State of California as applied to
 * agreements entered into and to be performed entirely within California
 * between California residents.  Any litigation relating to these license
 * terms shall be subject to the exclusive jurisdiction of the Federal Courts
 * of the Northern District of California (or, absent subject matter
 * jurisdiction in such courts, the courts of the State of California), with
 * venue lying exclusively in Santa Clara County, California.
 *
 * Copyright (c) 2001-2004, Hewlett-Packard Development Company, L.P.
 *
 * Permission is granted free of charge to copy, modify, use and distribute
 * this software  provided you include the entirety of this notice in all
 * copies made.
 *
 * THIS SOFTWARE IS PROVIDED ON AN AS IS BASIS, WITHOUT WARRANTY OF ANY
 * KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, WITHOUT LIMITATION,
 * WARRANTIES THAT THE SUBJECT SOFTWARE IS FREE OF DEFECTS, MERCHANTABLE, FIT
 * FOR A PARTICULAR PURPOSE OR NON-INFRINGING.   HEWLETT-PACKARD ASSUMES
 * NO RISK AS TO THE QUALITY AND PERFORMANCE OF THE SOFTWARE.
 * SHOULD THE SOFTWARE PROVE DEFECTIVE IN ANY RESPECT,
 * HEWLETT-PACKARD ASSUMES NO COST OR LIABILITY FOR ANY
 * SERVICING, REPAIR OR CORRECTION.  THIS DISCLAIMER OF WARRANTY CONSTITUTES
 * AN ESSENTIAL PART OF THIS LICENSE. NO USE OF ANY SUBJECT SOFTWARE IS
 * AUTHORIZED HEREUNDER EXCEPT UNDER THIS DISCLAIMER.
 *
 * UNDER NO CIRCUMSTANCES AND UNDER NO LEGAL THEORY, WHETHER TORT (INCLUDING,
 * WITHOUT LIMITATION, NEGLIGENCE OR STRICT LIABILITY), CONTRACT, OR
 * OTHERWISE, SHALL HEWLETT-PACKARD BE LIABLE FOR ANY DIRECT, INDIRECT, SPECIAL,
 * INCIDENTAL, OR CONSEQUENTIAL DAMAGES OF ANY CHARACTER WITH RESPECT TO THE
 * SOFTWARE INCLUDING, WITHOUT LIMITATION, DAMAGES FOR LOSS OF GOODWILL, WORK
 * STOPPAGE, LOSS OF DATA, COMPUTER FAILURE OR MALFUNCTION, OR ANY AND ALL
 * OTHER COMMERCIAL DAMAGES OR LOSSES, EVEN IF HEWLETT-PACKARD SHALL
 * HAVE BEEN INFORMED OF THE POSSIBILITY OF SUCH DAMAGES.
 * THIS LIMITATION OF LIABILITY SHALL NOT APPLY TO LIABILITY RESULTING
 * FROM HEWLETT-PACKARD's NEGLIGENCE TO THE EXTENT APPLICABLE
 * LAW PROHIBITS SUCH LIMITATION.  SOME JURISDICTIONS DO NOT ALLOW THE
 * EXCLUSION OR LIMITATION OF INCIDENTAL OR CONSEQUENTIAL DAMAGES, SO THAT
 * EXCLUSION AND LIMITATION MAY NOT APPLY TO YOU.
 *
 *
 * Added valueOf(string, radix), fixed some documentation comments.
 *              Hans_Boehm@hp.com 1/12/2001
 * Fixed a serious typo in InvCR():  For negative arguments it produced
 *              the wrong sign.  This affected the sign of divisions.
 * Added byteValue and fixed some comments.  Hans.Boehm@hp.com 12/17/2002
 * Added toStringFloatRep.      Hans.Boehm@hp.com 4/1/2004
 * Added approxGet() synchronization to allow access from multiple threads
 * hboehm@google.com 4/25/2014
 * Changed cos() pre-scaling to avoid logarithmic depth tree.
 * hboehm@google.com 6/30/2014
 * Added explicit asin() implementation.  Remove one.  Add ZERO and ONE and
 * make them public.  hboehm@google.com 5/21/2015
 * Added Gauss-Legendre PI implementation.  Removed two.
 * hboehm@google.com 4/12/2016
 * Fix shift operation in doubleValue. That produced incorrect values for
 * large negative exponents.
 * Don't negate argument and compute inverse for exp(). That causes severe
 * performance problems for (-huge).exp()
 * hboehm@google.com 8/21/2017
 * Have comparison check for interruption. hboehm@google.com 10/31/2017
 * Fix precision overflow issue in most general compareTo function.
 * Fix a couple of unused variable bugs. Notably selectorSign was
 * accidentally locally re-declared. (This turns out to be safe but useless.)
 * hboehm@google.com 11/20/2018.
 * Fix an exception-safety issue in GlPiCR.approximate.
 * hboehm@google.com 3/3/2019.
*/

package com.example.calculator2;

import java.math.BigInteger;
import java.util.ArrayList;

/**
 * Constructive real numbers, also known as recursive, or computable reals.
 * Each recursive real number is represented as an object that provides an
 * approximation function for the real number.
 * The approximation function guarantees that the generated approximation
 * is accurate to the specified precision.
 * Arithmetic operations on constructive reals produce new such objects;
 * they typically do not perform any real computation.
 * In this sense, arithmetic computations are exact: They produce
 * a description which describes the exact answer, and can be used to
 * later approximate it to arbitrary precision.
 * <p>
 * When approximations are generated, <I>e.g.</i> for output, they are
 * accurate to the requested precision; no cumulative rounding errors
 * are visible.
 * In order to achieve this precision, the approximation function will often
 * need to approximate subexpressions to greater precision than was originally
 * demanded.  Thus the approximation of a constructive real number
 * generated through a complex sequence of operations may eventually require
 * evaluation to very high precision.  This usually makes such computations
 * prohibitively expensive for large numerical problems.
 * But it is perfectly appropriate for use in a desk calculator,
 * for small numerical problems, for the evaluation of expressions
 * computed by a symbolic algebra system, for testing of accuracy claims
 * for floating point code on small inputs, or the like.
 * <p>
 * We expect that the vast majority of uses will ignore the particular
 * implementation, and the member functions <TT>approximate</tt>
 * and <TT>approxGet</tt>.  Such applications will treat <TT>CR</tt> as
 * a conventional numerical type, with an interface modeled on
 * <TT>java.math.BigInteger</tt>.  No subclasses of <TT>CR</tt>
 * will be explicitly mentioned by such a program.
 * <p>
 * All standard arithmetic operations, as well as a few algebraic
 * and transcendental functions are provided.  Constructive reals are
 * immutable; thus all of these operations return a new constructive real.
 * <p>
 * A few uses will require explicit construction of approximation functions.
 * The requires the construction of a subclass of <TT>CR</tt> with
 * an overridden <TT>approximate</tt> function.  Note that <TT>approximate</tt>
 * should only be defined, but never called.  <TT>approxGet</tt>
 * provides the same functionality, but adds the caching necessary to obtain
 * reasonable performance.
 * <p>
 * Any operation may throw <TT>com.hp.creals.AbortedException</tt> if the thread
 * in which it is executing is interrupted.  (<TT>InterruptedException</tt>
 * cannot be used for this purpose, since CR inherits from <TT>Number</tt>.)
 * <p>
 * Any operation may also throw <TT>com.hp.creals.PrecisionOverflowException</tt>
 * If the precision request generated during any sub-calculation overflows
 * a 28-bit integer.  (This should be extremely unlikely, except as an
 * outcome of a division by zero, or other erroneous computation.)
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public abstract class CR extends Number {
    // CR is the basic representation of a number.
    // Abstractly this is a function for computing an approximation
    // plus the current best approximation.
    // We could do without the latter, but that would
    // be atrociously slow.

    /**
     * Indicates a constructive real operation was interrupted.
     * Most constructive real operations may throw such an exception.
     * This is unchecked, since Number methods may not raise checked
     * exceptions.
     */
    public static class AbortedException extends RuntimeException {
        public AbortedException() {
            super();
        }

        @SuppressWarnings("unused")
        public AbortedException(String s) {
            super(s);
        }
    }

    /**
     * Indicates that the number of bits of precision requested by
     * a computation on constructive reals required more than 28 bits,
     * and was thus in danger of overflowing an int.
     * This is likely to be a symptom of a diverging computation,
     * <I>e.g.</i> division by zero.
     */
    @SuppressWarnings("WeakerAccess")
    public static class PrecisionOverflowException extends RuntimeException {
        public PrecisionOverflowException() {
            super();
        }

        @SuppressWarnings("unused")
        public PrecisionOverflowException(String s) {
            super(s);
        }
    }

    // First some frequently used constants, so we don't have to
    // recompute these all over the place.
    static final BigInteger big0 = BigInteger.ZERO;
    static final BigInteger big1 = BigInteger.ONE;
    static final BigInteger bigm1 = BigInteger.valueOf(-1);
    static final BigInteger big2 = BigInteger.valueOf(2);
    static final BigInteger bigm2 = BigInteger.valueOf(-2);
    static final BigInteger big3 = BigInteger.valueOf(3);
    static final BigInteger big6 = BigInteger.valueOf(6);
    static final BigInteger big8 = BigInteger.valueOf(8);
    static final BigInteger big10 = BigInteger.TEN;
    static final BigInteger big750 = BigInteger.valueOf(750);
    static final BigInteger bigm750 = BigInteger.valueOf(-750);

    /**
     * Setting this to true requests that  all computations be aborted by
     * throwing AbortedException.  Must be reset to false before any further
     * computation.  Ideally Thread.interrupt() should be used instead, but
     * that doesn't appear to be consistently supported by browser VMs.
     */
    public volatile static boolean pleaseStop = false;

    /**
     * Must be defined in subclasses of <TT>CR</tt>.
     * Most users can ignore the existence of this method, and will
     * not ever need to define a <TT>CR</tt> subclass.
     * Returns value / 2 ** precision rounded to an integer.
     * The error in the result is strictly < 1.
     * Informally, approximate(n) gives a scaled approximation
     * accurate to 2**n.
     * Implementations may safely assume that precision is
     * at least a factor of 8 away from overflow.
     * Called only with the lock on the <TT>CR</tt> object
     * already held.
     */
    protected abstract BigInteger approximate(int precision);

    /**
     * The smallest precision value with which the above has been called.
     */
    transient int minPrec;
    /**
     * The scaled approximation corresponding to minPrec.
     */
    transient BigInteger maxAppr;
    /**
     * minPrec and maxVal are valid.
     */
    transient boolean apprValid = false;

    // Helper functions
    static int boundLog2(int n) {
        int absN = Math.abs(n);
        return (int) Math.ceil(Math.log((double) (absN + 1)) / Math.log(2.0));
    }

    // Check that a precision is at least a factor of 8 away from
    // overflowing the integer used to hold a precision spec.
    // We generally perform this check early on, and then convince
    // ourselves that none of the operations performed on precisions
    // inside a function can generate an overflow.
    static void checkPrec(int n) {
        int high = n >> 28;
        // if n is not in danger of overflowing, then the 4 high order
        // bits should be identical.  Thus high is either 0 or -1.
        // The rest of this is to test for either of those in a way
        // that should be as cheap as possible.
        int highShifted = n >> 29;
        if (0 != (high ^ highShifted)) {
            throw new PrecisionOverflowException();
        }
    }

    /**
     * The constructive real number corresponding to a
     * <TT>BigInteger</tt>.
     */
    public static CR valueOf(BigInteger n) {
        return new IntCR(n);
    }

    /**
     * The constructive real number corresponding to a
     * Java <TT>int</tt>.
     */
    public static CR valueOf(int n) {
        return valueOf(BigInteger.valueOf(n));
    }

    /**
     * The constructive real number corresponding to a
     * Java <TT>long</tt>.
     */
    public static CR valueOf(long n) {
        return valueOf(BigInteger.valueOf(n));
    }

    /**
     * The constructive real number corresponding to a
     * Java <TT>double</tt>.
     * The result is undefined if argument is infinite or NaN.
     */
    public static CR valueOf(double n) {
        if (Double.isNaN(n)) throw new ArithmeticException("Nan argument");
        if (Double.isInfinite(n)) {
            throw new ArithmeticException("Infinite argument");
        }
        boolean negative = (n < 0.0);
        long bits = Double.doubleToLongBits(Math.abs(n));
        long mantissa = (bits & 0xfffffffffffffL);
        int biasedExp = (int) (bits >> 52);
        int exp = biasedExp - 1075;
        if (biasedExp != 0) {
            mantissa += (1L << 52);
        } else {
            mantissa <<= 1;
        }
        CR result = valueOf(mantissa).shiftLeft(exp);
        if (negative) result = result.negate();
        return result;
    }

    /**
     * The constructive real number corresponding to a
     * Java <TT>float</tt>.
     * The result is undefined if argument is infinite or NaN.
     */
    public static CR valueOf(float n) {
        return valueOf((double) n);
    }

    public static CR ZERO = valueOf(0);
    public static CR ONE = valueOf(1);

    // Multiply k by 2**n.
    static BigInteger shift(BigInteger k, int n) {
        if (n == 0) return k;
        if (n < 0) return k.shiftRight(-n);
        return k.shiftLeft(n);
    }

    // Multiply by 2**n, rounding result
    static BigInteger scale(BigInteger k, int n) {
        if (n >= 0) {
            return k.shiftLeft(n);
        } else {
            BigInteger adjK = shift(k, n + 1).add(big1);
            return adjK.shiftRight(1);
        }
    }

    // Identical to approximate(), but maintain and update cache.

    /**
     * Returns value / 2 ** prec rounded to an integer.
     * The error in the result is strictly < 1.
     * Produces the same answer as <TT>approximate</tt>, but uses and
     * maintains a cached approximation.
     * Normally not overridden, and called only from <TT>approximate</tt>
     * methods in subclasses.  Not needed if the provided operations
     * on constructive reals suffice.
     */
    public synchronized BigInteger approxGet(int precision) {
        checkPrec(precision);
        if (apprValid && precision >= minPrec) {
            return scale(maxAppr, minPrec - precision);
        } else {
            BigInteger result = approximate(precision);
            minPrec = precision;
            maxAppr = result;
            apprValid = true;
            return result;
        }
    }

    // Return the position of the msd.
    // If x.msd() == n then
    // 2**(n-1) < abs(x) < 2**(n+1)
    // This initial version assumes that maxAppr is valid
    // and sufficiently removed from zero
    // that the msd is determined.
    int knownMsd() {
        int firstDigit;
        int length;
        if (maxAppr.signum() >= 0) {
            length = maxAppr.bitLength();
        } else {
            length = maxAppr.negate().bitLength();
        }
        firstDigit = minPrec + length - 1;
        return firstDigit;
    }

    // This version may return Integer.MIN_VALUE if the correct
    // answer is < n.
    int msd(int n) {
        if (!apprValid ||
                maxAppr.compareTo(big1) <= 0
                        && maxAppr.compareTo(bigm1) >= 0) {
            approxGet(n - 1);
            if (maxAppr.abs().compareTo(big1) <= 0) {
                // msd could still be arbitrarily far to the right.
                return Integer.MIN_VALUE;
            }
        }
        return knownMsd();
    }


    // Functionally equivalent, but iteratively evaluates to higher
    // precision.
    int iterMsd(int n) {
        int prec = 0;

        for (; prec > n + 30; prec = (prec * 3) / 2 - 16) {
            int msd = msd(prec);
            if (msd != Integer.MIN_VALUE) return msd;
            checkPrec(prec);
            if (Thread.interrupted() || pleaseStop) {
                throw new AbortedException();
            }
        }
        return msd(n);
    }

    // This version returns a correct answer eventually, except
    // that it loops forever (or throws an exception when the
    // requested precision overflows) if this constructive real is zero.
    int msd() {
        return iterMsd(Integer.MIN_VALUE);
    }

    // A helper function for toString.
    // Generate a String containing n zeroes.
    private static String zeroes(int n) {
        char[] a = new char[n];
        for (int i = 0; i < n; ++i) {
            a[i] = '0';
        }
        return new String(a);
    }

    // Natural log of 2.  Needed for some pre-scaling below.
    // ln(2) = 7ln(10/9) - 2ln(25/24) + 3ln(81/80)
    CR simpleLn() {
        return new PrescaledLnCR(this.subtract(ONE));
    }

    static CR tenNinths = valueOf(10).divide(valueOf(9));
    static CR twentyfiveTwentyfourths = valueOf(25).divide(valueOf(24));
    static CR eightyoneEightyeths = valueOf(81).divide(valueOf(80));
    static CR ln2s1 = valueOf(7).multiply(tenNinths.simpleLn());
    static CR ln2s2 = valueOf(2).multiply(twentyfiveTwentyfourths.simpleLn());
    static CR ln2s3 = valueOf(3).multiply(eightyoneEightyeths.simpleLn());
    static CR ln2 = ln2s1.subtract(ln2s2).add(ln2s3);

    // Atan of integer reciprocal.  Used for atanPI.  Could perhaps be made
    // public.
    static CR atanReciprocal(int n) {
        return new IntegralAtanCR(n);
    }

    // Other constants used for PI computation.
    static CR four = valueOf(4);

    // Public operations.

    /**
     * Return 0 if x = y to within the indicated tolerance,
     * -1 if x < y, and +1 if x > y.  If x and y are indeed
     * equal, it is guaranteed that 0 will be returned.  If
     * they differ by less than the tolerance, anything
     * may happen.  The tolerance allowed is
     * the maximum of (abs(this)+abs(x))*(2**r) and 2**a
     *
     * @param x The other constructive real
     * @param r Relative tolerance in bits
     * @param a Absolute tolerance in bits
     */
    public int compareTo(CR x, int r, int a) {
        int thisMsd = iterMsd(a);
        int xMsd = x.iterMsd(thisMsd > a ? thisMsd : a);
        int maxMsd = (xMsd > thisMsd ? xMsd : thisMsd);
        if (maxMsd == Integer.MIN_VALUE) {
            return 0;
        }
        checkPrec(r);
        int rel = maxMsd + r;
        int absPrec = (rel > a ? rel : a);
        return compareTo(x, absPrec);
    }

    /**
     * Approximate comparison with only an absolute tolerance.
     * Identical to the three argument version, but without a relative
     * tolerance.
     * Result is 0 if both constructive reals are equal, indeterminate
     * if they differ by less than 2**a.
     *
     * @param x The other constructive real
     * @param a Absolute tolerance in bits
     */
    public int compareTo(CR x, int a) {
        int neededPrec = a - 1;
        BigInteger thisAppr = approxGet(neededPrec);
        BigInteger xAppr = x.approxGet(neededPrec);
        int comp1 = thisAppr.compareTo(xAppr.add(big1));
        if (comp1 > 0) return 1;
        int comp2 = thisAppr.compareTo(xAppr.subtract(big1));
        if (comp2 < 0) return -1;
        return 0;
    }

    /**
     * Return -1 if <TT>this &lt; x</tt>, or +1 if <TT>this &gt; x</tt>.
     * Should be called only if <TT>this != x</tt>.
     * If <TT>this == x</tt>, this will not terminate correctly; typically it
     * will run until it exhausts memory.
     * If the two constructive reals may be equal, the two or 3 argument
     * version of compareTo should be used.
     */
    public int compareTo(CR x) {
        for (int a = -20; ; a *= 2) {
            checkPrec(a);
            int result = compareTo(x, a);
            if (0 != result) return result;
            if (Thread.interrupted() || pleaseStop) {
                throw new AbortedException();
            }
        }
    }

    /**
     * Equivalent to <TT>compareTo(CR.valueOf(0), a)</tt>
     */
    public int signum(int a) {
        if (apprValid) {
            int quickTry = maxAppr.signum();
            if (0 != quickTry) return quickTry;
        }
        int neededPrec = a - 1;
        BigInteger thisAppr = approxGet(neededPrec);
        return thisAppr.signum();
    }

    /**
     * Return -1 if negative, +1 if positive.
     * Should be called only if <TT>this != 0</tt>.
     * In the 0 case, this will not terminate correctly; typically it
     * will run until it exhausts memory.
     * If the two constructive reals may be equal, the one or two argument
     * version of signum should be used.
     */
    public int signum() {
        for (int a = -20; ; a *= 2) {
            checkPrec(a);
            int result = signum(a);
            if (0 != result) return result;
            if (Thread.interrupted() || pleaseStop) {
                throw new AbortedException();
            }
        }
    }

    /**
     * Return the constructive real number corresponding to the given
     * textual representation and radix.
     *
     * @param s     [-] digit* [. digit*]
     * @param radix radix of number in our string parameter
     */

    public static CR valueOf(String s, int radix)
            throws NumberFormatException {
        int len = s.length();
        int startPos = 0, pointPos;
        String fraction;
        while (s.charAt(startPos) == ' ') ++startPos;
        while (s.charAt(len - 1) == ' ') --len;
        pointPos = s.indexOf('.', startPos);
        if (pointPos == -1) {
            pointPos = len;
            fraction = "0";
        } else {
            fraction = s.substring(pointPos + 1, len);
        }
        String whole = s.substring(startPos, pointPos);
        BigInteger scaledResult = new BigInteger(whole + fraction, radix);
        BigInteger divisor = BigInteger.valueOf(radix).pow(fraction.length());
        return CR.valueOf(scaledResult).divide(CR.valueOf(divisor));
    }

    /**
     * Return a textual representation accurate to <TT>n</tt> places
     * to the right of the decimal point.  <TT>n</tt> must be non-negative.
     *
     * @param n     Number of digits (>= 0) included to the right of decimal point
     * @param radix Base ( >= 2, <= 16) for the resulting representation.
     */
    public String toString(int n, int radix) {
        CR scaledCR;
        if (16 == radix) {
            scaledCR = shiftLeft(4 * n);
        } else {
            BigInteger scaleFactor = BigInteger.valueOf(radix).pow(n);
            scaledCR = multiply(new IntCR(scaleFactor));
        }
        BigInteger scaledInt = scaledCR.approxGet(0);
        String scaledString = scaledInt.abs().toString(radix);
        String result;
        if (0 == n) {
            result = scaledString;
        } else {
            int len = scaledString.length();
            if (len <= n) {
                // Add sufficient leading zeroes
                String z = zeroes(n + 1 - len);
                scaledString = z + scaledString;
                len = n + 1;
            }
            String whole = scaledString.substring(0, len - n);
            String fraction = scaledString.substring(len - n);
            result = whole + "." + fraction;
        }
        if (scaledInt.signum() < 0) {
            result = "-" + result;
        }
        return result;
    }


    /**
     * Equivalent to <TT>toString(n,10)</tt>
     *
     * @param n Number of digits included to the right of decimal point
     */
    public String toString(int n) {
        return toString(n, 10);
    }

    /**
     * Equivalent to <TT>toString(10, 10)</tt>
     */
    @SuppressWarnings("NullableProblems")
    public String toString() {
        return toString(10);
    }

    static double doubleLog2 = Math.log(2.0);

    /**
     * Return a textual scientific notation representation accurate
     * to <TT>n</tt> places to the right of the decimal point.
     * <TT>n</tt> must be non-negative.  A value smaller than
     * <TT>radix</tt>**-<TT>m</tt> may be displayed as 0.
     * The <TT>mantissa</tt> component of the result is either "0"
     * or exactly <TT>n</tt> digits long.  The <TT>sign</tt>
     * component is zero exactly when the mantissa is "0".
     *
     * @param n     Number of digits (&gt; 0) included to the right of decimal point.
     * @param radix Base ( &ge; 2, &le; 16) for the resulting representation.
     * @param m     Precision used to distinguish number from zero.
     *              Expressed as a power of m.
     */
    public StringFloatRep toStringFloatRep(int n, int radix, int m) {
        if (n <= 0) throw new ArithmeticException("Bad precision argument");
        double log2Radix = Math.log((double) radix) / doubleLog2;
        BigInteger bigRadix = BigInteger.valueOf(radix);
        long longMsdPrec = (long) (log2Radix * (double) m);
        if (longMsdPrec > (long) Integer.MAX_VALUE
                || longMsdPrec < (long) Integer.MIN_VALUE)
            throw new PrecisionOverflowException();
        int msdPrec = (int) longMsdPrec;
        checkPrec(msdPrec);
        int msd = iterMsd(msdPrec - 2);
        if (msd == Integer.MIN_VALUE)
            return new StringFloatRep(0, "0", radix, 0);
        int exponent = (int) Math.ceil((double) msd / log2Radix);
        // Guess for the exponent.  Try to get it usually right.
        int scaleExp = exponent - n;
        CR scale;
        if (scaleExp > 0) {
            scale = CR.valueOf(bigRadix.pow(scaleExp)).inverse();
        } else {
            scale = CR.valueOf(bigRadix.pow(-scaleExp));
        }
        CR scaledRes = multiply(scale);
        BigInteger scaledInt = scaledRes.approxGet(0);
        int sign = scaledInt.signum();
        String scaledString = scaledInt.abs().toString(radix);
        while (scaledString.length() < n) {
            // exponent was too large.  Adjust.
            scaledRes = scaledRes.multiply(CR.valueOf(bigRadix));
            exponent -= 1;
            scaledInt = scaledRes.approxGet(0);
            sign = scaledInt.signum();
            scaledString = scaledInt.abs().toString(radix);
        }
        if (scaledString.length() > n) {
            // exponent was too small.  Adjust by truncating.
            exponent += (scaledString.length() - n);
            scaledString = scaledString.substring(0, n);
        }
        return new StringFloatRep(sign, scaledString, radix, exponent);
    }

    /**
     * Return a BigInteger which differs by less than one from the
     * constructive real.
     */
    public BigInteger BigIntegerValue() {
        return approxGet(0);
    }

    /**
     * Return an int which differs by less than one from the
     * constructive real.  Behavior on overflow is undefined.
     */
    public int intValue() {
        return BigIntegerValue().intValue();
    }

    /**
     * Return an int which differs by less than one from the
     * constructive real.  Behavior on overflow is undefined.
     */
    public byte byteValue() {
        return BigIntegerValue().byteValue();
    }

    /**
     * Return a long which differs by less than one from the
     * constructive real.  Behavior on overflow is undefined.
     */
    public long longValue() {
        return BigIntegerValue().longValue();
    }

    /**
     * Return a double which differs by less than one in the least
     * represented bit from the constructive real.
     * (We're in fact closer to round-to-nearest than that, but we can't and
     * don't promise correct rounding.)
     */
    public double doubleValue() {
        int myMsd = iterMsd(-1080 /* slightly > exp. range */);
        if (Integer.MIN_VALUE == myMsd) return 0.0;
        int neededPrec = myMsd - 60;
        double scaledInt = approxGet(neededPrec).doubleValue();
        boolean mayUnderflow = (neededPrec < -1000);
        long scaledIntRep = Double.doubleToLongBits(scaledInt);
        long expAdj = mayUnderflow ? neededPrec + 96 : neededPrec;
        long origExp = (scaledIntRep >> 52) & 0x7ff;
        if (((origExp + expAdj) & ~0x7ff) != 0) {
            // Original unbiased exponent is > 50. Exp_adj > -1050.
            // Thus this can overflow the 11 bit exponent only if the result
            // itself overflows.
            if (scaledInt < 0.0) {
                return Double.NEGATIVE_INFINITY;
            } else {
                return Double.POSITIVE_INFINITY;
            }
        }
        scaledIntRep += expAdj << 52;
        double result = Double.longBitsToDouble(scaledIntRep);
        if (mayUnderflow) {
            double two48 = (double) (1L << 48);
            return result / two48 / two48;
        } else {
            return result;
        }
    }

    /**
     * Return a float which differs by less than one in the least
     * represented bit from the constructive real.
     */
    public float floatValue() {
        return (float) doubleValue();
        // Note that double-rounding is not a problem here, since we
        // cannot, and do not, guarantee correct rounding.
    }

    /**
     * Add two constructive reals.
     */
    public CR add(CR x) {
        //noinspection SuspiciousNameCombination
        return new AddCR(this, x);
    }

    /**
     * Multiply a constructive real by 2**n.
     *
     * @param n shift count, may be negative
     */
    public CR shiftLeft(int n) {
        checkPrec(n);
        return new ShiftedCR(this, n);
    }

    /**
     * Multiply a constructive real by 2**(-n).
     *
     * @param n shift count, may be negative
     */
    public CR shiftRight(int n) {
        checkPrec(n);
        return new ShiftedCR(this, -n);
    }

    /**
     * Produce a constructive real equivalent to the original, assuming
     * the original was an integer.  Undefined results if the original
     * was not an integer.  Prevents evaluation of digits to the right
     * of the decimal point, and may thus improve performance.
     */
    public CR assumeInt() {
        return new AssumedIntCR(this);
    }

    /**
     * The additive inverse of a constructive real
     */
    public CR negate() {
        return new NegCR(this);
    }

    /**
     * The difference between two constructive reals
     */
    public CR subtract(CR x) {
        return new AddCR(this, x.negate());
    }

    /**
     * The product of two constructive reals
     */
    public CR multiply(CR x) {
        //noinspection SuspiciousNameCombination
        return new MultCR(this, x);
    }

    /**
     * The multiplicative inverse of a constructive real.
     * <TT>x.inverse()</tt> is equivalent to <TT>CR.valueOf(1).divide(x)</tt>.
     */
    public CR inverse() {
        return new InvCR(this);
    }

    /**
     * The quotient of two constructive reals.
     */
    public CR divide(CR x) {
        return new MultCR(this, x.inverse());
    }

    /**
     * The real number <TT>x</tt> if <TT>this</tt> < 0, or <TT>y</tt> otherwise.
     * Requires <TT>x</tt> = <TT>y</tt> if <TT>this</tt> = 0.
     * Since comparisons may diverge, this is often
     * a useful alternative to conditionals.
     */
    public CR select(CR x, CR y) {
        return new SelectCR(this, x, y);
    }

    /**
     * The maximum of two constructive reals.
     */
    public CR max(CR x) {
        return subtract(x).select(x, this);
    }

    /**
     * The minimum of two constructive reals.
     */
    public CR min(CR x) {
        //noinspection SuspiciousNameCombination
        return subtract(x).select(this, x);
    }

    /**
     * The absolute value of a constructive reals.
     * Note that this cannot be written as a conditional.
     */
    public CR abs() {
        return select(negate(), this);
    }

    /**
     * The exponential function, that is e**<TT>this</tt>.
     */
    public CR exp() {
        final int lowPrec = -10;
        BigInteger roughAppr = approxGet(lowPrec);
        // Handle negative arguments directly; negating and computing inverse
        // can be very expensive.
        if (roughAppr.compareTo(big2) > 0 || roughAppr.compareTo(bigm2) < 0) {
            CR squareRoot = shiftRight(1).exp();
            return squareRoot.multiply(squareRoot);
        } else {
            return new PrescaledExpCR(this);
        }
    }

    /**
     * The ratio of a circle's circumference to its diameter.
     */
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static CR PI = new GlPiCR();

    // Our old PI implementation. Keep this around for now to allow checking.
    // This implementation may also be faster for BigInteger implementations
    // that support only quadratic multiplication, but exhibit high performance
    // for small computations.  (The standard Android 6 implementation supports
    // sub-quadratic multiplication, but has high constant overhead.) Many other
    // atan-based formulas are possible, but based on superficial
    // experimentation, this is roughly as good as the more complex formulas.
    public static CR atanPI = four.multiply(four.multiply(atanReciprocal(5))
            .subtract(atanReciprocal(239)));
    // pi/4 = 4*atan(1/5) - atan(1/239)
    static CR halfPi = PI.shiftRight(1);

    /**
     * The trigonometric cosine function.
     */
    public CR cos() {
        BigInteger halfpiMultiples = divide(PI).approxGet(-1);
        BigInteger absHalfpiMultiples = halfpiMultiples.abs();
        if (absHalfpiMultiples.compareTo(big2) >= 0) {
            // Subtract multiples of PI
            BigInteger piMultiples = scale(halfpiMultiples, -1);
            CR adjustment = PI.multiply(CR.valueOf(piMultiples));
            if (piMultiples.and(big1).signum() != 0) {
                return subtract(adjustment).cos().negate();
            } else {
                return subtract(adjustment).cos();
            }
        } else if (approxGet(-1).abs().compareTo(big2) >= 0) {
            // Scale further with double angle formula
            CR cosHalf = shiftRight(1).cos();
            return cosHalf.multiply(cosHalf).shiftLeft(1).subtract(ONE);
        } else {
            return new PrescaledCosCR(this);
        }
    }

    /**
     * The trigonometric sine function.
     */
    public CR sin() {
        return halfPi.subtract(this).cos();
    }

    /**
     * The trigonometric arc (inverse) sine function.
     */
    public CR asin() {
        BigInteger roughAppr = approxGet(-10);
        if (roughAppr.compareTo(big750) /* 1/sqrt(2) + a bit */ > 0) {
            CR newArg = ONE.subtract(multiply(this)).sqrt();
            return newArg.acos();
        } else if (roughAppr.compareTo(bigm750) < 0) {
            return negate().asin().negate();
        } else {
            return new PrescaledAsinCR(this);
        }
    }

    /**
     * The trigonometric arc (inverse) cosine function.
     */
    public CR acos() {
        return halfPi.subtract(asin());
    }

    static final BigInteger LOW_LN_LIMIT = big8; /* sixteenths, i.e. 1/2 */
    static final BigInteger HIGH_LN_LIMIT = BigInteger.valueOf(16 + 8 /* 1.5 */);
    static final BigInteger SCALED_4 = BigInteger.valueOf(4 * 16);

    /**
     * The natural (base e) logarithm.
     */
    public CR ln() {
        final int lowPrec = -4;
        BigInteger roughAppr = approxGet(lowPrec); /* In sixteenths */
        if (roughAppr.compareTo(big0) < 0) {
            throw new ArithmeticException("ln(negative)");
        }
        if (roughAppr.compareTo(LOW_LN_LIMIT) <= 0) {
            return inverse().ln().negate();
        }
        if (roughAppr.compareTo(HIGH_LN_LIMIT) >= 0) {
            if (roughAppr.compareTo(SCALED_4) <= 0) {
                CR quarter = sqrt().sqrt().ln();
                return quarter.shiftLeft(2);
            } else {
                int extraBits = roughAppr.bitLength() - 3;
                CR scaledResult = shiftRight(extraBits).ln();
                return scaledResult.add(CR.valueOf(extraBits).multiply(ln2));
            }
        }
        return simpleLn();
    }

    /**
     * The square root of a constructive real.
     */
    public CR sqrt() {
        return new SqrtCR(this);
    }

}  // end of CR

/**
 *  A specialization of CR for cases in which approximate() calls to increase evaluation precision
 *  are somewhat expensive. If we need to (re)evaluate, we speculatively evaluate to slightly higher
 *  precision, minimizing reevaluations.
 *  <p>
 *  Note that this requires any arguments to be evaluated to higher precision than absolutely
 *  necessary. It can thus potentially result in lots of wasted effort, and should be used
 *  judiciously. This assumes that the order of magnitude of the number is roughly one.
 */
@SuppressWarnings("WeakerAccess")
abstract class SlowCR extends CR {
    static int maxPrec = -64;
    static int precIncr = 32;

    public synchronized BigInteger approxGet(int precision) {
        checkPrec(precision);
        if (apprValid && precision >= minPrec) {
            return scale(maxAppr, minPrec - precision);
        } else {
            //noinspection PointlessBitwiseExpression
            int evalPrec = (precision >= maxPrec ? maxPrec :
                    (precision - precIncr + 1) & ~(precIncr - 1));
            BigInteger result = approximate(evalPrec);
            minPrec = evalPrec;
            maxAppr = result;
            apprValid = true;
            return scale(result, evalPrec - precision);
        }
    }
}

/**
 * Representation of an integer constant. Private.
 */
class IntCR extends CR {
    BigInteger value;

    IntCR(BigInteger n) {
        value = n;
    }

    protected BigInteger approximate(int p) {
        return scale(value, -p);
    }
}

/**
 * Representation of a number that may not have been completely evaluated, but is assumed to be an
 * integer.  Hence we never evaluate beyond the decimal point.
 */
class AssumedIntCR extends CR {
    CR value;

    AssumedIntCR(CR x) {
        value = x;
    }

    protected BigInteger approximate(int p) {
        if (p >= 0) {
            return value.approxGet(p);
        } else {
            return scale(value.approxGet(0), -p);
        }
    }
}

/**
 * Representation of the sum of 2 constructive reals. Private.
 */
@SuppressWarnings("WeakerAccess")
class AddCR extends CR {
    CR op1;
    CR op2;

    AddCR(CR x, CR y) {
        op1 = x;
        op2 = y;
    }

    protected BigInteger approximate(int p) {
        // Args need to be evaluated so that each error is < 1/4 ulp.
        // Rounding error from the cale call is <= 1/2 ulp, so that
        // final error is < 1 ulp.
        return scale(op1.approxGet(p - 2).add(op2.approxGet(p - 2)), -2);
    }
}

/**
 * Representation of a CR multiplied by 2**n
 */
@SuppressWarnings("WeakerAccess")
class ShiftedCR extends CR {
    CR op;
    int count;

    ShiftedCR(CR x, int n) {
        op = x;
        count = n;
    }

    protected BigInteger approximate(int p) {
        return op.approxGet(p - count);
    }
}

/**
 * Representation of the negation of a constructive real. Private.
 */
@SuppressWarnings("WeakerAccess")
class NegCR extends CR {
    CR op;

    NegCR(CR x) {
        op = x;
    }

    protected BigInteger approximate(int p) {
        return op.approxGet(p).negate();
    }
}

/**
 * Representation of: op1 if selector < 0, op2 if selector >= 0. Assumes x = y if s = 0
 */
@SuppressWarnings("WeakerAccess")
class SelectCR extends CR {
    CR selector;
    int selectorSign;
    CR op1;
    CR op2;

    SelectCR(CR s, CR x, CR y) {
        selector = s;
        selectorSign = selector.approxGet(-20).signum();
        op1 = x;
        op2 = y;
    }

    protected BigInteger approximate(int p) {
        if (selectorSign < 0) return op1.approxGet(p);
        if (selectorSign > 0) return op2.approxGet(p);
        BigInteger op1Appr = op1.approxGet(p - 1);
        BigInteger op2Appr = op2.approxGet(p - 1);
        BigInteger diff = op1Appr.subtract(op2Appr).abs();
        if (diff.compareTo(big1) <= 0) {
            // close enough; use either
            return scale(op1Appr, -1);
        }
        // op1 and op2 are different; selector != 0;
        // safe to get sign of selector.
        if (selector.signum() < 0) {
            selectorSign = -1;
            return scale(op1Appr, -1);
        } else {
            selectorSign = 1;
            return scale(op2Appr, -1);
        }
    }
}

/**
 * Representation of the product of 2 constructive reals. Private.
 */
@SuppressWarnings("WeakerAccess")
class MultCR extends CR {
    CR op1;
    CR op2;

    MultCR(CR x, CR y) {
        op1 = x;
        op2 = y;
    }

    protected BigInteger approximate(int p) {
        int halfPrec = (p >> 1) - 1;
        int msdOp1 = op1.msd(halfPrec);
        int msdOp2;

        if (msdOp1 == Integer.MIN_VALUE) {
            msdOp2 = op2.msd(halfPrec);
            if (msdOp2 == Integer.MIN_VALUE) {
                // Product is small enough that zero will do as an
                // approximation.
                return big0;
            } else {
                // Swap them, so the larger operand (in absolute value)
                // is first.
                CR tmp;
                tmp = op1;
                op1 = op2;
                op2 = tmp;
                msdOp1 = msdOp2;
            }
        }
        // msdOp1 is valid at this point.
        int prec2 = p - msdOp1 - 3;    // Precision needed for op2.
        // The appr. error is multiplied by at most
        // 2 ** (msdOp1 + 1)
        // Thus each approximation contributes 1/4 ulp
        // to the rounding error, and the final rounding adds
        // another 1/2 ulp.
        BigInteger appr2 = op2.approxGet(prec2);
        if (appr2.signum() == 0) return big0;
        msdOp2 = op2.knownMsd();
        int prec1 = p - msdOp2 - 3;    // Precision needed for op1.
        BigInteger appr1 = op1.approxGet(prec1);
        int scaleDigits = prec1 + prec2 - p;
        return scale(appr1.multiply(appr2), scaleDigits);
    }
}

/**
 * Representation of the multiplicative inverse of a constructive real. Private.
 * Should use Newton iteration to refine estimates.
 */
@SuppressWarnings("WeakerAccess")
class InvCR extends CR {
    CR op;

    InvCR(CR x) {
        op = x;
    }

    protected BigInteger approximate(int p) {
        int msd = op.msd();
        int invMsd = 1 - msd;
        int digitsNeeded = invMsd - p + 3;
        // Number of SIGNIFICANT digits needed for
        // argument, excl. msd position, which may
        // be fictitious, since msd routine can be
        // off by 1.  Roughly 1 extra digit is
        // needed since the relative error is the
        // same in the argument and result, but
        // this isn't quite the same as the number
        // of significant digits.  Another digit
        // is needed to compensate for slop in the
        // calculation.
        // One further bit is required, since the
        // final rounding introduces a 0.5 ulp
        // error.
        int precNeeded = msd - digitsNeeded;
        int logScaleFactor = -p - precNeeded;
        if (logScaleFactor < 0) return big0;
        BigInteger dividend = big1.shiftLeft(logScaleFactor);
        BigInteger scaledDivisor = op.approxGet(precNeeded);
        BigInteger absScaledDivisor = scaledDivisor.abs();
        BigInteger adjDividend = dividend.add(
                absScaledDivisor.shiftRight(1));
        // Adjustment so that final result is rounded.
        BigInteger result = adjDividend.divide(absScaledDivisor);
        if (scaledDivisor.signum() < 0) {
            return result.negate();
        } else {
            return result;
        }
    }
}

/**
 * Representation of the exponential of a constructive real. Private. Uses a Taylor series expansion.
 * Assumes |x| < 1/2.
 * <p>
 * Note: this is known to be a bad algorithm for floating point. Unfortunately, other alternatives
 * appear to require precomputed information.
 */
@SuppressWarnings("WeakerAccess")
class PrescaledExpCR extends CR {
    CR op;

    PrescaledExpCR(CR x) {
        op = x;
    }

    protected BigInteger approximate(int p) {
        if (p >= 1) return big0;
        int iterationsNeeded = -p / 2 + 2;  // conservative estimate > 0.
        //  Claim: each intermediate term is accurate
        //  to 2*2^calcPrecision.
        //  Total rounding error in series computation is
        //  2*iterationsNeeded*2^calcPrecision,
        //  exclusive of error in op.
        int calcPrecision = p - boundLog2(2 * iterationsNeeded)
                - 4; // for error in op, truncation.
        int opPrec = p - 3;
        BigInteger opAppr = op.approxGet(opPrec);
        // Error in argument results in error of < 3/8 ulp.
        // Sum of term eval. rounding error is < 1/16 ulp.
        // Series truncation error < 1/16 ulp.
        // Final rounding error is <= 1/2 ulp.
        // Thus final error is < 1 ulp.
        BigInteger scaled1 = big1.shiftLeft(-calcPrecision);
        BigInteger currentTerm = scaled1;
        BigInteger currentSum = scaled1;
        int n = 0;
        BigInteger maxTruncError = big1.shiftLeft(p - 4 - calcPrecision);
        while (currentTerm.abs().compareTo(maxTruncError) >= 0) {
            if (Thread.interrupted() || pleaseStop) throw new AbortedException();
            n += 1;
            /* currentTerm = currentTerm * op / n */
            currentTerm = scale(currentTerm.multiply(opAppr), opPrec);
            currentTerm = currentTerm.divide(BigInteger.valueOf(n));
            currentSum = currentSum.add(currentTerm);
        }
        return scale(currentSum, calcPrecision - p);
    }
}

/**
 * Representation of the cosine of a constructive real. Private. Uses a Taylor series expansion.
 * Assumes |x| < 1.
 */
@SuppressWarnings("WeakerAccess")
class PrescaledCosCR extends SlowCR {
    CR op;

    PrescaledCosCR(CR x) {
        op = x;
    }

    protected BigInteger approximate(int p) {
        if (p >= 1) return big0;
        int iterationsNeeded = -p / 2 + 4;  // conservative estimate > 0.
        //  Claim: each intermediate term is accurate
        //  to 2*2^calcPrecision.
        //  Total rounding error in series computation is
        //  2*iterationsNeeded*2^calcPrecision,
        //  exclusive of error in op.
        int calcPrecision = p - boundLog2(2 * iterationsNeeded) - 4; // for error in op, truncation.
        int opPrec = p - 2;
        BigInteger opAppr = op.approxGet(opPrec);
        // Error in argument results in error of < 1/4 ulp.
        // Cumulative arithmetic rounding error is < 1/16 ulp.
        // Series truncation error < 1/16 ulp.
        // Final rounding error is <= 1/2 ulp.
        // Thus final error is < 1 ulp.
        BigInteger currentTerm;
        int n;
        BigInteger maxTruncError = big1.shiftLeft(p - 4 - calcPrecision);
        n = 0;
        currentTerm = big1.shiftLeft(-calcPrecision);
        BigInteger currentSum = currentTerm;
        while (currentTerm.abs().compareTo(maxTruncError) >= 0) {
            if (Thread.interrupted() || pleaseStop) throw new AbortedException();
            n += 2;
            /* currentTerm = - currentTerm * op * op / n * (n - 1)   */
            currentTerm = scale(currentTerm.multiply(opAppr), opPrec);
            currentTerm = scale(currentTerm.multiply(opAppr), opPrec);
            BigInteger divisor = BigInteger.valueOf(-n).multiply(BigInteger.valueOf(n - 1));
            currentTerm = currentTerm.divide(divisor);
            currentSum = currentSum.add(currentTerm);
        }
        return scale(currentSum, calcPrecision - p);
    }
}

/**
 * The constructive real atan(1/n), where n is a small integer > base.
 * This gives a simple and moderately fast way to compute PI.
 */
@SuppressWarnings("WeakerAccess")
class IntegralAtanCR extends SlowCR {
    int op;

    IntegralAtanCR(int x) {
        op = x;
    }

    protected BigInteger approximate(int p) {
        if (p >= 1) return big0;
        int iterationsNeeded = -p / 2 + 2;  // conservative estimate > 0.
        //  Claim: each intermediate term is accurate
        //  to 2*base^calcPrecision.
        //  Total rounding error in series computation is
        //  2*iterationsNeeded*base^calcPrecision,
        //  exclusive of error in op.
        int calcPrecision = p - boundLog2(2 * iterationsNeeded)
                - 2; // for error in op, truncation.
        // Error in argument results in error of < 3/8 ulp.
        // Cumulative arithmetic rounding error is < 1/4 ulp.
        // Series truncation error < 1/4 ulp.
        // Final rounding error is <= 1/2 ulp.
        // Thus final error is < 1 ulp.
        BigInteger scaled1 = big1.shiftLeft(-calcPrecision);
        BigInteger bigOp = BigInteger.valueOf(op);
        BigInteger bigOpSquared = BigInteger.valueOf(op * op);
        BigInteger opInverse = scaled1.divide(bigOp);
        BigInteger currentPower = opInverse;
        BigInteger currentTerm = opInverse;
        BigInteger currentSum = opInverse;
        int currentSign = 1;
        int n = 1;
        BigInteger maxTruncError = big1.shiftLeft(p - 2 - calcPrecision);
        while (currentTerm.abs().compareTo(maxTruncError) >= 0) {
            if (Thread.interrupted() || pleaseStop) throw new AbortedException();
            n += 2;
            currentPower = currentPower.divide(bigOpSquared);
            currentSign = -currentSign;
            currentTerm = currentPower.divide(BigInteger.valueOf(currentSign * n));
            currentSum = currentSum.add(currentTerm);
        }
        return scale(currentSum, calcPrecision - p);
    }
}

/**
 * Representation for ln(1 + op)
 */
@SuppressWarnings("WeakerAccess")
class PrescaledLnCR extends SlowCR {
    CR op;

    PrescaledLnCR(CR x) {
        op = x;
    }

    // Compute an approximation of ln(1+x) to precision
    // prec. This assumes |x| < 1/2.
    // It uses a Taylor series expansion.
    // Unfortunately there appears to be no way to take
    // advantage of old information.
    // Note: this is known to be a bad algorithm for
    // floating point.  Unfortunately, other alternatives
    // appear to require precomputed tabular information.
    protected BigInteger approximate(int p) {
        if (p >= 0) return big0;
        int iterationsNeeded = -p;  // conservative estimate > 0.
        //  Claim: each intermediate term is accurate
        //  to 2*2^calcPrecision.  Total error is
        //  2*iterationsNeeded*2^calcPrecision
        //  exclusive of error in op.
        int calcPrecision = p - boundLog2(2 * iterationsNeeded) - 4; // for error in op, truncation.
        int opPrec = p - 3;
        BigInteger opAppr = op.approxGet(opPrec);
        // Error analysis as for exponential.
        BigInteger xNth = scale(opAppr, opPrec - calcPrecision);
        BigInteger currentTerm = xNth;  // x**n
        BigInteger currentSum = currentTerm;
        int n = 1;
        int currentSign = 1;   // (-1)^(n-1)
        BigInteger maxTruncError = big1.shiftLeft(p - 4 - calcPrecision);
        while (currentTerm.abs().compareTo(maxTruncError) >= 0) {
            if (Thread.interrupted() || pleaseStop) throw new AbortedException();
            n += 1;
            currentSign = -currentSign;
            xNth = scale(xNth.multiply(opAppr), opPrec);
            currentTerm = xNth.divide(BigInteger.valueOf(n * currentSign));
            // x**n / (n * (-1)**(n-1))
            currentSum = currentSum.add(currentTerm);
        }
        return scale(currentSum, calcPrecision - p);
    }
}

/**
 * Representation of the arcsine of a constructive real. Private. Uses a Taylor series expansion.
 * Assumes |x| < (1/2)^(1/3).
 */
@SuppressWarnings("WeakerAccess")
class PrescaledAsinCR extends SlowCR {
    CR op;

    PrescaledAsinCR(CR x) {
        op = x;
    }

    protected BigInteger approximate(int p) {
        // The Taylor series is the sum of x^(2n+1) * (2n)!/(4^n n!^2 (2n+1))
        // Note that (2n)!/(4^n n!^2) is always less than one.
        // (The denominator is effectively 2n*2n*(2n-2)*(2n-2)*...*2*2
        // which is clearly > (2n)!)
        // Thus all terms are bounded by x^(2n+1).
        // Unfortunately, there's no easy way to prescale the argument
        // to less than 1/sqrt(2), and we can only approximate that.
        // Thus the worst case iteration count is fairly high.
        // But it doesn't make much difference.
        if (p >= 2) return big0;  // Never bigger than 4.
        int iterationsNeeded = -3 * p / 2 + 4;
        // conservative estimate > 0.
        // Follows from assumed bound on x and
        // the fact that only every other Taylor
        // Series term is present.
        //  Claim: each intermediate term is accurate
        //  to 2*2^calcPrecision.
        //  Total rounding error in series computation is
        //  2*iterationsNeeded*2^calcPrecision,
        //  exclusive of error in op.
        int calcPrecision = p - boundLog2(2 * iterationsNeeded) - 4; // for error in op, truncation.
        int opPrec = p - 3;  // always <= -2
        BigInteger opAppr = op.approxGet(opPrec);
        // Error in argument results in error of < 1/4 ulp.
        // (Derivative is bounded by 2 in the specified range and we use
        // 3 extra digits.)
        // Ignoring the argument error, each term has an error of
        // < 3ulps relative to calcPrecision, which is more precise than p.
        // Cumulative arithmetic rounding error is < 3/16 ulp (relative to p).
        // Series truncation error < 2/16 ulp.  (Each computed term
        // is at most 2/3 of last one, so some of remaining series <
        // 3/2 * current term.)
        // Final rounding error is <= 1/2 ulp.
        // Thus final error is < 1 ulp (relative to p).
        BigInteger maxLastTerm = big1.shiftLeft(p - 4 - calcPrecision);
        int exp = 1; // Current exponent, = 2n+1 in above expression
        BigInteger currentTerm = opAppr.shiftLeft(opPrec - calcPrecision);
        BigInteger currentSum = currentTerm;
        BigInteger currentFactor = currentTerm;
        // Current scaled Taylor series term
        // before division by the exponent.
        // Accurate to 3 ulp at calcPrecision.
        while (currentTerm.abs().compareTo(maxLastTerm) >= 0) {
            if (Thread.interrupted() || pleaseStop) throw new AbortedException();
            exp += 2;
            // currentFactor = currentFactor * op * op * (exp-1) * (exp-2) /
            // (exp-1) * (exp-1), with the two exp-1 factors cancelling,
            // giving
            // currentFactor = currentFactor * op * op * (exp-2) / (exp-1)
            // Thus the error any in the previous term is multiplied by
            // op^2, adding an error of < (1/2)^(2/3) < 2/3 the original
            // error.
            currentFactor = currentFactor.multiply(BigInteger.valueOf(exp - 2));
            currentFactor = scale(currentFactor.multiply(opAppr), opPrec + 2);
            // Carry 2 extra bits of precision forward; thus
            // this effectively introduces 1/8 ulp error.
            currentFactor = currentFactor.multiply(opAppr);
            BigInteger divisor = BigInteger.valueOf(exp - 1);
            currentFactor = currentFactor.divide(divisor);
            // Another 1/4 ulp error here.
            currentFactor = scale(currentFactor, opPrec - 2);
            // Remove extra 2 bits.  1/2 ulp rounding error.
            // currentFactor has original 3 ulp rounding error, which we
            // reduced by 1, plus < 1 ulp new rounding error.
            currentTerm = currentFactor.divide(BigInteger.valueOf(exp));
            // Contributes 1 ulp error to sum plus at most 3 ulp
            // from currentFactor.
            currentSum = currentSum.add(currentTerm);
        }
        return scale(currentSum, calcPrecision - p);
    }
}


@SuppressWarnings("WeakerAccess")
class SqrtCR extends CR {
    CR op;

    SqrtCR(CR x) {
        op = x;
    }

    // Explicitly provide an initial approximation.
    // Useful for arithmetic geometric mean algorithms, where we've previously
    // computed a very similar square root.
    SqrtCR(CR x, int minP, BigInteger maxA) {
        op = x;
        minPrec = minP;
        maxAppr = maxA;
        apprValid = true;
    }

    final int fpPrec = 50;     // Conservative estimate of number of
    // significant bits in double precision
    // computation.
    final int fpOpPrec = 60;

    protected BigInteger approximate(int p) {
        int maxOpPrecNeeded = 2 * p - 1;
        int msd = op.iterMsd(maxOpPrecNeeded);
        if (msd <= maxOpPrecNeeded) return big0;
        int resultMsd = msd / 2;                 // +- 1
        int resultDigits = resultMsd - p;     // +- 2
        if (resultDigits > fpPrec) {
            // Compute less precise approximation and use a Newton iter.
            int apprDigits = resultDigits / 2 + 6;
            // This should be conservative.  Is fewer enough?
            int apprPrec = resultMsd - apprDigits;
            int prodPrec = 2 * apprPrec;
            // First compute the argument to maximal precision, so we don't end up
            // reevaluating it incrementally.
            BigInteger opAppr = op.approxGet(prodPrec);
            BigInteger lastAppr = approxGet(apprPrec);
            // Compute (lastAppr * lastAppr + opAppr) / lastAppr / 2
            // while adjusting the scaling to make everything work
            BigInteger prodPrecScaledNumerator = lastAppr.multiply(lastAppr).add(opAppr);
            BigInteger scaledNumerator = scale(prodPrecScaledNumerator, apprPrec - p);
            BigInteger shiftedResult = scaledNumerator.divide(lastAppr);
            return shiftedResult.add(big1).shiftRight(1);
        } else {
            // Use a double precision floating point approximation.
            // Make sure all precisions are even
            int opPrec = (msd - fpOpPrec) & ~1;
            int workingPrec = opPrec - fpOpPrec;
            BigInteger scaledBiAppr = op.approxGet(opPrec).shiftLeft(fpOpPrec);
            double scaledAppr = scaledBiAppr.doubleValue();
            if (scaledAppr < 0.0)
                throw new ArithmeticException("sqrt(negative)");
            double scaledFpSqrt = Math.sqrt(scaledAppr);
            BigInteger scaledSqrt = BigInteger.valueOf((long) scaledFpSqrt);
            int shiftCount = workingPrec / 2 - p;
            return shift(scaledSqrt, shiftCount);
        }
    }
}

/**
 * The constant PI, computed using the Gauss-Legendre alternating arithmetic-geometric
 * mean algorithm:
 *         a[0] = 1
 *         b[0] = 1/sqrt(2)
 *         t[0] = 1/4
 *         p[0] = 1
 *
 *         a[n+1] = (a[n] + b[n])/2        (arithmetic mean, between 0.8 and 1)
 *         b[n+1] = sqrt(a[n] * b[n])      (geometric mean, between 0.7 and 1)
 *         t[n+1] = t[n] - (2^n)(a[n]-a[n+1])^2,  (always between 0.2 and 0.25)
 *
 *         pi is then approximated as (a[n+1]+b[n+1])^2 / 4*t[n+1].
 */
@SuppressWarnings("WeakerAccess")
class GlPiCR extends SlowCR {
    // In addition to the best approximation kept by the CR base class, we keep
    // the entire sequence b[n], to the extent we've needed it so far.  Each
    // reevaluation leads to slightly different sqrt arguments, but the
    // previous result can be used to avoid repeating low precision Newton
    // iterations for the sqrt approximation.
    ArrayList<Integer> bPrec = new ArrayList<>();
    ArrayList<BigInteger> bVal = new ArrayList<>();

    GlPiCR() {
        bPrec.add(null);  // Zeroth entry unused.
        bVal.add(null);
    }

    private static BigInteger TOLERANCE = BigInteger.valueOf(4);
    // sqrt(1/2)
    private static CR SQRT_HALF = new SqrtCR(ONE.shiftRight(1));

    protected BigInteger approximate(int p) {
        // Get us back into a consistent state if the last computation
        // was interrupted after pushing onto bPrec.
        if (bPrec.size() > bVal.size()) {
            bPrec.remove(bPrec.size() - 1);
        }
        // Rough approximations are easy.
        if (p >= 0) return scale(BigInteger.valueOf(3), -p);
        // We need roughly log2(p) iterations.  Each iteration should
        // contribute no more than 2 ulps to the error in the corresponding
        // term (a[n], b[n], or t[n]).  Thus 2log2(n) bits plus a few for the
        // final calculation and rounding suffice.
        final int extraEvalPrec = (int) Math.ceil(Math.log(-p) / Math.log(2)) + 10;
        // All our terms are implicitly scaled by evalPrec.
        final int evalPrec = p - extraEvalPrec;
        BigInteger a = BigInteger.ONE.shiftLeft(-evalPrec);
        BigInteger b = SQRT_HALF.approxGet(evalPrec);
        BigInteger t = BigInteger.ONE.shiftLeft(-evalPrec - 2);
        int n = 0;
        while (a.subtract(b).subtract(TOLERANCE).signum() > 0) {
            // Current values correspond to n, next* values to n + 1
            // bPrec.size() == bVal.size() >= n + 1
            final BigInteger nextA = a.add(b).shiftRight(1);
            final BigInteger nextB;
            final BigInteger aDiff = a.subtract(nextA);
            final BigInteger bProd = a.multiply(b).shiftRight(-evalPrec);
            // We compute square root approximations using a nested
            // temporary CR computation, to avoid implementing BigInteger
            // square roots separately.
            final CR bProdAsCR = CR.valueOf(bProd).shiftRight(-evalPrec);
            if (bPrec.size() == n + 1) {
                // Add an n+1st slot.
                // Take care to make this exception-safe; bPrec and bVal
                // must remain consistent, even if we are interrupted, or run
                // out of memory. It's OK to just push on bPrec in that case.
                final CR nextBAsCR = bProdAsCR.sqrt();
                nextB = nextBAsCR.approxGet(evalPrec);
                final BigInteger scaledNextB = scale(nextB, -extraEvalPrec);
                bPrec.add(p);
                bVal.add(scaledNextB);
            } else {
                // Reuse previous approximation to reduce sqrt iterations,
                // hopefully to one.
                final CR nextBAsCR = new SqrtCR(bProdAsCR, bPrec.get(n + 1), bVal.get(n + 1));
                nextB = nextBAsCR.approxGet(evalPrec);
                // We assume that set() doesn't throw for any reason.
                bPrec.set(n + 1, p);
                bVal.set(n + 1, scale(nextB, -extraEvalPrec));
            }
            // bPrec.size() == bVal.size() >= n + 2
            final BigInteger nextT = t.subtract(aDiff.multiply(aDiff).shiftLeft(n + evalPrec));  // shift dist. usually neg.
            a = nextA;
            b = nextB;
            t = nextT;
            ++n;
        }
        final BigInteger sum = a.add(b);
        final BigInteger result = sum.multiply(sum).divide(t).shiftRight(2);
        return scale(result, -extraEvalPrec);
    }
}
