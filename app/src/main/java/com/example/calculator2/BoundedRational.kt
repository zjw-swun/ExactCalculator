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

package com.example.calculator2

import java.math.BigInteger
import java.util.Objects
import java.util.Random

/**
 * Rational numbers that may turn to null if they get too big.
 * For many operations, if the length of the numerator plus the length of the denominator exceeds
 * a maximum size, we simply return null, and rely on our caller do something else.
 * We currently never return null for a pure integer or for a BoundedRational that has just been
 * constructed.
 *
 * We also implement a number of irrational functions.  These return a non-null result only when
 * the result is known to be rational.
 * TODO: Consider returning null for integers. With some care, large factorials might become much faster.
 * TODO: Maybe eventually make this extend Number?
 */
class BoundedRational {

    /**
     * Numerator of our rational number
     */
    private val mNum: BigInteger
    /**
     * Denominator of our rational number
     */
    private val mDen: BigInteger

    /**
     * Constructs a [BoundedRational] from 2 [BigInteger] values.
     *
     * @param n the numerator of our [BoundedRational]
     * @param d the denominator of our [BoundedRational]
     */
    constructor(n: BigInteger, d: BigInteger) {
        mNum = n
        mDen = d
    }

    /**
     * Constructs a [BoundedRational] from a single [BigInteger] value.
     *
     * @param n the [BigInteger] we are the [BoundedRational] for.
     */
    constructor(n: BigInteger) {
        mNum = n
        mDen = BigInteger.ONE
    }

    /**
     * Constructs a [BoundedRational] from 2 [Long] values.
     *
     * @param n the numerator of our [BoundedRational]
     * @param d the denominator of our [BoundedRational]
     */
    constructor(n: Long, d: Long) {
        mNum = BigInteger.valueOf(n)
        mDen = BigInteger.valueOf(d)
    }

    /**
     * Constructs a [BoundedRational] from a single [Long] value
     *
     * @param n the [Long] we are the [BoundedRational] for.
     */
    constructor(n: Long) {
        mNum = BigInteger.valueOf(n)
        mDen = BigInteger.valueOf(1)
    }

    /**
     * Convert to [String] reflecting raw representation. Debug or log messages only, not pretty.
     *
     * @return a [String] version of us.
     */
    override fun toString(): String {
        return "$mNum/$mDen"
    }

    /**
     * Convert to a readable String. Intended for output to user. More expensive, less useful for
     * debugging than [toString]. Not internationalized. We initialize our variable *nicer* to the
     * [BoundedRational] created from *this* by first using our method [reduce] to simplify our
     * contents by factoring out the GCF, then feeding that value to our method [positiveDen] to
     * convert that [BoundedRational] to one with a positive denominator. We intialize our variable
     * *result* to the [String] version of the [mNum] numerator of *nicer*. When the [mDen] field of
     * *nicer* is not the contant [BigInteger.ONE] we append the character "/" followed by the string
     * version of the [mDen] field of *nicer* to *result*. Finally we return *result* to the caller.
     *
     * @return a [String] version of us.
     */
    fun toNiceString(): String {
        val nicer = reduce().positiveDen()
        var result = nicer.mNum.toString()
        when {
            nicer.mDen != BigInteger.ONE -> result += "/" + nicer.mDen
        }
        return result
    }

    /**
     * Returns a truncated (rounded towards 0) representation of the result. Includes n digits to
     * the right of the decimal point. We initialize our variable *digits* to the [String] created
     * by taking the absolute value of our [mNum], multiplying it by the [BigInteger] created by
     * raising the constant [BigInteger.TEN] to the power [n], then dividing that result by the
     * absolute value of our field [mDen] and converting the [BigInteger] result of all this to a
     * string. We initialize our variable *len* to the the length of *digits*. Then if *len* is less
     * than [n] plus one, we prepend ([n]+1-*len*) zeros to *digits* and set *len* to [n] plus 1.
     * Finally we return the [String] formed by concatenating a "-" if our [signum] function determines
     * we are a negative number, followed by the substring of *digits* from index 0 to index (*len*-[n])
     * followed by a "." decimal point, followed by the substring of *digits* from index (*len*-[n]) to
     * the end of *digits*.
     *
     * @param n result precision, >= 0
     * @return a truncated (rounded towards 0) representation of the result.
     */
    fun toStringTruncated(n: Int): String {
        var digits = mNum.abs().multiply(BigInteger.TEN.pow(n)).divide(mDen.abs()).toString()
        var len = digits.length
        if (len < n + 1) {
            digits = StringUtils.repeat('0', n + 1 - len) + digits
            len = n + 1
        }
        return ((if (signum() < 0) "-" else "") + digits.substring(0, len - n) + "."
                + digits.substring(len - n))
    }

    /**
     * Return a double approximation. The result is correctly rounded to nearest, with ties rounded
     * away from zero. TODO: Should round ties to even.
     *
     * We initialize our variable *sign* to the sign that our [signum] returns (-1 if we are less
     * than 0, 0 if either [mNum] or [mDen] is 0, and 1 if we are greater than 0). If *sign* is less
     * than 0 we return minus the [Double] that this function [doubleValue] creates from the
     * [BoundedRational] that our [negate] creates by negating *this*.
     *
     * We initialize our variable *apprExp* to the length of our field [mNum]'s two's complement
     * representation minus the length of our field [mDen]'s two's complement representation. If
     * *apprExp* is less than -1100 or *sign* is 0 our result will clearly turn out to be zero so we
     * just return 0.0 to the caller. We initialize our variable *neededPrec* by subtracting 80 from
     * *apprExp*. If *neededPrec* is less than 0 we initalize our variable *dividend* to [mNum] shifted
     * left by minus *neededPrec*, otherwise we set it to [mNum]. If *neededPrec* is greater than 0
     * we initalize our variable *divisor* to [mDen] shifted left by *neededPrec*, otherwise we set
     * it to [mDen]. We initialize our variable *quotient* to the [BigInteger] that the *divide*
     * method of *dividend* creates by dividing *dividend* by *divisor*. We initialize our variable
     * *qLength* to the length of the value's two's complement representation of *quotient*, and
     * initialize our variable *extraBits* to *qLength* minus 53. We initialize our variable *exponent*
     * to *neededPrec* plus *qLength* (this is the exponent assuming leading binary point). If
     * *exponent* is greater than or equal to -1021 the binary point is actually to right of leading
     * bit so we subtract 1 from *exponent*. Otherwise we are in the gradual underflow range so we add
     * -1022 minus *exponent* plus 1 to *extraBits* and set *exponent* to -1023.
     *
     * We initialize our variable *bigMantissa* by adding the constant [BigInteger.ONE] shifted left
     * by *extraBits* minus 1 to *quotient* then shifting the result right by *extraBits*. If our
     * variable *exponent* is greater than 1024 we return the system constant POSITIVE_INFINITY. As
     * a sanity check we throw an AssertionError is *exponent* is greater than -1023 and the length
     * of the value's two's complement representation of *bigMantissa* is not 53, or *exponent* is
     * less than or equal to -1023 and the length of the value's two's complement representation of
     * *bigMantissa* is greater than or equal to 53. Otherwise we initialize our variable *mantissa*
     * to the [Long] value of *bigMantissa*. We then initialize our variable *bits* to the [Long]
     * formed by or'ing together: the result of left shifting 1L by 52 bits then subtracting 1 and
     * and'ing that value with *mantissa* (zeros the exponent area), with the result of converting
     * *exponent* to [Long] adding 1023 and shifting that by 52 (this biases the exponent and then
     * shifts it into position). Finally we return the [Double] floating point value with the same
     * bit pattern as *bits* that the library function *longBitsToDouble* creates to the caller.
     *
     * @return a [Double] approximation of the value we hold.
     */
    fun doubleValue(): Double {
        val sign = signum()
        if (sign < 0) {
            return -negate(this).doubleValue()
        }
        // We get the mantissa by dividing the numerator by denominator, after
        // suitably pre-scaling them so that the integral part of the result contains
        // enough bits. We do the pre-scaling to avoid any precision loss, so the division result
        // is correctly truncated towards zero.
        val apprExp = mNum.bitLength() - mDen.bitLength()
        if (apprExp < -1100 || sign == 0) {
            // Bail fast for clearly zero result.
            return 0.0
        }
        val neededPrec = apprExp - 80
        val dividend = if (neededPrec < 0) mNum.shiftLeft(-neededPrec) else mNum
        val divisor = if (neededPrec > 0) mDen.shiftLeft(neededPrec) else mDen
        val quotient = dividend.divide(divisor)
        val qLength = quotient.bitLength()
        var extraBits = qLength - 53
        var exponent = neededPrec + qLength  // Exponent assuming leading binary point.
        if (exponent >= -1021) {
            // Binary point is actually to right of leading bit.
            --exponent
        } else {
            // We're in the gradual underflow range. Drop more bits.
            extraBits += -1022 - exponent + 1
            exponent = -1023
        }
        val bigMantissa = quotient.add(BigInteger.ONE.shiftLeft(extraBits - 1)).shiftRight(extraBits)
        if (exponent > 1024) {
            return java.lang.Double.POSITIVE_INFINITY
        }
        if (exponent > -1023 && bigMantissa.bitLength() != 53 || exponent <= -1023 && bigMantissa.bitLength() >= 53) {
            throw AssertionError("doubleValue internal error")
        }
        val mantissa = bigMantissa.toLong()
        val bits = mantissa and (1L shl 52) - 1 or (exponent.toLong() + 1023 shl 52)
        return java.lang.Double.longBitsToDouble(bits)
    }

    /**
     * Returns a Constructive real version of the value we hold. We create a [CR] out of our field
     * [mNum] and call its *divide* method to have it create a [CR] representing itself divided by a
     * [CR] created from our field [mDen].
     */
    fun crValue(): CR {
        return CR.valueOf(mNum).divide(CR.valueOf(mDen))
    }

    /**
     * Returns an [Int] version of the value we hold. We initialize our variable *reduced* with the
     * [BoundedRational] that is the equivalent fraction of 'this' in lowest terms. If the [mDen] of
     * *reduced* is not equal to [BigInteger.ONE] we throw an ArithmeticException indicating that
     * the conversion of a non-int to an [Int] was attempted. Otherwise we return the [Int] returned
     * by the *toInt* method of the [mNum] field of *reduced* to the caller.
     */
    fun intValue(): Int {
        val reduced = reduce()
        if (reduced.mDen != BigInteger.ONE) {
            throw ArithmeticException("intValue of non-int")
        }
        return reduced.mNum.toInt()
    }

    /**
     * Returns the approximate number of bits to left of binary point. Negative indicates leading
     * zeroes to the right of binary point. If the *signum* method of our [mNum] field returns 0,
     * it is 0 so we return the value [Integer.MIN_VALUE], otherwise we return the result of
     * subtracting the length of the two's complement representation of [mDen] from the length of
     * the two's complement representation of [mNum].
     *
     * @return Approximate number of bits to the left of the binary point
     */
    fun wholeNumberBits(): Int {
        return if (mNum.signum() == 0) {
            Integer.MIN_VALUE
        } else {
            mNum.bitLength() - mDen.bitLength()
        }
    }

    /**
     * Is this number too big for us to continue with rational arithmetic? We return false for
     * integers ([mDen] is [BigInteger.ONE]) on the assumption that we have no better fallback.
     * Otherwise we return true if the length of [mNum]'s two's complement representation plus the
     * length of [mDen]'s two's complement representation is greater than our constant MAX_SIZE
     * (10,000).
     *
     * @return true the number we hold is too big to continue using rational arithmetic.
     */
    private fun tooBig(): Boolean {
        return if (mDen == BigInteger.ONE) {
            false
        } else mNum.bitLength() + mDen.bitLength() > MAX_SIZE
    }

    /**
     * Return an equivalent fraction with a positive denominator. If the sign of [mDen] is greater
     * than 0 we return *this*, otherwise we return a new instance of [BoundedRational] constructed
     * from the negative of [mNum] and the negative of [mDen].
     *
     * @return the value held by *this* only with a positive denominator.
     */
    private fun positiveDen(): BoundedRational {
        return if (mDen.signum() > 0) {
            this
        } else BoundedRational(mNum.negate(), mDen.negate())
    }

    /**
     * Return an equivalent fraction in lowest terms. Denominator sign may remain negative. If [mDen]
     * is [BigInteger.ONE] we return *this*. Otherwise we initialize our variable *divisor* to the
     * [BigInteger] that the *gcd* method of [mNum] finds to be the greatest common denominator of
     * [mNum] and [mDen]. Then we return a new instance of [BoundedRational] constructed from [mNum]
     * divided by *divisor* and [mDen] divided by *divisor*.
     *
     * @return the value held by *this* after dividing [mNum] and [mDen] by their GCD
     */
    private fun reduce(): BoundedRational {
        if (mDen == BigInteger.ONE) {
            return this  // Optimization only
        }
        val divisor = mNum.gcd(mDen)
        return BoundedRational(mNum.divide(divisor), mDen.divide(divisor))
    }

    /**
     * Compares *this* to its parameter [other] returning -1 if *this* is less than [other], 0 if
     * *this* is equal to [other] and 1 if *this* is greater than [other]. We multiply our [mNum] by
     * the [mDen] field of [other], compare that to the [mNum] field of [other] multiplied by our
     * [mDen] then multiply the result of that comparison by the sign of our [mDen] times the sign
     * of the [mDen] field of [other] (this inverts the result of the previous comparison if the
     * signs of the two denominators are different).
     *
     * @param other the [BoundedRational] we are to compare ourselves to
     * @return -1 if *this* is less than [other], 0 if *this* is equal to [other] and 1 if *this* is
     * greater than [other]
     */
    operator fun compareTo(other: BoundedRational): Int {
        // Compare by multiplying both sides by denominators, invert result if denominator product
        // was negative.
        return (mNum.multiply(other.mDen).compareTo(other.mNum.multiply(mDen))
                * mDen.signum() * other.mDen.signum())
    }

    /**
     * Returns the sign of the value we hold.
     *
     * @return -1 if *this* is less than 0, 0 if either [mNum] of [mDen] is 0 and 1 if *this* is
     * greater than 0.
     */
    fun signum(): Int {
        return mNum.signum() * mDen.signum()
    }

    /**
     * Computes a unique hash code for the value we hold. We initialize our variable *reduced* to
     * a [BoundedRational] holding the equivalent fraction of *this* in lowest terms with a positive
     * denominator. Then we return the hash value generated by the *hash* method of [Objects] from
     * the [mNum] field of *reduced* and the [mDen] field of *reduced* to the caller.
     *
     * @return a hash value formed from the "reduced" value of *this*
     */
    override fun hashCode(): Int {
        // Note that this may be too expensive to be useful.
        val reduced = reduce().positiveDen()
        return Objects.hash(reduced.mNum, reduced.mDen)
    }

    /**
     * Compares for equality the value held by its parameter [other] to the value held by *this*
     *
     * @param other the [BoundedRational] we are to compare ourselves to.
     * @return *true* if [other] is equal to *this*
     */
    override fun equals(other: Any?): Boolean {
        return other != null && other is BoundedRational && compareTo(other) == 0
    }

    /**
     * The exception we throw is someone calls our [inverse] method with a zero [BoundedRational].
     */
    class ZeroDivisionException : ArithmeticException("Division by zero")

    /**
     * Compute integral power of *this*, assuming *this* has been reduced and [exp] is >= 0. Uses
     * recursion. If our parameter [exp] is [BigInteger.ONE] we just return *this*. If [exp] is
     * odd, we return the result of multiplying *this* by the [BoundedRational] returned by a
     * recursive of this function with a parameter that is ONE less than [exp]. If the *signum*
     * method of [exp] indicates that its value is 0 we return the constant [ONE]. Otherwise we
     * initialize our variable to the result of a recursive call to this function with a parameter
     * that is half of [exp]. We then check to see if our thread has been interupted, and if so we
     * throw AbortedException. Otherwise we initialize our variable *result* to the [BoundedRational]
     * that is created when we multiply *tmp* by itself. If *result* is *null* or its [tooBig] method
     * reports that it is too big to continue to uses rational arithmetic with it we return *null*
     * to the caller, otherwise we return *result*. TODO: refactor to use loop instead of recursion
     *
     * @param exp power to raise *this* to.
     * @return *this* raised to the [exp] power.
     */
    private fun rawPow(exp: BigInteger): BoundedRational? {
        if (exp == BigInteger.ONE) {
            return this
        }
        if (exp.and(BigInteger.ONE).toInt() == 1) {
            return rawMultiply(rawPow(exp.subtract(BigInteger.ONE)), this)
        }
        if (exp.signum() == 0) {
            return ONE
        }
        val tmp = rawPow(exp.shiftRight(1))
        if (Thread.interrupted()) {
            throw CR.AbortedException()
        }
        val result = rawMultiply(tmp, tmp)
        return if (result == null || result.tooBig()) {
            null
        } else result
    }

    /**
     * Compute an integral power of this. We initialize our variable *expSign* to the sign value that
     * the *signum* method of [exp] computes from it. If *expSign* is 0 we return our constant [ONE]
     * to the caller. If [exp] is [BigInteger.ONE] we return *this* to the caller. Otherwise we
     * initialize our variable *reduced* to a [BoundedRational] holding the equivalent fraction of
     * *this* in lowest terms with a positive denominator. If the [mDen] field of *reduced* is
     * [BigInteger.ONE] there is a chance that huge exponents could give compact results when the
     * [mNum] field of *reduced is:
     *
     * [BigInteger.ZERO] we return [ZERO] to the caller.
     *
     * [BigInteger.ONE] we return [ONE] to the caller.
     *
     * [BIG_MINUS_ONE] we return [MINUS_ONE] if [exp] is odd, or [ONE] if it is even.
     *
     * Looks like we don't have a simple case, but if there are more than 1,000 bits in [exp] stack
     * overflow is likely so we just return *null*. Otherwise if *expSign* (the sign of [exp]) is
     * less than 0 we return the value computed by the *rawPow* method of the inverse of *reduced*
     * with a negated [exp] as its parameter, if it is greater than 0 we return the value computed
     * by the *rawPow* method of *reduced* with [exp] as its parameter.
     *
     * @param exp power to raise *this* to.
     * @return *this* raised to the [exp] power.
     */
    fun pow(exp: BigInteger): BoundedRational? {
        val expSign = exp.signum()
        if (expSign == 0) {
            // Questionable if base has undefined or zero value.
            // java.lang.Math.pow() returns 1 anyway, so we do the same.
            return ONE
        }
        if (exp == BigInteger.ONE) {
            return this
        }
        // Reducing once at the beginning means there's no point in reducing later.
        val reduced = reduce().positiveDen()
        // First handle cases in which huge exponents could give compact results.
        if (reduced.mDen == BigInteger.ONE) {
            when {
                reduced.mNum == BigInteger.ZERO -> return ZERO
                reduced.mNum == BigInteger.ONE -> return ONE
                reduced.mNum == BIG_MINUS_ONE -> return if (exp.testBit(0)) {
                    MINUS_ONE
                } else {
                    ONE
                }
            }
        }
        if (exp.bitLength() > 1000) {
            // Stack overflow is likely; a useful rational result is not.
            return null
        }
        return if (expSign < 0) {
            inverse(reduced)!!.rawPow(exp.negate())
        } else {
            reduced.rawPow(exp)
        }
    }

    /**
     * Our constants and static functions.
     */
    companion object {

        /**
         * Maximum number of bits that a [BoundedRational] should contain ([mNum] plus [mDen])
         */
        private const val MAX_SIZE = 10000 // total, in bits

        /**
         * Produce [BoundedRational] equal to the given double. First we initialize our variable
         * *longVal* with the rounded [Long] of our parameter [x]. If the [Double] value of *longVal*
         * is equal to [x] and the absolute value of *longVal* is less than or equal to 1000 we just
         * return the [BoundedRational] constructed from *longVal*. Otherwise we initialize our
         * variable *allBits* to the [Long] created by the library method *doubleToRawLongBits* from
         * the absolute value of [x] (the raw bits of it allowing us to manipulate them). We initialize
         * our variable *mantissa* by masking off bits 0 to 51, and we initialize our variable
         * *biasedExp* by using the *ushr* method of *allBits* to shift it right by 52 bits filling
         * the leftmost bits with zeros and converting that to an [Int]. If *biasedExp* and 0x7ff
         * is equal to 0x7ff we throw an ArithmeticException since that means that [x] is infinity
         * or NaN and not convertible to a [BoundedRational]. We initialize our variable *sign* to
         * the [Long] -1 if [x] is less than 0.0 or to 1 if it is not less than 0. We initialize our
         * variable *exp* by subtracting 1075 from *biasedExp*, and if *biasedExp* is 0 we add 1 to
         * *exp* (since the de-normal exponent is 1 greater) otherwise we add 1 left shifted by 52
         * bits to *mantissa* (this is the implied leading one of a normalized number). We initialize
         * our variable *num* to the [BigInteger] formed by multiplying *sign* times *mantissa*, and
         * our variable *den* to the constant [BigInteger.ONE]. If *exp* is greater than or equal to
         * 0 we shift *num* left by *exp* bits, otherwise we shift *den* left by minus *exp* bits.
         * Finally we return a [BoundedRational] constructed from *num* and *den* to the caller.
         *
         * @param x *Double* we want to convert to a [BoundedRational]
         * @return a [BoundedRational] version of our parameter [x]
         */
        fun valueOf(x: Double): BoundedRational {
            val longVal = Math.round(x)
            if (longVal.toDouble() == x && Math.abs(longVal) <= 1000) {
                return valueOf(longVal)
            }
            val allBits = java.lang.Double.doubleToRawLongBits(Math.abs(x))
            var mantissa = allBits and (1L shl 52) - 1
            val biasedExp = allBits.ushr(52).toInt()
            if (biasedExp and 0x7ff == 0x7ff) {
                throw ArithmeticException("Infinity or NaN not convertible to BoundedRational")
            }
            val sign = (if (x < 0.0) -1 else 1).toLong()
            var exp = biasedExp - 1075  // 1023 + 52; we treat mantissa as integer.
            if (biasedExp == 0) {
                exp += 1  // De-normal exponent is 1 greater.
            } else {
                mantissa += 1L shl 52  // Implied leading one.
            }
            var num = BigInteger.valueOf(sign * mantissa)
            var den = BigInteger.ONE
            if (exp >= 0) {
                num = num.shiftLeft(exp)
            } else {
                den = den.shiftLeft(-exp)
            }
            return BoundedRational(num, den)
        }

        /**
         * Produce BoundedRational equal to the given long. When [x] is greater than or equal to -2
         * and less than or equal to 10 we try to optimize by returning our constant MINUS_TWO for
         * -2, MINUS_ONE for -1, ZERO for 0, ONE for 1, TWO for 2, and TEN for 10. If [x] is not one
         * of these values we return a [BoundedRational] constructed from [x].
         *
         * @param x *Long* we want to convert to a [BoundedRational]
         * @return a [BoundedRational] version of our parameter [x]
         */
        fun valueOf(x: Long): BoundedRational {
            if (x >= -2 && x <= 10) {
                when (x.toInt()) {
                    -2 -> return MINUS_TWO
                    -1 -> return MINUS_ONE
                    0 -> return ZERO
                    1 -> return ONE
                    2 -> return TWO
                    10 -> return TEN
                }
            }
            return BoundedRational(x)
        }

        /**
         * Converts its [BoundedRational] parameter [r] to a [String]. If the *toString* method of
         * [r] returns a non-null [String] we return it, otherwise we return the string "not a small
         * rational".
         *
         * @param r the [BoundedRational] we are to convert to a string
         * @return a [String] representation of our parameter [r] or the string "not a small rational"
         */
        fun toString(r: BoundedRational?): String {
            return r?.toString() ?: "not a small rational"
        }

        /**
         * The [Random] number generator used by our [maybeReduce] method.
         */
        private var sReduceRng = Random()

        /**
         * Return a possibly reduced version of [r] that's not *tooBig()*. Return null if none exists.
         * If [r] is *null* we return *null* to the caller. If [r] is not too big for [BoundedRational]
         * arithmetic, and the lower 4 bits of the next integer random number of [sReduceRng] are not
         * all 0 (15 in 16 chance) we return [r] to the caller without reducing it. Otherwise we
         * initialize our variable *result* to the [BoundedRational] version of [r] with a positive
         * denominator. We then set *result* to the [BoundedRational] that its *reduce* method creates
         * from it by reducing it to lowest terms. If *result* is not too big for [BoundedRational]
         * arithmetic we return it to the caller, otherwise we return *null*.
         *
         * @param r the [BoundedRational] we might try to reduce if possible.
         * @return a [BoundedRational] possibly reduced to lowest terms, our parameter [r] or *null*
         */
        private fun maybeReduce(r: BoundedRational?): BoundedRational? {
            if (r == null) return null
            // Reduce randomly, with 1/16 probability, or if the result is too big.
            if (!r.tooBig() && sReduceRng.nextInt() and 0xf != 0) {
                return r
            }
            var result = r.positiveDen()
            result = result.reduce()
            return if (!result.tooBig()) {
                result
            } else null
        }

        // We use static methods for arithmetic, so that we can easily handle the null case. We try
        // to catch domain errors whenever possible, sometimes even when one of the arguments is null,
        // but not relevant.

        /**
         * Returns equivalent [BigInteger] result if it exists, *null* if not. If [r] is *null* we
         * return *null* to the caller. Otherwise we initialize our variable *quotAndRem* to the
         * array of [BigInteger] returned by the *divideAndRemainder* method of the numerator of [r]
         * when it divides itself by the denominator of [r] (index 0 will contain the quotient, and
         * index 1 will contain the remainder). If the remainder is 0 we return th quotient to the
         * caller, otherwise we return *null*.
         *
         * @param r the [BoundedRational] we want to convert to a [BigInteger].
         * @return a [BigInteger] equivalent of [r] if possible or *null* if not possible.
         */
        fun asBigInteger(r: BoundedRational?): BigInteger? {
            if (r == null) {
                return null
            }
            val quotAndRem = r.mNum.divideAndRemainder(r.mDen)
            return if (quotAndRem[1].signum() == 0) {
                quotAndRem[0]
            } else {
                null
            }
        }

        /**
         * Adds two [BoundedRational] values and returns the (possible reduced) [BoundedRational]
         * result if the addition is possible, otherwise it returns *null*. If [r1] or [r2] is *null*
         * we return *null* to the caller. Otherwise we initialize our variable *den* to the
         * [BigInteger] that results when we multiply the denominator of [r1] by the denominator of
         * [r2], and initialize our variable *num* to the [BigInteger] that results when we add
         * the numerator of [r1] times the denominator of [r2] to the numerator of [r2] times the
         * denominator of [r1]. Then we return the (possibly reduced) [BoundedRational] that our
         * [maybeReduce] method creates from a [BoundedRational] constructed from *num* and *den*
         * to the caller.
         *
         * @param r1 the first [BoundedRational] value
         * @param r2 the second [BoundedRational] value
         * @return a possibly reduced [BoundedRational] (1 in 16 chance) which is the sum of [r1] and
         * [r2] or *null* if the addition is not possible.
         */
        fun add(r1: BoundedRational?, r2: BoundedRational?): BoundedRational? {
            if (r1 == null || r2 == null) {
                return null
            }
            val den = r1.mDen.multiply(r2.mDen)
            val num = r1.mNum.multiply(r2.mDen).add(r2.mNum.multiply(r1.mDen))
            return maybeReduce(BoundedRational(num, den))
        }

        /**
         * Return the argument, but with the opposite sign. Returns null only for a null argument.
         * If [r] is *null* we return *null* to the caller, otherwise we return a [BoundedRational]
         * constructed from the negated numerator of [r] and the denominator of [r].
         *
         * @param r the [BoundedRational] we are to negate.
         * @return a [BoundedRational] which has the opposite sign of its parameter [r].
         */
        fun negate(r: BoundedRational): BoundedRational {
            return BoundedRational(r.mNum.negate(), r.mDen)
        }

        /**
         * Subtracts a [BoundedRational] value from another [BoundedRational] value and returns the
         * result. We just return the result returned by our [add] method for [r1] and negative [r2].
         *
         * @param r1 the minuend of our subtraction
         * @param r2 the subtrahend of our subtraction
         * @return the difference between [r1] and [r2].
         */
        @Suppress("unused")
        fun subtract(r1: BoundedRational, r2: BoundedRational): BoundedRational? {
            return add(r1, negate(r2))
        }

        /**
         * Return product of [r1] and [r2] without reducing the result. If either [r1] or [r2] is
         * *null* we return *null*. If [r1] is our constant ONE we return [r2], and if [r2] is our
         * constant ONE we return [r1]. Otherwise we initialize our variable *num* to the [BigInteger]
         * result of mutiplying the [mNum] field of [r1] by the [mNum] field of [r2] and our variable
         * *den* to the [BigInteger] result of multiplying the [mDen] field of [r1] by the [mDen]
         * field of [r2]. We then return a [BoundedRational] constructed from *num* and *den*.
         *
         * @param r1 the multiplicand of our multiplication
         * @param r2 the multiplier of our multiplication
         * @return the product of our multiplication
         */
        private fun rawMultiply(r1: BoundedRational?, r2: BoundedRational?): BoundedRational? {
            // It's tempting but marginally unsound to reduce 0 * null to 0.  The null could represent
            // an infinite value, for which we failed to throw an exception because it was too big.
            if (r1 == null || r2 == null) {
                return null
            }
            // Optimize the case of our special ONE constant, since that's cheap and somewhat frequent.
            if (r1 === ONE) {
                return r2
            }
            if (r2 === ONE) {
                return r1
            }
            val num = r1.mNum.multiply(r2.mNum)
            val den = r1.mDen.multiply(r2.mDen)
            return BoundedRational(num, den)
        }

        /**
         *  Return product of [r1] and [r2] possibly reducing (1 in 16 chance) the result. We return
         *  the [BoundedRational] created by our [maybeReduce] method from the [BoundedRational]
         *  unreduced product produced by a call to our [rawMultiply] method.
         *
         * @param r1 the multiplicand of our multiplication
         * @param r2 the multiplier of our multiplication
         * @return the possibly reduced (1 in 16 chance) product of our multiplication
         */
        fun multiply(r1: BoundedRational, r2: BoundedRational?): BoundedRational? {
            return maybeReduce(rawMultiply(r1, r2))
        }

        /**
         * Return the reciprocal of [r] (or null if the argument was *null*). If [r] is *null* we
         * return *null*. If the [mNum] field of [r] is 0 we throw ZeroDivisionException. Otherwise
         * we return a [BoundedRational] constructed from the [mDen] field of [r] and its [mNum]
         * field.
         *
         * @param r the [BoundedRational] we are to produce the inverse of
         * @return the reciprocal of [r] (or null if the argument was *null*).
         */
        fun inverse(r: BoundedRational?): BoundedRational? {
            if (r == null) {
                return null
            }
            if (r.mNum.signum() == 0) {
                throw ZeroDivisionException()
            }
            return BoundedRational(r.mDen, r.mNum)
        }

        /**
         * Divides the [BoundedRational] dividend [r1] by the [BoundedRational] divisor [r2] and
         * returns the result. We just return the [BoundedRational] created by our [multiply] method
         * from [r1] and the [BoundedRational] created by our [inverse] method from [r2].
         *
         * @param r1 the dividend
         * @param r2 the divisor
         * @return [r1] divided by [r2]
         */
        fun divide(r1: BoundedRational, r2: BoundedRational): BoundedRational? {
            return multiply(r1, inverse(r2))
        }

        /**
         * Returns the square root of its parameter [r] or *null*. We initialize our variable *rTemp*
         * to [r] if it is not *null* otherwise we return *null* to the caller. We set *rTemp* to its
         * reduced, positive denominator [BoundedRational] equivalent. If the the sign of the [mNum]
         * field of *rTemp* is negative we throw an ArithmeticException. Otherwise we initialize our
         * variable *numSqrt* to the [BigInteger] created when we convert the [mNum] field of *rTemp*
         * to a [Double], take its square root, round the result and convert that to a [BigInteger].
         * If *numSqrt* multiplied by itself is not equal to the [mNum] field of *rTemp* we return
         * *null* (the square root of [r] is not a [BoundedRational]). We initialize our variable
         * *denSqrt* to the [BigInteger] created when we convert the [mDen] field of *rTemp* to a
         * [Double], take its square root, round the result and convert that to a [BigInteger]. If
         * *denSqrt* multiplied by itself is not equal to the [mDen] field of *rTemp* we return
         * *null* (again the square root of [r] is not a [BoundedRational]). Otherwise we return a
         * [BoundedRational] constructed from *numSqrt* and *denSqrt* to the caller.
         *
         * @param r the [BoundedRational] we want to take the square root of.
         * @return the square root of [r] or *null* if this is impossible to do.
         */
        fun sqrt(r: BoundedRational?): BoundedRational? {
            var rTemp: BoundedRational? = r ?: return null
            // Return non-null if numerator and denominator are small perfect squares.
            rTemp = rTemp!!.positiveDen().reduce()
            if (rTemp.mNum.signum() < 0) {
                throw ArithmeticException("sqrt(negative)")
            }
            val numSqrt = BigInteger.valueOf(Math.round(Math.sqrt(rTemp.mNum.toDouble())))
            if (numSqrt.multiply(numSqrt) != rTemp.mNum) {
                return null
            }
            val denSqrt = BigInteger.valueOf(Math.round(Math.sqrt(rTemp.mDen.toDouble())))
            return if (denSqrt.multiply(denSqrt) != rTemp.mDen) {
                null
            } else BoundedRational(numSqrt, denSqrt)
        }

        /**
         * A constant 0 [BoundedRational]
         */
        val ZERO = BoundedRational(0)
        /**
         * A constant 1/2 [BoundedRational]
         */
        val HALF = BoundedRational(1, 2)
        /**
         * A constant -1/2 [BoundedRational]
         */
        val MINUS_HALF = BoundedRational(-1, 2)
        /**
         * A constant 1/3 [BoundedRational]
         */
        val THIRD = BoundedRational(1, 3)
        /**
         * A constant 1/4 [BoundedRational]
         */
        val QUARTER = BoundedRational(1, 4)
        /**
         * A constant 1/6 [BoundedRational]
         */
        val SIXTH = BoundedRational(1, 6)
        /**
         * A constant 1 [BoundedRational]
         */
        val ONE = BoundedRational(1)
        /**
         * A constant -1 [BoundedRational]
         */
        val MINUS_ONE = BoundedRational(-1)
        /**
         * A constant 2 [BoundedRational]
         */
        val TWO = BoundedRational(2)
        /**
         * A constant -2 [BoundedRational]
         */
        val MINUS_TWO = BoundedRational(-2)
        /**
         * A constant 10 [BoundedRational]
         */
        val TEN = BoundedRational(10)
        /**
         * A constant 12 [BoundedRational]
         */
        val TWELVE = BoundedRational(12)
        /**
         * A constant 30 [BoundedRational]
         */
        @Suppress("unused")
        val THIRTY = BoundedRational(30)
        /**
         * A constant -30 [BoundedRational]
         */
        @Suppress("unused")
        val MINUS_THIRTY = BoundedRational(-30)
        /**
         * A constant 45 [BoundedRational]
         */
        @Suppress("unused")
        val FORTY_FIVE = BoundedRational(45)
        /**
         * A constant -45 [BoundedRational]
         */
        @Suppress("unused")
        val MINUS_FORTY_FIVE = BoundedRational(-45)
        /**
         * A constant 90 [BoundedRational]
         */
        @Suppress("unused")
        val NINETY = BoundedRational(90)
        /**
         * A constant -90 [BoundedRational]
         */
        @Suppress("unused")
        val MINUS_NINETY = BoundedRational(-90)

        /**
         * A constant 2 [BigInteger]
         */
        @Suppress("unused")
        private val BIG_TWO = BigInteger.valueOf(2)
        /**
         * A constant 1 [BigInteger]
         */
        private val BIG_MINUS_ONE = BigInteger.valueOf(-1)
        /**
         * A constant 5 [BigInteger]
         */
        private val BIG_FIVE = BigInteger.valueOf(5)

        /**
         * Computes the integral power of the [base] parameter raised to the [exp] power. We
         * initialize our variable *expTemp* to [exp] if it is not *null* otherwise we return
         * *null* to the caller. If [base] is *null* we return *null* to the caller. We set
         * *expTemp* to its reduced equivalent with a positive denominator. If the [mDen] field
         * of *expTemp* is not the constant [BigInteger.ONE] we return *null* to the caller (we
         * do not handle non-integral powers), otherwise we return [base] raised to the [mNum]
         * field of *expTemp* to the caller as calculated by the *pow* method of [base].
         *
         * @param base the [BoundedRational] we are to raise to the [exp] power
         * @param exp the power we are to raise [base] to (must be integral)
         * @return [base] raised to the [exp] power or *null* if [base] is *null* or the [mDen] field
         * of [exp] once reduced is not the constant [BigInteger.ONE].
         */
        fun pow(base: BoundedRational?, exp: BoundedRational?): BoundedRational? {
            var expTemp: BoundedRational? = exp ?: return null
            if (base == null) {
                return null
            }
            expTemp = expTemp!!.reduce().positiveDen()
            return if (expTemp.mDen != BigInteger.ONE) {
                null
            } else base.pow(expTemp.mNum)
        }

        /**
         * Return the number of decimal digits to the right of the decimal point required to represent
         * the argument exactly. Return [Integer.MAX_VALUE] if that's not possible. Never returns a
         * value less than zero, even if [r] is a power of ten. We initialize our variable *rTemp*
         * to [r] if it is not *null* otherwise we return [Integer.MAX_VALUE] to the caller. We
         * initialize our variable *powersOfTwo* to 0 (this will be the Max power of 2 that divides
         * the denominator) and our variable *powersOfFive* to 0 (this will be the Max power of 5
         * that divides the denominator). If the [mDen] field of *rTemp* is the constant [BigInteger.ONE]
         * we return 0 to the caller ([r] is an is an integral value, and there are no digits to the
         * right of the decimal place required). We initialize our variable *den* to the [BigInteger]
         * in the [mDen] field of *rTemp*. If the bit length of *den* is greater than MAX_SIZE we
         * return [Integer.MAX_VALUE] to the caller (way too many digits to the right of the decimal
         * point will be required). In order to compute the number of powers of 2 in *den* we loop
         * so long as the least significant bit of *den* is 0 incrementing *powersOfTwo* then shifting
         * *den* right by one bit (so long as the least significant bit is 0 there is another power
         * of 2 remaining in *den*). In order to compute the number of powers of 5 in *den* we loop
         * so long as *den* modulo BIG_FIVE is 0 incrementing *powersOfFive* and dividing *den* by
         * BIG_FIVE. If *den* is not now reduced to the constant [BigInteger.ONE], or BIG_MINUS_ONE
         * it has a factor of other than 2 or 5 (the divisors of 10) and the decimal expansion does
         * not terminate so we return [Integer.MAX_VALUE] to the caller, otherwise we return the
         * maximum of *powersOfTwo* and *powersOfFive* to the caller (this is the powers of 10 we
         * need to cancel the denominator).
         */
        fun digitsRequired(r: BoundedRational?): Int {
            var rTemp: BoundedRational? = r ?: return Integer.MAX_VALUE
            var powersOfTwo = 0  // Max power of 2 that divides denominator
            var powersOfFive = 0  // Max power of 5 that divides denominator
            // Try the easy case first to speed things up.
            if (rTemp!!.mDen == BigInteger.ONE) {
                return 0
            }
            rTemp = rTemp.reduce()
            var den = rTemp.mDen
            if (den.bitLength() > MAX_SIZE) {
                return Integer.MAX_VALUE
            }
            while (!den.testBit(0)) {
                ++powersOfTwo
                den = den.shiftRight(1)
            }
            while (den.mod(BIG_FIVE).signum() == 0) {
                ++powersOfFive
                den = den.divide(BIG_FIVE)
            }
            // If the denominator has a factor of other than 2 or 5 (the divisors of 10), the decimal
            // expansion does not terminate.  Multiplying the fraction by any number of powers of 10
            // will not cancel the denominator. (Recall the fraction was in lowest terms to start
            // with.) Otherwise the powers of 10 we need to cancel the denominator is the larger of
            // powersOfTwo and powersOfFive.
            return if (den != BigInteger.ONE && den != BIG_MINUS_ONE) {
                Integer.MAX_VALUE
            } else Math.max(powersOfTwo, powersOfFive)
        }
    }
}
