/*
 * Copyright (C) 2016 The Android Open Source Project
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
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.round

/**
 * Computable real numbers, represented so that we can get exact decidable comparisons
 * for a number of interesting special cases, including rational computations.
 *
 * A real number is represented as the product of two numbers with different representations:
 * > A) A [BoundedRational] that can only represent a subset of the rationals, but supports
 * > exact computable comparisons.
 *
 * > B) A lazily evaluated "constructive real number" ([CR]) that provides operations to evaluate
 * > itself to any requested number of digits.
 *
 * Whenever possible, we choose (B) to be one of a small set of known constants about which we
 * know more.  For example, whenever we can, we represent rationals such that (B) is 1.
 * This scheme allows us to do some very limited symbolic computation on numbers when both
 * have the same (B) value, as well as in some other situations.  We try to maximize that
 * possibility.
 *
 * Arithmetic operations and operations that produce finite approximations may throw unchecked
 * exceptions produced by the underlying [CR] and [BoundedRational] packages, including
 * [CR.PrecisionOverflowException] and [CR.AbortedException].
 */
@Suppress("MemberVisibilityCanBePrivate")
class UnifiedReal private constructor(
        private val mRatFactor: BoundedRational,
        private val mCrFactor: CR) {

    /**
     * Return (*this* mod 2pi)/(pi/6) as a [BigInteger], or *null* if that isn't easily possible.
     */
    private val piTwelfths: BigInteger?
        get() {
            if (definitelyZero()) return BigInteger.ZERO
            if (mCrFactor === CR_PI) {
                val quotient = BoundedRational.asBigInteger(
                        BoundedRational.multiply(mRatFactor, BoundedRational.TWELVE)) ?: return null
                return quotient.mod(BIG_24)
            }
            return null
        }

/*
    init {
        if (mRatFactor == null) {
            throw ArithmeticException("Building UnifiedReal from null")
        }
    }// We don't normally traffic in null CRs, and hence don't test explicitly.
*/

    constructor(cr: CR) : this(BoundedRational.ONE, cr)

    constructor(rat: BoundedRational) : this(rat, CR_ONE)

    constructor(n: BigInteger) : this(BoundedRational(n))

    constructor(n: Long) : this(BoundedRational(n))

    /**
     * Given a constructive real [cr], try to determine whether [cr] is the logarithm of a small
     * integer.  If so, return exp([cr]) as a [BoundedRational].  Otherwise return *null*.
     * We make this determination by simple table lookup, so spurious *null* returns are
     * entirely possible, or even likely. We loop over `i` for range of valid inidces for the
     * array [sLogs], and if the `i`'th entry in [sLogs] points to the same object as our parameter
     * [cr] we return a [BoundedRational] constructed from `i` to the caller. If none of the
     * entries in [sLogs] matches [cr] we return *null*.
     *
     * @param cr The [CR] we are to look up in our table of logariths of small numbers.
     * @return A [BoundedRational] constructed from a small integer or *null*.
     */
    private fun getExp(cr: CR): BoundedRational? {
        for (i in sLogs.indices) {
            if (sLogs[i] === cr) {
                return BoundedRational(i.toLong())
            }
        }
        return null
    }

    /**
     * Is this number known to be rational?
     *
     * @return *true* if our [mCrFactor] field is [CR_ONE], or our [mRatFactor] is equal to 0.
     */
    fun definitelyRational(): Boolean {
        return mCrFactor === CR_ONE || mRatFactor.signum() == 0
    }

    /**
     * Is this number known to be irrational?
     * TODO: We could track the fact that something is irrational with an explicit flag, which
     * could cover many more cases.  Whether that matters in practice is TBD.
     *
     * @return *true* if our [definitelyRational] determines that our number is not definitely a
     * rational number and our [isNamed] method determines that our [mCrFactor] field is among the
     * well-known constructive reals we know about.
     */
    fun definitelyIrrational(): Boolean {
        return !definitelyRational() && isNamed(mCrFactor)
    }

    /**
     * Is this number known to be algebraic?
     *
     * @return *true* if our [definitelyAlgebraic] method determines that our [mCrFactor] field is
     * known to be algebraic (ie. either the constant [CR_ONE] or other known [CR] constant) or our
     * [mRatFactor] field is 0.
     */
    fun definitelyAlgebraic(): Boolean {
        return definitelyAlgebraic(mCrFactor) || mRatFactor.signum() == 0
    }

    /**
     * Is this number known to be transcendental?
     *
     * @return *true* if our [definitelyAlgebraic] method returns *false* (we are not an algebraic
     * number) and our [isNamed] method determines that our [mCrFactor] field is one of our named
     * well-known constructive reals.
     */
    @Suppress("unused")
    fun definitelyTranscendental(): Boolean {
        return !definitelyAlgebraic() && isNamed(mCrFactor)
    }

    /**
     * Convert to String reflecting raw representation. Debug or log messages only, not pretty.
     *
     * @return a string formed by concatenating the string value of our [mRatFactor] field followed
     * by the "*" multiply character followed by the string value of our [mCrFactor] field.
     */
    override fun toString(): String {
        @Suppress("ConvertToStringTemplate")
        return mRatFactor.toString() + "*" + mCrFactor.toString()
    }

    /**
     * Convert to readable String. Intended for user output. Produces exact expression when possible.
     * If our [mCrFactor] field is [CR_ONE] (we are a rational number) or our [mRatFactor] is 0 we
     * return the string returned by the `toNiceString` method of [mRatFactor] to the caller. If not
     * we initialize our `val name` to the [String] returned by our [crName] method for [mCrFactor].
     * If `name` is not *null* we initialize our `val bi` to the [BigInteger] returned by the
     * [BoundedRational.asBigInteger] method for [mRatFactor] then return:
     * - `name` if `bi` is [BigInteger.ONE]
     * - Theconcatenating by `name` if
     * `bi` is not *null*
     * - The [String] formed by concatenating "(" followed by the [String] returned by the
     * `toNiceString` method of [mRatFactor] followed by ")" followed by `name` if `bi` is
     * *null*.
     *
     * If `name` is *null* we return the [String] returned by the `toString` method of [mCrFactor]
     * if [mRatFactor] is [BoundedRational.ONE], otherwise we return the [String] returned by the
     * `toString` method the [CR] returned by our `crValue` method to the caller.
     *
     * @return readable [String] representation suitable for user output.
     */
    fun toNiceString(): String {
        if (mCrFactor === CR_ONE || mRatFactor.signum() == 0) {
            return mRatFactor.toNiceString()
        }
        val name = crName(mCrFactor)
        if (name != null) {
            val bi = BoundedRational.asBigInteger(mRatFactor)
            return if (bi != null) {
                if (bi == BigInteger.ONE) {
                    name
                } else mRatFactor.toNiceString() + name
            } else "(" + mRatFactor.toNiceString() + ")" + name
        }
        return if (mRatFactor == BoundedRational.ONE) {
            mCrFactor.toString()
        } else crValue().toString()
    }

    /**
     * Will toNiceString() produce an exact representation?
     *
     * @return *true* if our [crName] method determines that our [mCrFactor] field is a well-known
     * constructive real.
     */
    fun exactlyDisplayable(): Boolean {
        return crName(mCrFactor) != null
    }

    /**
     * Returns a truncated representation of the result. If exactlyTruncatable(), we round correctly
     * towards zero. Otherwise the resulting digit string may occasionally be rounded up instead.
     * Always includes a decimal point in the result. The result includes [n] digits to the right of
     * the decimal point. If our [mCrFactor] field is [CR_ONE] (we are a rational number) or our
     * [mRatFactor] is [BoundedRational.ZERO] we return the string returned by the `toStringTruncated`
     * method of [mRatFactor] for [n] digits of precision to the caller. Otherwise we initialize our
     * `val scaled` to the [CR] created by multiplying our value as a [CR] by the [CR] created from
     * the [BigInteger] of 10 to the [n]. We initialize our `var negative` to *false*, and declare
     * `var intScaled` to be a [BigInteger]. If our method [exactlyTruncatable] returns *true* (to
     * indicate that we can compute correctly truncated approximations) we wet `intScaled` to the
     * [BigInteger] returned by the `approxGet` method of `scaled`. If the `signum` method indicated
     * thatn `intScaled` is negative we set `negative` to *true* and negate `intScaled`. If the
     * `compareTo` method of the [CR] of `intScaled` determines that `intScaled` is greater than
     * the absolute value of `scaled` we subtract [BigInteger.ONE] from `intScaled`. We then call
     * the `check` method of [CR] to make sure `intScaled` is less than `scaled`. If on the other
     * hand [exactlyTruncatable] returns *false* we set `intScaled` to the [BigInteger] returned by
     * the `approxGet` method for `scaled` to the precision of minus EXTRA_PREC. If `intScaled` is
     * negative we set `negative` to *true* and negate `intScaled`. We then shift `intScaled` right
     * by EXTRA_PREC.
     *
     * Next we initialize our `var digits` to the string value of `intScaled`, and `var len` to the
     * length of `digits`. If `len` is less than [n] plus 1 we add [n] plus 1 minus `len` "0" digits
     * to the beginning of `digits` and set `len` to [n] plus 1. Finally we return a [String] formed
     * by concatenating a "-" character if `negative` is *true* followed by the substring of `digits`
     * from 0 to `len` minus [n], followed by a "." decimal point followed by the substring of
     * `digits` from `len` minus [n] to its end.
     *
     * @param n result precision, >= 0
     * @return string representation of our value with [n] digits to the right of the decimal point.
     */
    fun toStringTruncated(n: Int): String {
        if (mCrFactor === CR_ONE || mRatFactor === BoundedRational.ZERO) {
            return mRatFactor.toStringTruncated(n)
        }
        val scaled = CR.valueOf(BigInteger.TEN.pow(n)).multiply(crValue())
        var negative = false
        var intScaled: BigInteger
        if (exactlyTruncatable()) {
            intScaled = scaled.approxGet(0)
            if (intScaled.signum() < 0) {
                negative = true
                intScaled = intScaled.negate()
            }
            @Suppress("ReplaceCallWithBinaryOperator")
            if (CR.valueOf(intScaled).compareTo(scaled.abs()) > 0) {
                intScaled = intScaled.subtract(BigInteger.ONE)
            }
            @Suppress("ReplaceCallWithBinaryOperator")
            check(CR.valueOf(intScaled).compareTo(scaled.abs()) < 0)
        } else {
            // Approximate case.  Exact comparisons are impossible.
            intScaled = scaled.approxGet(-EXTRA_PREC)
            if (intScaled.signum() < 0) {
                negative = true
                intScaled = intScaled.negate()
            }
            intScaled = intScaled.shiftRight(EXTRA_PREC)
        }
        var digits = intScaled.toString()
        var len = digits.length
        if (len < n + 1) {
            digits = StringUtils.repeat('0', n + 1 - len) + digits
            len = n + 1
        }
        return ((if (negative) "-" else "") + digits.substring(0, len - n) + "."
                + digits.substring(len - n))
    }

    /**
     * Can we compute correctly truncated approximations of this number? We return *true* is our
     * [mCrFactor] field is [CR_ONE] (we are rational) or our [mRatFactor] field is ZERO, or if
     * our [definitelyIrrational] method returns *true* (we are one of the well known irrational
     * numbers).
     *
     * @return *true* if we can compute correctly truncated approximations of this number.
     */
    fun exactlyTruncatable(): Boolean {
        // If the value is known rational, we can do exact comparisons.
        // If the value is known irrational, then we can safely compare to rational approximations;
        // equality is impossible; hence the comparison must converge.
        // The only problem cases are the ones in which we don't know.
        return mCrFactor === CR_ONE || mRatFactor === BoundedRational.ZERO || definitelyIrrational()
    }

    /**
     * Return a double approximation. Rational arguments are currently rounded to nearest, with ties
     * away from zero. If our [mCrFactor] field is [CR_ONE] we return the [Double] value returned by
     * the [doubleValue] method of our [mRatFactor] field, otherwise we return the [Double] returned
     * by the [toDouble] method of the [CR] computed by our [crValue] method (it returns our
     * [mRatFactor] field multiplied by our [mCrFactor] field).
     *
     * @return Our value converted to a [Double].
     */
    @Suppress("unused")
    fun doubleValue(): Double {
        return if (mCrFactor === CR_ONE) {
            mRatFactor.doubleValue() // Hopefully correctly rounded
        } else {
            crValue().toDouble() // Approximately correctly rounded
        }
    }

    /**
     * Computes our value as a [CR] by multiplying our [mRatFactor] field by our [mCrFactor] field.
     *
     * @return the [CR] created by multiplying our [mRatFactor] field by our [mCrFactor] field.
     */
    fun crValue(): CR {
        return mRatFactor.crValue().multiply(mCrFactor)
    }

    /**
     * Are *this* and [u] exactly comparable? There are four conditions where we declare [u] to be
     * exactly comparable to *this*:
     * - When our [mCrFactor] field points to the same [CR] as the [mCrFactor] field of [u] and
     * [mCrFactor] is either a well known [CR] or is within DEFAULT_COMPARE_TOLERANCE (-1000 bits)
     * tolerance of 0.000 - we return *true*.
     * - When our [mRatFactor] field is 0, and the [mRatFactor] field of [u] is 0 - we return *true*.
     * - When our [definitelyIndependent] method determines that our [mCrFactor] field and the
     * [mCrFactor] field of [u] differ by something other than a a rational factor - we return *true*.
     * - When our value as a [CR] calculated by our [crValue] method is not equal to the value of [u]
     * as a [CR] within DEFAULT_COMPARE_TOLERANCE (-1000 bits) tolerance - we return *true*.
     *
     * Otherwise we return *false*.
     *
     * @param u The other [UnifiedReal] to be compared against.
     * @return *true* if it is possible to compare *this* [UnifiedReal] to our parameter [u].
     */
    fun isComparable(u: UnifiedReal): Boolean {
        // We check for ONE only to speed up the common case.
        // The use of a tolerance here means we can spuriously return false, not true.
        return (mCrFactor === u.mCrFactor && (isNamed(mCrFactor) || mCrFactor.signum(DEFAULT_COMPARE_TOLERANCE) != 0)
                || mRatFactor.signum() == 0 && u.mRatFactor.signum() == 0
                || definitelyIndependent(mCrFactor, u.mCrFactor)
                || crValue().compareTo(u.crValue(), DEFAULT_COMPARE_TOLERANCE) != 0)
    }

    /**
     * Return +1 if *this* is greater than [u], -1 if *this* is less than [u], or 0 of the two are
     * known to be equal. May diverge if the two are equal and our [isComparable] method returns
     * *false* for [u]. If our [definitelyZero] method returns *true* indicating that *this* is 0
     * and the [definitelyZero] method of [u] also returns *true* we return 0 to the caller. If our
     * [mCrFactor] field points to the same [CR] as the [mCrFactor] field of [u] we initialize our
     * `val signum` to the value returned by the `signum` method of [mCrFactor] then return `signum`
     * times the value returned by the `compareTo` method of our field [mRatFactor] when it compares
     * itself to the [mRatFactor] field of [u]. Otherwise we return the value returned by the
     * `compareTo` method of our value as a [CR] that is calculated by our [crValue] when it compares
     * itself to the [CR] value of [u] that its `crValue` method calculates.
     *
     * @param u The other [UnifiedReal] to be compared to.
     * @return +1 if *this* is greater than [u], -1 if *this* is less than [u], or 0 of the two are
     * known to be equal.
     */
    operator fun compareTo(u: UnifiedReal): Int {
        if (definitelyZero() && u.definitelyZero()) return 0
        if (mCrFactor === u.mCrFactor) {
            val signum = mCrFactor.signum()  // Can diverge if mCrFactor == 0.
            return signum * mRatFactor.compareTo(u.mRatFactor)
        }
        return crValue().compareTo(u.crValue())  // Can also diverge.
    }

    /**
     * Return +1 if this is greater than r, -1 if this is less than r, or possibly 0 of the two are
     * within 2^a of each other. If our [isComparable] returns *true* to indicate that it is possible
     * to compare *this* to [u] we return the value returned by our `compareTo(UnifiedReal)` method
     * when given [u]. Otherwise we return the value returned by the `compareTo` method of our value
     * as a [CR] that our [crValue] method calculates when that method compares *this* to the value
     * as a [CR] of [u] with a as the tolerance in bits.
     *
     * @param u The other [UnifiedReal]
     * @param a Absolute tolerance in bits
     * @return +1 if this is greater than r, -1 if this is less than r, or possibly 0 of the two are
     * within 2^a of each other
     */
    fun compareTo(u: UnifiedReal, a: Int): Int {
        return if (isComparable(u)) {
            compareTo(u)
        } else {
            crValue().compareTo(u.crValue(), a)
        }
    }

    /**
     * Return compareTo(ZERO, a).
     *
     * @param a Absolute tolerance in bits
     * @return the value returned by our [compareTo] method when it compares [ZERO] to *this* to a
     * precision of [a] bits.
     */
    fun signum(a: Int): Int {
        return compareTo(ZERO, a)
    }

    /**
     * Return compareTo(ZERO). May diverge for ZERO argument if !isComparable(ZERO).
     *
     * @return the value returned by our [compareTo] method when it compares [ZERO] to *this*
     */
    fun signum(): Int {
        return compareTo(ZERO)
    }

    /**
     * Equality comparison. May erroneously return *true* if values differ by less than 2^a, and
     * !isComparable(u). If our [isComparable] method determines that [u] is comparable to *this*,
     * we call our [definitelyIndependent] returns *true* when comparing the [mCrFactor] field of
     * this and the [mCrFactor] of [u] and our [mRatFactor] field is not equal to 0 or the same
     * field of [u] is not equal to 0 we return *false* without doing any further work, otherwise
     * we call our [compareTo] method to compare *this* to [u] and return *true* if it returns 0.
     * If our [isComparable] method returns *false* on the other hand we return *true* if the
     * `compareTo` method of our value as a [CR] as calculated by our [crValue] method determines
     * that the value as a [CR] of [u] is within the tolerance of [a] of us.
     *
     * @param u The other [UnifiedReal]
     * @param a Absolute tolerance in bits
     * @return *true* if [u] is approximately equal to *this* to a precision of [a] bits.
     */
    fun approxEquals(u: UnifiedReal, a: Int): Boolean {
        return if (isComparable(u)) {
            if (definitelyIndependent(mCrFactor, u.mCrFactor) && (mRatFactor.signum() != 0
                            || u.mRatFactor.signum() != 0)) {
                // No need to actually evaluate, though we don't know which is larger.
                false
            } else {
                compareTo(u) == 0
            }
        } else crValue().compareTo(u.crValue(), a) == 0
    }

    /**
     * Returns *true* if values are definitely known to be equal, *false* in all other cases.
     * This does not satisfy the contract for Object.equals(). We return *true* if our [isComparable]
     * method determines that [u] is comparable to *this* and our [compareTo] method returns 0 to
     * indicate that [u] is equal to *this*.
     *
     * @param u The other [UnifiedReal]
     * @return *true* if [u] is definitely known to be equal to *this*.
     */
    fun definitelyEquals(u: UnifiedReal): Boolean {
        return isComparable(u) && compareTo(u) == 0
    }

    /**
     * Returns a hash code value for the object. We just return the useless value 0.
     *
     * @return we always return 0.
     */
    override fun hashCode(): Int {
        // Better useless than wrong. Probably.
        return 0
    }

    /**
     * Indicates whether some other object is "equal to" this one. Implementations must fulfil the
     * following requirements:
     * * Reflexive: for any non-null value `x`, `x.equals(x)` should return true.
     * * Symmetric: for any non-null values `x` and `y`, `x.equals(y)` should return true if and
     * only if `y.equals(x)` returns true.
     * * Transitive:  for any non-null values `x`, `y`, and `z`, if `x.equals(y)` returns true
     * and `y.equals(z)` returns true, then `x.equals(z)` should return true.
     * * Consistent:  for any non-null values `x` and `y`, multiple invocations of `x.equals(y)`
     * consistently return true or consistently return false, provided no information used in
     * `equals` comparisons on the objects is modified.
     * * Never equal to null: for any non-null value `x`, `x.equals(null)` should return false.
     *
     * @param other The other kotlin object (*Any*) we are comparing *this* to.
     * @return we always return *false* if *this* is not being compared to another instance of
     * [UnifiedReal] and is *null*, if we are being compared to another instance we throw an
     * [AssertionError] because one "Can't compare UnifiedReals for exact equality".
     */
    override fun equals(other: Any?): Boolean {

        if (other == null || other !is UnifiedReal) {
            return false
        }
        // This is almost certainly a programming error. Don't even try.
        throw AssertionError("Can't compare UnifiedReals for exact equality")
    }

    /**
     * Returns *true* if values are definitely known not to be equal, *false* in all other cases.
     * Performs no approximate evaluation. We set our `val isNamed` to *true* if our [isNamed]
     * method determines that our [mCrFactor] field points to one of the well known [CR] constants,
     * and our `val uIsNamed` to *true* if our [isNamed] method determines that the `mCrFactor`
     * field of [u] points to one of the well known [CR] constants. If both `isNamed` and `uIsNamed`
     * are *true* we branch on the whether our [definitelyIndependent] method determines that our
     * [mCrFactor] field and the `mCrFactor` field of [u] are definitely independent, returning
     * *true* if either our [mRatFactor] field or the `mRatFactor` field of [u] are not equal to 0.
     * If they are not definitely independent and our [mCrFactor] field points to the same [CR] as
     * the `mCrFactor` field of [u] we return *true* if our [mRatFactor] field is not equal to the
     * `mRatFactor` field of [u]. Otherwise we return *true* if our [mRatFactor] field is not equal
     * to the `mRatFactor` field of [u]. On the other hand if either `isNamed` or `uIsNamed` was
     * *false* we first check if our [mRatFactor] is 0 and if so return *true* if `uIsNamed` is
     * *true* and the `mRatFactor` field of [u] is not equal to 0. If our [mRatFactor] field is not
     * 0 we return *true* if the `mRatFactor` field of [u] is 0 `isNamed` is *true* and our
     * [mRatFactor] field is not 0. Otherwise we return *false*.
     *
     * @param u the other [UnifiedReal] we are comparing *this* to.
     * @return *true* it [u] is definitely not equal to *this*.
     */
    @Suppress("unused")
    fun definitelyNotEquals(u: UnifiedReal): Boolean {
        val isNamed = isNamed(mCrFactor)
        val uIsNamed = isNamed(u.mCrFactor)
        if (isNamed && uIsNamed) {
            if (definitelyIndependent(mCrFactor, u.mCrFactor)) {
                return mRatFactor.signum() != 0 || u.mRatFactor.signum() != 0
            } else if (mCrFactor === u.mCrFactor) {
                return mRatFactor != u.mRatFactor
            }
            return mRatFactor != u.mRatFactor
        }
        if (mRatFactor.signum() == 0) {
            return uIsNamed && u.mRatFactor.signum() != 0
        }
        return if (u.mRatFactor.signum() == 0) {
            isNamed && mRatFactor.signum() != 0
        } else false
    }

    // And some slightly faster convenience functions for special cases:

    /**
     * Returns *true* if our [mRatFactor] field is 0 (as determined by the `signum` method of that
     * [BoundedRational].
     *
     * @return *true* if our [mRatFactor] field is 0.
     */
    fun definitelyZero(): Boolean {
        return mRatFactor.signum() == 0
    }

    /**
     * Can this number be determined to be definitely nonzero without performing approximate
     * evaluation?
     */
    @Suppress("unused")
    fun definitelyNonZero(): Boolean {
        return isNamed(mCrFactor) && mRatFactor.signum() != 0
    }

    @Suppress("unused")
    fun definitelyOne(): Boolean {
        return mCrFactor === CR_ONE && mRatFactor == BoundedRational.ONE
    }

    /**
     * Return equivalent BoundedRational, if known to exist, null otherwise
     */
    fun boundedRationalValue(): BoundedRational? {
        return if (mCrFactor === CR_ONE || mRatFactor.signum() == 0) {
            mRatFactor
        } else null
    }

    /**
     * Returns equivalent BigInteger result if it exists, null if not.
     */
    fun bigIntegerValue(): BigInteger? {
        val r = boundedRationalValue()
        return BoundedRational.asBigInteger(r)
    }

    fun add(u: UnifiedReal): UnifiedReal {
        if (mCrFactor === u.mCrFactor) {
            val nRatFactor = BoundedRational.add(mRatFactor, u.mRatFactor)
            if (nRatFactor != null) {
                return UnifiedReal(nRatFactor, mCrFactor)
            }
        }
        if (definitelyZero()) {
            // Avoid creating new mCrFactor, even if they don't currently match.
            return u
        }
        return if (u.definitelyZero()) {
            this
        } else UnifiedReal(crValue().add(u.crValue()))
    }

    fun negate(): UnifiedReal {
        return UnifiedReal(BoundedRational.negate(mRatFactor), mCrFactor)
    }

    fun subtract(u: UnifiedReal): UnifiedReal {
        return add(u.negate())
    }

    fun multiply(u: UnifiedReal): UnifiedReal {
        // Preserve a preexisting mCrFactor when we can.
        if (mCrFactor === CR_ONE) {
            val nRatFactor = BoundedRational.multiply(mRatFactor, u.mRatFactor)
            if (nRatFactor != null) {
                return UnifiedReal(nRatFactor, u.mCrFactor)
            }
        }
        if (u.mCrFactor === CR_ONE) {
            val nRatFactor = BoundedRational.multiply(mRatFactor, u.mRatFactor)
            if (nRatFactor != null) {
                return UnifiedReal(nRatFactor, mCrFactor)
            }
        }
        if (definitelyZero() || u.definitelyZero()) {
            return ZERO
        }
        if (mCrFactor === u.mCrFactor) {
            val square = getSquare(mCrFactor)
            if (square != null) {

                val nRatFactor = BoundedRational.multiply(
                        BoundedRational.multiply(square, mRatFactor)!!, u.mRatFactor)
                if (nRatFactor != null) {
                    return UnifiedReal(nRatFactor)
                }
            }
        }
        // Probably a bit cheaper to multiply component-wise.
        val nRatFactor = BoundedRational.multiply(mRatFactor, u.mRatFactor)
        return if (nRatFactor != null) {
            UnifiedReal(nRatFactor, mCrFactor.multiply(u.mCrFactor))
        } else UnifiedReal(crValue().multiply(u.crValue()))
    }

    class ZeroDivisionException : ArithmeticException("Division by zero")

    /**
     * Return the reciprocal.
     */
    fun inverse(): UnifiedReal {
        if (definitelyZero()) {
            throw ZeroDivisionException()
        }
        val square = getSquare(mCrFactor)
        if (square != null) {
            // 1/sqrt(n) = sqrt(n)/n
            val nRatFactor = BoundedRational.inverse(
                    BoundedRational.multiply(mRatFactor, square))
            if (nRatFactor != null) {
                return UnifiedReal(nRatFactor, mCrFactor)
            }
        }
        return UnifiedReal(BoundedRational.inverse(mRatFactor)!!, mCrFactor.inverse())
    }

    fun divide(u: UnifiedReal): UnifiedReal {
        if (mCrFactor === u.mCrFactor) {
            if (u.definitelyZero()) {
                throw ZeroDivisionException()
            }
            val nRatFactor = BoundedRational.divide(mRatFactor, u.mRatFactor)
            if (nRatFactor != null) {
                return UnifiedReal(nRatFactor, CR_ONE)
            }
        }
        return multiply(u.inverse())
    }

    /**
     * Return the square root.
     * This may fail to return a known rational value, even when the result is rational.
     */
    fun sqrt(): UnifiedReal {
        if (definitelyZero()) {
            return ZERO
        }
        if (mCrFactor === CR_ONE) {
            var ratSqrt: BoundedRational?
            // Check for all arguments of the form <perfect rational square> * small_int,
            // where small_int has a known sqrt.  This includes the small_int = 1 case.
            for (divisor in 1 until sSqrts.size) {
                if (sSqrts[divisor] != null) {
                    ratSqrt = BoundedRational.sqrt(
                            BoundedRational.divide(mRatFactor, BoundedRational(divisor.toLong())))
                    if (ratSqrt != null) {
                        return UnifiedReal(ratSqrt, sSqrts[divisor]!!)
                    }
                }
            }
        }
        return UnifiedReal(crValue().sqrt())
    }

    fun sin(): UnifiedReal {
        val piTwelfths = piTwelfths
        if (piTwelfths != null) {
            val result = sinPiTwelfths(piTwelfths.toInt())
            if (result != null) {
                return result
            }
        }
        return UnifiedReal(crValue().sin())
    }

    fun cos(): UnifiedReal {
        val piTwelfths = piTwelfths
        if (piTwelfths != null) {
            val result = cosPiTwelfths(piTwelfths.toInt())
            if (result != null) {
                return result
            }
        }
        return UnifiedReal(crValue().cos())
    }

    @Suppress("unused")
    fun tan(): UnifiedReal {
        val piTwelfths = piTwelfths
        if (piTwelfths != null) {
            val i = piTwelfths.toInt()
            if (i == 6 || i == 18) {
                throw ArithmeticException("Tangent undefined")
            }
            val top = sinPiTwelfths(i)
            val bottom = cosPiTwelfths(i)
            if (top != null && bottom != null) {
                return top.divide(bottom)
            }
        }
        return sin().divide(cos())
    }

    // Throw an exception if the argument is definitely out of bounds for asin or acos.
    private fun checkAsinDomain() {
        if (isComparable(ONE) && (compareTo(ONE) > 0 || compareTo(MINUS_ONE) < 0)) {
            throw ArithmeticException("inverse trig argument out of range")
        }
    }

    /**
     * Return asin of this, assuming this is not an integral multiple of a half.
     */
    fun asinNonHalves(): UnifiedReal {
        if (compareTo(ZERO, -10) < 0) {
            return negate().asinNonHalves().negate()
        }
        if (definitelyEquals(HALF_SQRT2)) {
            return UnifiedReal(BoundedRational.QUARTER, CR_PI)
        }
        return if (definitelyEquals(HALF_SQRT3)) {
            UnifiedReal(BoundedRational.THIRD, CR_PI)
        } else UnifiedReal(crValue().asin())
    }

    fun asin(): UnifiedReal {
        checkAsinDomain()
        val halves = multiply(TWO).bigIntegerValue()
        if (halves != null) {
            return asinHalves(halves.toInt())
        }
        return if (mCrFactor === CR.ONE || mCrFactor !== CR_SQRT2 || mCrFactor !== CR_SQRT3) {
            asinNonHalves()
        } else UnifiedReal(crValue().asin())
    }

    fun acos(): UnifiedReal {
        return PI_OVER_2.subtract(asin())
    }

    fun atan(): UnifiedReal {
        if (compareTo(ZERO, -10) < 0) {
            return negate().atan().negate()
        }
        val asBI = bigIntegerValue()
        @Suppress("ReplaceCallWithBinaryOperator")
        if (asBI != null && asBI.compareTo(BigInteger.ONE) <= 0) {
            // These seem to be all rational cases:
            return when (asBI.toInt()) {
                0 -> ZERO
                1 -> PI_OVER_4
                else -> throw AssertionError("Impossible r_int")
            }
        }
        if (definitelyEquals(THIRD_SQRT3)) {
            return PI_OVER_6
        }
        return if (definitelyEquals(SQRT3)) {
            PI_OVER_3
        } else UnifiedReal(UnaryCRFunction.atanFunction.execute(crValue()))
    }

    /**
     * Compute an integral power of a constructive real, using the exp function when
     * we safely can. Use recursivePow when we can't. exp is known to be nozero.
     */
    private fun expLnPow(exp: BigInteger): UnifiedReal {
        val sign = signum(DEFAULT_COMPARE_TOLERANCE)
        when {
            sign > 0 -> // Safe to take the log. This avoids deep recursion for huge exponents, which
                // may actually make sense here.
                return UnifiedReal(crValue().ln().multiply(CR.valueOf(exp)).exp())
            sign < 0 -> {
                var result = crValue().negate().ln().multiply(CR.valueOf(exp)).exp()
                if (exp.testBit(0) /* odd exponent */) {
                    result = result.negate()
                }
                return UnifiedReal(result)
            }
            else -> // Base of unknown sign with integer exponent. Use a recursive computation.
                // (Another possible option would be to use the absolute value of the base, and then
                // adjust the sign at the end.  But that would have to be done in the CR
                // implementation.)
                return if (exp.signum() < 0) {
                    // This may be very expensive if exp.negate() is large.
                    UnifiedReal(recursivePow(crValue(), exp.negate()).inverse())
                } else {
                    UnifiedReal(recursivePow(crValue(), exp))
                }
        }
    }


    /**
     * Compute an integral power of this.
     * This recurses roughly as deeply as the number of bits in the exponent, and can, in
     * ridiculous cases, result in a stack overflow.
     */
    private fun pow(exp: BigInteger): UnifiedReal {
        if (exp == BigInteger.ONE) {
            return this
        }
        if (exp.signum() == 0) {
            // Questionable if base has undefined value or is 0.
            // Java.lang.Math.pow() returns 1 anyway, so we do the same.
            return ONE
        }
        val absExp = exp.abs()
        @Suppress("ReplaceCallWithBinaryOperator")
        if (mCrFactor === CR_ONE && absExp.compareTo(HARD_RECURSIVE_POW_LIMIT) <= 0) {
            val ratPow = mRatFactor.pow(exp)
            // We count on this to fail, e.g. for very large exponents, when it would
            // otherwise be too expensive.
            if (ratPow != null) {
                return UnifiedReal(ratPow)
            }
        }
        @Suppress("ReplaceCallWithBinaryOperator")
        if (absExp.compareTo(RECURSIVE_POW_LIMIT) > 0) {
            return expLnPow(exp)
        }
        val square = getSquare(mCrFactor)
        if (square != null) {

            val nRatFactor = BoundedRational.multiply(mRatFactor.pow(exp)!!, square.pow(exp.shiftRight(1)))
            if (nRatFactor != null) {
                return if (exp.and(BigInteger.ONE).toInt() == 1) {
                    // Odd power: Multiply by remaining square root.
                    UnifiedReal(nRatFactor, mCrFactor)
                } else {
                    UnifiedReal(nRatFactor)
                }
            }
        }
        return expLnPow(exp)
    }

    /**
     * Return this ^ expon.
     * This is really only well-defined for a positive base, particularly since
     * 0^x is not continuous at zero. (0^0 = 1 (as is epsilon^0), but 0^epsilon is 0.
     * We nonetheless try to do reasonable things at zero, when we recognize that case.
     */
    fun pow(expon: UnifiedReal): UnifiedReal {
        if (mCrFactor === CR_E) {
            return if (mRatFactor == BoundedRational.ONE) {
                expon.exp()
            } else {
                val ratPart = UnifiedReal(mRatFactor).pow(expon)
                expon.exp().multiply(ratPart)
            }
        }
        val expAsBR = expon.boundedRationalValue()
        if (expAsBR != null) {
            var expAsBI = BoundedRational.asBigInteger(expAsBR)
            if (expAsBI != null) {
                return pow(expAsBI)
            } else {
                // Check for exponent that is a multiple of a half.
                expAsBI = BoundedRational.asBigInteger(
                        BoundedRational.multiply(BoundedRational.TWO, expAsBR))
                if (expAsBI != null) {
                    return pow(expAsBI).sqrt()
                }
            }
        }
        // If the exponent were known zero, we would have handled it above.
        if (definitelyZero()) {
            return ZERO
        }
        val sign = signum(DEFAULT_COMPARE_TOLERANCE)
        if (sign < 0) {
            throw ArithmeticException("Negative base for pow() with non-integer exponent")
        }
        return UnifiedReal(crValue().ln().multiply(expon.crValue()).exp())
    }

    fun ln(): UnifiedReal {
        if (mCrFactor === CR_E) {
            return UnifiedReal(mRatFactor, CR_ONE).ln().add(ONE)
        }
        if (isComparable(ZERO)) {
            if (signum() <= 0) {
                throw ArithmeticException("log(non-positive)")
            }
            val compare1 = compareTo(ONE, DEFAULT_COMPARE_TOLERANCE)
            if (compare1 == 0) {
                if (definitelyEquals(ONE)) {
                    return ZERO
                }
            } else if (compare1 < 0) {
                return inverse().ln().negate()
            }
            val bi = BoundedRational.asBigInteger(mRatFactor)
            if (bi != null) {
                if (mCrFactor === CR_ONE) {
                    // Check for a power of a small integer.  We can use sLogs[] to return
                    // a more useful answer for those.
                    for (i in sLogs.indices) {
                        if (sLogs[i] != null) {
                            val intLog = getIntLog(bi, i)
                            if (intLog != 0L) {
                                return UnifiedReal(BoundedRational(intLog), sLogs[i]!!)
                            }
                        }
                    }
                } else {
                    // Check for n^k * sqrt(n), for which we can also return a more useful answer.
                    val square = getSquare(mCrFactor)
                    if (square != null) {
                        val intSquare = square.intValue()
                        if (sLogs[intSquare] != null) {
                            val intLog = getIntLog(bi, intSquare)
                            if (intLog != 0L) {
                                val nRatFactor = BoundedRational.add(BoundedRational(intLog),
                                        BoundedRational.HALF)
                                if (nRatFactor != null) {
                                    return UnifiedReal(nRatFactor, sLogs[intSquare]!!)
                                }
                            }
                        }
                    }
                }
            }
        }
        return UnifiedReal(crValue().ln())
    }

    fun exp(): UnifiedReal {
        if (definitelyEquals(ZERO)) {
            return ONE
        }
        if (definitelyEquals(ONE)) {
            // Avoid redundant computations, and ensure we recognize all instances as equal.
            return E
        }
        val crExp = getExp(mCrFactor)
        if (crExp != null) {
            var needSqrt = false
            var ratExponent: BoundedRational? = mRatFactor
            val asBI = BoundedRational.asBigInteger(ratExponent)
            if (asBI == null) {
                // check for multiple of one half.
                needSqrt = true
                ratExponent = BoundedRational.multiply(ratExponent!!, BoundedRational.TWO)
            }
            val nRatFactor = BoundedRational.pow(crExp, ratExponent)
            if (nRatFactor != null) {
                var result = UnifiedReal(nRatFactor)
                if (needSqrt) {
                    result = result.sqrt()
                }
                return result
            }
        }
        return UnifiedReal(crValue().exp())
    }


    /**
     * Factorial function.
     * Fails if argument is clearly not an integer.
     * May round to nearest integer if value is close.
     */
    fun fact(): UnifiedReal {
        var asBI = bigIntegerValue()
        if (asBI == null) {
            asBI = crValue().approxGet(0)  // Correct if it was an integer.
            if (!approxEquals(UnifiedReal(asBI), DEFAULT_COMPARE_TOLERANCE)) {
                throw ArithmeticException("Non-integral factorial argument")
            }
        }
        if (asBI!!.signum() < 0) {
            throw ArithmeticException("Negative factorial argument")
        }
        if (asBI.bitLength() > 20) {
            // Will fail.  LongValue() may not work. Punt now.
            throw ArithmeticException("Factorial argument too big")
        }
        val biResult = genFactorial(asBI.toLong(), 1)
        val nRatFactor = BoundedRational(biResult)
        return UnifiedReal(nRatFactor)
    }

    /**
     * Return the number of decimal digits to the right of the decimal point required to represent
     * the argument exactly.
     * Return Integer.MAX_VALUE if that's not possible.  Never returns a value less than zero, even
     * if r is a power of ten.
     */
    fun digitsRequired(): Int {
        return if (mCrFactor === CR_ONE || mRatFactor.signum() == 0) {
            BoundedRational.digitsRequired(mRatFactor)
        } else {
            Integer.MAX_VALUE
        }
    }

    /**
     * Return an upper bound on the number of leading zero bits.
     * These are the number of 0 bits
     * to the right of the binary point and to the left of the most significant digit.
     * Return Integer.MAX_VALUE if we cannot bound it.
     */
    fun leadingBinaryZeroes(): Int {
        if (isNamed(mCrFactor)) {
            // Only ln(2) is smaller than one, and could possibly add one zero bit.
            // Adding 3 gives us a somewhat sloppy upper bound.
            val wholeBits = mRatFactor.wholeNumberBits()
            if (wholeBits == Integer.MIN_VALUE) {
                return Integer.MAX_VALUE
            }
            return if (wholeBits >= 3) {
                0
            } else {
                -wholeBits + 3
            }
        }
        return Integer.MAX_VALUE
    }

    /**
     * Is the number of bits to the left of the decimal point greater than bound?
     * The result is inexact: We roughly approximate the whole number bits.
     */
    fun approxWholeNumberBitsGreaterThan(bound: Int): Boolean {
        return if (isNamed(mCrFactor)) {
            mRatFactor.wholeNumberBits() > bound
        } else {
            crValue().approxGet(bound - 2).bitLength() > 2
        }
    }

    companion object {
        // TODO: It would be helpful to add flags to indicate whether the result is known
        // irrational, etc.  This sometimes happens even if mCrFactor is not one of the known ones.
        // And exact comparisons between rationals and known irrationals are decidable.

        /**
         * Perform some nontrivial consistency checks.
         */
        @Suppress("unused")
        var enableChecks = true

        private fun check(b: Boolean) {
            if (!b) {
                throw AssertionError()
            }
        }

        @Suppress("unused")
        fun valueOf(x: Double): UnifiedReal {
            return if (x == 0.0 || x == 1.0) {
                valueOf(x.toLong())
            } else UnifiedReal(BoundedRational.valueOf(x))
        }

        fun valueOf(x: Long): UnifiedReal {
            return when (x) {
                0L -> ZERO
                1L -> ONE
                else -> UnifiedReal(BoundedRational.valueOf(x))
            }
        }

        // Various helpful constants
        private val BIG_24 = BigInteger.valueOf(24)
        private const val DEFAULT_COMPARE_TOLERANCE = -1000

        // Well-known CR constants we try to use in the mCrFactor position:
        private val CR_ONE = CR.ONE
        private val CR_PI = CR.PI
        private val CR_E = CR.ONE.exp()
        private val CR_SQRT2 = CR.valueOf(2).sqrt()
        private val CR_SQRT3 = CR.valueOf(3).sqrt()
        private val CR_LN2 = CR.valueOf(2).ln()
        private val CR_LN3 = CR.valueOf(3).ln()
        private val CR_LN5 = CR.valueOf(5).ln()
        private val CR_LN6 = CR.valueOf(6).ln()
        private val CR_LN7 = CR.valueOf(7).ln()
        private val CR_LN10 = CR.valueOf(10).ln()

        // Square roots that we try to recognize.
        // We currently recognize only a small fixed collection, since the sqrt() function needs to
        // identify numbers of the form <SQRT[i]>*n^2, and we don't otherwise know of a good
        // algorithm for that.
        @Suppress("RemoveExplicitTypeArguments")
        private val sSqrts = arrayOf<CR?>(
                null, CR.ONE, CR_SQRT2, CR_SQRT3, null, CR.valueOf(5).sqrt(),
                CR.valueOf(6).sqrt(), CR.valueOf(7).sqrt(), null, null,
                CR.valueOf(10).sqrt()
        )

        // Natural logs of small integers that we try to recognize.
        @Suppress("RemoveExplicitTypeArguments")
        private val sLogs = arrayOf<CR?>(
                null, null, CR_LN2, CR_LN3, null, CR_LN5,
                CR_LN6, CR_LN7, null, null, CR_LN10
        )


        // Some convenient UnifiedReal constants.
        val PI = UnifiedReal(CR_PI)
        val E = UnifiedReal(CR_E)
        val ZERO = UnifiedReal(BoundedRational.ZERO)
        val ONE = UnifiedReal(BoundedRational.ONE)
        val MINUS_ONE = UnifiedReal(BoundedRational.MINUS_ONE)
        val TWO = UnifiedReal(BoundedRational.TWO)
        @Suppress("unused")
        val MINUS_TWO = UnifiedReal(BoundedRational.MINUS_TWO)
        val HALF = UnifiedReal(BoundedRational.HALF)
        @Suppress("unused")
        val MINUS_HALF = UnifiedReal(BoundedRational.MINUS_HALF)
        val TEN = UnifiedReal(BoundedRational.TEN)
        val RADIANS_PER_DEGREE = UnifiedReal(BoundedRational(1, 180), CR_PI)
        @Suppress("unused")
        private val SIX = UnifiedReal(6)
        private val HALF_SQRT2 = UnifiedReal(BoundedRational.HALF, CR_SQRT2)
        private val SQRT3 = UnifiedReal(CR_SQRT3)
        private val HALF_SQRT3 = UnifiedReal(BoundedRational.HALF, CR_SQRT3)
        private val THIRD_SQRT3 = UnifiedReal(BoundedRational.THIRD, CR_SQRT3)
        private val PI_OVER_2 = UnifiedReal(BoundedRational.HALF, CR_PI)
        private val PI_OVER_3 = UnifiedReal(BoundedRational.THIRD, CR_PI)
        private val PI_OVER_4 = UnifiedReal(BoundedRational.QUARTER, CR_PI)
        private val PI_OVER_6 = UnifiedReal(BoundedRational.SIXTH, CR_PI)


        /**
         * Given a constructive real cr, try to determine whether cr is the square root of
         * a small integer.  If so, return its square as a BoundedRational.  Otherwise return null.
         * We make this determination by simple table lookup, so spurious null returns are
         * entirely possible, or even likely.
         */
        private fun getSquare(cr: CR): BoundedRational? {
            for (i in sSqrts.indices) {
                if (sSqrts[i] === cr) {
                    return BoundedRational(i.toLong())
                }
            }
            return null
        }

        /**
         * If the argument is a well-known constructive real, return its name.
         * The name of "CR_ONE" is the empty string.
         * No named constructive reals are rational multiples of each other.
         * Thus two UnifiedReals with different named mCrFactors can be equal only if both
         * mRatFactors are zero or possibly if one is CR_PI and the other is CR_E.
         * (The latter is apparently an open problem.)
         */
        private fun crName(cr: CR): String? {
            if (cr === CR_ONE) {
                return ""
            }
            if (cr === CR_PI) {
                return "\u03C0"   // GREEK SMALL LETTER PI
            }
            if (cr === CR_E) {
                return "e"
            }
            for (i in sSqrts.indices) {
                if (cr === sSqrts[i]) {
                    return "\u221A" /* SQUARE ROOT */ + i
                }
            }
            for (i in sLogs.indices) {
                if (cr === sLogs[i]) {
                    return "ln($i)"
                }
            }
            return null
        }

        /**
         * Would crName() return non-Null?
         */
        private fun isNamed(cr: CR): Boolean {
            if (cr === CR_ONE || cr === CR_PI || cr === CR_E) {
                return true
            }
            for (r in sSqrts) {
                if (cr === r) {
                    return true
                }
            }
            for (r in sLogs) {
                if (cr === r) {
                    return true
                }
            }
            return false
        }

        /**
         * Is cr known to be algebraic (as opposed to transcendental)?
         * Currently only produces meaningful results for the above known special
         * constructive reals.
         */
        private fun definitelyAlgebraic(cr: CR): Boolean {
            return cr === CR_ONE || getSquare(cr) != null
        }


        /**
         * Is it known that the two constructive reals differ by something other than a
         * a rational factor, i.e. is it known that two UnifiedReals
         * with those mCrFactors will compare unequal unless both mRatFactors are zero?
         * If this returns true, then a comparison of two UnifiedReals using those two
         * mCrFactors cannot diverge, though we don't know of a good runtime bound.
         */
        private fun definitelyIndependent(r1: CR, r2: CR): Boolean {
            // The question here is whether r1 = x*r2, where x is rational, where r1 and r2
            // are in our set of special known CRs, can have a solution.
            // This cannot happen if one is CR_ONE and the other is not.
            // (Since all others are irrational.)
            // This cannot happen for two named square roots, which have no repeated factors.
            // (To see this, square both sides of the equation and factor.  Each prime
            // factor in the numerator and denominator occurs twice.)
            // This cannot happen for e or pi on one side, and a square root on the other.
            // (One is transcendental, the other is algebraic.)
            // This cannot happen for two of our special natural logs.
            // (Otherwise ln(m) = (a/b)ln(n) ==> m = n^(a/b) ==> m^b = n^a, which is impossible
            // because either m or n includes a prime factor not shared by the other.)
            // This cannot happen for a log and a square root.
            // (The Lindemann-Weierstrass theorem tells us, among other things, that if
            // a is algebraic, then exp(a) is transcendental.  Thus if l in our finite
            // set of logs where algebraic, expl(l), must be transacendental.
            // But exp(l) is an integer.  Thus the logs are transcendental.  But of course the
            // square roots are algebraic.  Thus they can't be rational multiples.)
            // Unfortunately, we do not know whether e/pi is rational.
            if (r1 === r2) {
                return false
            }

            @Suppress("UNUSED_VARIABLE") val other: CR
            if (r1 === CR_E || r1 === CR_PI) {
                return definitelyAlgebraic(r2)
            }
            return if (r2 === CR_E || r2 === CR_PI) {
                definitelyAlgebraic(r1)
            } else isNamed(r1) && isNamed(r2)
        }

        // Number of extra bits used in evaluation below to prefer truncation to rounding.
        // Must be <= 30.
        private const val EXTRA_PREC = 10

        /**
         * Computer the sin() for an integer multiple n of pi/12, if easily representable.
         * @param n value between 0 and 23 inclusive.
         */
        private fun sinPiTwelfths(n: Int): UnifiedReal? {
            if (n >= 12) {
                val negResult = sinPiTwelfths(n - 12)
                return negResult?.negate()
            }
            return when (n) {
                0 -> ZERO
                2 // 30 degrees
                -> HALF
                3 // 45 degrees
                -> HALF_SQRT2
                4 // 60 degrees
                -> HALF_SQRT3
                6 -> ONE
                8 -> HALF_SQRT3
                9 -> HALF_SQRT2
                10 -> HALF
                else -> null
            }
        }

        private fun cosPiTwelfths(n: Int): UnifiedReal? {
            var sinArg = n + 6
            if (sinArg >= 24) {
                sinArg -= 24
            }
            return sinPiTwelfths(sinArg)
        }

        /**
         * Return asin(n/2).  n is between -2 and 2.
         */
        fun asinHalves(n: Int): UnifiedReal {
            if (n < 0) {
                return asinHalves(-n).negate()
            }
            when (n) {
                0 -> return ZERO
                1 -> return UnifiedReal(BoundedRational.SIXTH, CR.PI)
                2 -> return UnifiedReal(BoundedRational.HALF, CR.PI)
            }
            throw AssertionError("asinHalves: Bad argument")
        }

        @Suppress("unused")
        private val BIG_TWO = BigInteger.valueOf(2)

        // The (in abs value) integral exponent for which we attempt to use a recursive
        // algorithm for evaluating pow(). The recursive algorithm works independent of the sign of the
        // base, and can produce rational results. But it can become slow for very large exponents.
        private val RECURSIVE_POW_LIMIT = BigInteger.valueOf(1000)
        // The corresponding limit when we're using rational arithmetic. This should fail fast
        // anyway, but we avoid ridiculously deep recursion.
        private val HARD_RECURSIVE_POW_LIMIT = BigInteger.ONE.shiftLeft(1000)

        /**
         * Compute an integral power of a constructive real, using the standard recursive algorithm.
         * exp is known to be positive.
         */
        private fun recursivePow(base: CR, exp: BigInteger): CR {
            if (exp == BigInteger.ONE) {
                return base
            }
            if (exp.testBit(0)) {
                return base.multiply(recursivePow(base, exp.subtract(BigInteger.ONE)))
            }
            val tmp = recursivePow(base, exp.shiftRight(1))
            if (Thread.interrupted()) {
                throw CR.AbortedException()
            }
            return tmp.multiply(tmp)
        }

        /**
         * Raise the argument to the 16th power.
         */
        private fun pow16(n: Int): Long {
            if (n > 10) {
                throw AssertionError("Unexpected pow16 argument")
            }
            var result = (n * n).toLong()
            result *= result
            result *= result
            result *= result
            return result
        }

        /**
         * Return the integral log with respect to the given base if it exists, 0 otherwise.
         * n is presumed positive.
         */
        private fun getIntLog(n: BigInteger, base: Int): Long {
            var nLocal = n
            val nAsDouble = nLocal.toDouble()
            val approx = ln(nAsDouble) / ln(base.toDouble())
            // A relatively quick test first.
            // Unfortunately, this doesn't help for values to big to fit in a Double.
            if (!java.lang.Double.isInfinite(nAsDouble) && abs(approx - round(approx)) > 1.0e-6) {
                return 0
            }
            var result: Long = 0

            @Suppress("UNUSED_VARIABLE") val remaining = nLocal
            val bigBase = BigInteger.valueOf(base.toLong())
            var base16th: BigInteger? = null  // base^16, computed lazily
            while (nLocal.mod(bigBase).signum() == 0) {
                if (Thread.interrupted()) {
                    throw CR.AbortedException()
                }
                nLocal = nLocal.divide(bigBase)
                ++result
                // And try a slightly faster computation for large n:
                if (base16th == null) {
                    base16th = BigInteger.valueOf(pow16(base))
                }
                while (nLocal.mod(base16th).signum() == 0) {
                    nLocal = nLocal.divide(base16th)
                    result += 16
                }
            }
            return if (nLocal == BigInteger.ONE) {
                result
            } else 0
        }


        /**
         * Generalized factorial.
         * Compute n * (n - step) * (n - 2 * step) * etc.  This can be used to compute factorial a bit
         * faster, especially if BigInteger uses sub-quadratic multiplication.
         */
        private fun genFactorial(n: Long, step: Long): BigInteger {
            if (n > 4 * step) {
                val prod1 = genFactorial(n, 2 * step)
                if (Thread.interrupted()) {
                    throw CR.AbortedException()
                }
                val prod2 = genFactorial(n - step, 2 * step)
                if (Thread.interrupted()) {
                    throw CR.AbortedException()
                }
                return prod1.multiply(prod2)
            } else {
                if (n == 0L) {
                    return BigInteger.ONE
                }
                var res = BigInteger.valueOf(n)
                var i = n - step
                while (i > 1) {
                    res = res.multiply(BigInteger.valueOf(i))
                    i -= step
                }
                return res
            }
        }
    }
}
