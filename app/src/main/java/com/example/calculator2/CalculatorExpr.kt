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

import android.content.Context
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TtsSpan
import java.io.*
import java.math.BigInteger
import java.util.*

/**
 * A mathematical expression represented as a sequence of "tokens".
 * Many tokens are represented by button ids for the corresponding operator.
 * A token may also represent the result of a previously evaluated expression.
 * The [add] method adds a token to the end of the expression.  The [delete] method removes one.
 * [clear] deletes the entire expression contents. [eval] evaluates the expression,
 * producing a [UnifiedReal] result.
 * Expressions are parsed only during evaluation; no explicit parse tree is maintained.
 *
 * The [write] method is used to save the current expression.  Note that neither [UnifiedReal]
 * nor the underlying [CR] provide a serialization facility.  Thus we save all previously
 * computed values by writing out the expression that was used to compute them, and reevaluate
 * when reading it back in.
 */
@Suppress("MemberVisibilityCanBePrivate")
internal class CalculatorExpr {

    /**
     * The actual representation as a list of tokens. Constant tokens are always nonempty.
     */
    private var mExpr: ArrayList<Token>

    /**
     * Returns true if our field [mExpr] contains no elements.
     *
     * @return true if our field [mExpr] contains no elements.
     */
    val isEmpty: Boolean
        get() = mExpr.isEmpty()

    /**
     * Am I just a constant? If the *size* of [mExpr] is not 1 it contains several [Token] objects
     * (a constant occupies only a single [Token]) so we return *false*, otherwise we return *true*
     * if the only entry in [mExpr] is a [Constant] instance.
     *
     * @return true if [mExpr] holds only a single constant.
     */
    @Suppress("unused")
    val isConstant: Boolean
        get() = if (mExpr.size != 1) {
            false
        } else mExpr[0] is Constant

    /**
     * An interface for resolving expression indices in embedded subexpressions to
     * the associated [CalculatorExpr], and associating a [UnifiedReal] result with it.
     * All methods are thread-safe in the strong sense; they may be called asynchronously
     * at any time from any thread.
     */
    interface ExprResolver {
        /**
         * Retrieve the expression corresponding to [index].
         *
         * @param index the index of the expression to retrieve
         * @return the expression corresponding to index.
         */
        fun getExpr(index: Long): CalculatorExpr

        /**
         * Retrieve the degree mode associated with the expression at [index].
         *
         * @param index the index of the expression in question
         * @return the degree mode associated with the expression at index
         */
        fun getDegreeMode(index: Long): Boolean

        /**
         * Retrieve the stored result for the expression at [index], or return null.
         *
         * @param index the index of the expression whose result we want
         * @return the stored result for the expression at [index], or null.
         */
        fun getResult(index: Long): UnifiedReal?

        /**
         * Atomically test for an existing result, and set it if there was none.
         * Return the prior result if there was one, or the new one if there was not.
         * May only be called after getExpr.
         *
         * @param index index of the expression that we are interested in.
         * @param result the [UnifiedReal] we are to save at index [index].
         * @return if there was no [UnifiedReal] at [index] we return [result], otherwise we return
         * the [UnifiedReal] which already occupies position [index].
         */
        fun putResultIfAbsent(index: Long, result: UnifiedReal): UnifiedReal
    }

    /**
     * The kind of [Token] we are looking at.
     */
    enum class TokenKind {
        CONSTANT, OPERATOR, PRE_EVAL
    }

    /**
     * The [TokenKind] types of [Token] extend this class ([Constant], [Operator], and [PreEval]).
     */
    abstract class Token {
        /**
         * Used to query a [Token] for the [TokenKind] that if holds.
         *
         * @return the kind of [TokenKind] that this [Token] holds.
         */
        internal abstract fun kind(): TokenKind

        /**
         * Write token as either a very small Byte containing the TokenKind,
         * followed by data needed by subclass constructor,
         * or as a byte >= 0x20 directly describing the OPERATOR token.
         *
         * @param dataOutput the [DataOutput] that our [Token] should write our bytes to.
         */
        @Throws(IOException::class)
        internal abstract fun write(dataOutput: DataOutput)

        /**
         * Return a textual representation of the token.
         * The result is suitable for either display as part of the formula or TalkBack use.
         * It may be a SpannableString that includes added TalkBack information.
         *
         * @param context context used for converting button ids to strings
         * @return a [CharSequence] representing this [Token] for display use.
         */
        internal abstract fun toCharSequence(context: Context): CharSequence
    }

    /**
     * Representation of an OPERATOR token
     */
    private class Operator : Token {
        /**
         * We use the button resource id to represent the OPERATOR we hold.
         */
        val id: Int

        /**
         * Our constructor from the resource id of the operator button that was clicked. We just
         * save our parameter [resId] in our field [id].
         *
         * @param resId the resource id of the operator button that was clicked.
         */
        internal constructor(resId: Int) {
            id = resId
        }

        /**
         * Our constructor from a [Byte] representation of the operator we hold. We set our field
         * [id] to the key resource id found by the *fromByte* method of [KeyMaps] for our parameter
         * [op].
         *
         * @param op single byte encoding of the operator.
         */
        internal constructor(op: Byte) {
            id = KeyMaps.fromByte(op)
        }

        /**
         * Writes the [Byte] encoding of the operator we hold to our parameter [dataOutput]. We call
         * the *writeByte* method of [dataOutput] to write the low order 8 bits of the [Int] of the
         * [Byte] representation of [id] created by the *toByte* method of [KeyMaps] to our parameter
         * [dataOutput].
         *
         * @param dataOutput the [DataOutput] we are to write to.
         */
        @Throws(IOException::class)
        override fun write(dataOutput: DataOutput) {
            dataOutput.writeByte(KeyMaps.toByte(id).toInt())
        }

        public override fun toCharSequence(context: Context): CharSequence {
            val desc = KeyMaps.toDescriptiveString(context, id)
            return if (desc != null) {
                val result = SpannableString(KeyMaps.toString(context, id))
                val descSpan = TtsSpan.TextBuilder(desc).build()
                result.setSpan(descSpan, 0, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                result
            } else {
                KeyMaps.toString(context, id)
            }
        }

        override fun kind(): TokenKind {
            return TokenKind.OPERATOR
        }
    }

    /**
     * Representation of a (possibly incomplete) numerical constant.
     * Supports addition and removal of trailing characters; hence mutable.
     */
    private class Constant : Token, Cloneable {
        private var mSawDecimal: Boolean = false
        private var mWhole: String? = null  // String preceding decimal point.
        private var mFraction: String? = null // String after decimal point.
        private var mExponent: Int = 0  // Explicit exponent, only generated through addExponent.

        val isEmpty: Boolean
            get() = !mSawDecimal && mWhole!!.isEmpty()

        internal constructor() {
            mWhole = ""
            mFraction = ""
            // mSawDecimal = false;
            // mExponent = 0;
        }

        @Throws(IOException::class)
        internal constructor(dataInput: DataInput) {
            mWhole = dataInput.readUTF()
            val flags = dataInput.readByte().toInt()
            if (flags and SAW_DECIMAL != 0) {
                mSawDecimal = true
                mFraction = dataInput.readUTF()
            } else {
                // mSawDecimal = false;
                mFraction = ""
            }
            if (flags and HAS_EXPONENT != 0) {
                mExponent = dataInput.readInt()
            }
        }

        @Throws(IOException::class)
        override fun write(dataOutput: DataOutput) {
            val flags = ((if (mSawDecimal) SAW_DECIMAL else 0) or if (mExponent != 0) HAS_EXPONENT else 0).toByte()
            dataOutput.writeByte(TokenKind.CONSTANT.ordinal)
            dataOutput.writeUTF(mWhole)
            dataOutput.writeByte(flags.toInt())
            if (mSawDecimal) {
                dataOutput.writeUTF(mFraction)
            }
            if (mExponent != 0) {
                dataOutput.writeInt(mExponent)
            }
        }

        // Given a button press, append corresponding digit.
        // We assume id is a digit or decimal point.
        // Just return false if this was the second (or later) decimal point
        // in this constant.
        // Assumes that this constant does not have an exponent.
        fun add(id: Int): Boolean {
            if (id == R.id.dec_point) {
                if (mSawDecimal || mExponent != 0) return false
                mSawDecimal = true
                return true
            }
            val value = KeyMaps.digVal(id)
            if (mExponent != 0) {
                return if (Math.abs(mExponent) <= 10000) {
                    mExponent = if (mExponent > 0) {
                        10 * mExponent + value
                    } else {
                        10 * mExponent - value
                    }
                    true
                } else {  // Too large; refuse
                    false
                }
            }
            if (mSawDecimal) {
                mFraction += value
            } else {
                mWhole += value
            }
            return true
        }

        fun addExponent(exp: Int) {
            // Note that adding a 0 exponent is a no-op.  That's OK.
            mExponent = exp
        }

        /**
         * Undo the last add or remove last exponent digit.
         * Assumes the constant is nonempty.
         */
        fun delete() {
            when {
                mExponent != 0 -> mExponent /= 10
                // Once zero, it can only be added back with addExponent.
                mFraction!!.isNotEmpty() -> mFraction = mFraction!!.substring(0, mFraction!!.length - 1)
                mSawDecimal -> mSawDecimal = false
                else -> mWhole = mWhole!!.substring(0, mWhole!!.length - 1)
            }
        }

        /**
         * Produce human-readable string representation of constant, as typed.
         * We do add digit grouping separators to the whole number, even if not typed.
         * Result is internationalized.
         */
        override fun toString(): String {
            var result: String? = if (mExponent != 0) {
                mWhole
            } else {
                StringUtils.addCommas(mWhole, 0, mWhole!!.length)
            }
            if (mSawDecimal) {
                result += '.'.toString()
                result += mFraction
            }
            if (mExponent != 0) {
                result += "E$mExponent"
            }
            return KeyMaps.translateResult(result!!)
        }

        /**
         * Return BoundedRational representation of constant, if well-formed.
         * Result is never null.
         */
        @Throws(SyntaxException::class)
        fun toRational(): BoundedRational {
            var whole = mWhole
            if (whole!!.isEmpty()) {
                if (mFraction!!.isEmpty()) {
                    // Decimal point without digits.
                    throw SyntaxException()
                } else {
                    whole = "0"
                }
            }
            var num = BigInteger(whole + mFraction!!)
            var den = BigInteger.TEN.pow(mFraction!!.length)
            if (mExponent > 0) {
                num = num.multiply(BigInteger.TEN.pow(mExponent))
            }
            if (mExponent < 0) {
                den = den.multiply(BigInteger.TEN.pow(-mExponent))
            }
            return BoundedRational(num, den)
        }

        public override fun toCharSequence(context: Context): CharSequence {
            return toString()
        }

        public override fun kind(): TokenKind {
            return TokenKind.CONSTANT
        }

        // Override clone to make it public
        public override fun clone(): Any {
            val result = Constant()
            result.mWhole = mWhole
            result.mFraction = mFraction
            result.mSawDecimal = mSawDecimal
            result.mExponent = mExponent
            return result
        }

        companion object {
            private const val SAW_DECIMAL = 0x1
            private const val HAS_EXPONENT = 0x2
        }
    }

    /**
     * The "token" class for previously evaluated subexpressions.
     * We treat previously evaluated subexpressions as tokens.  These are inserted when we either
     * continue an expression after evaluating some of it, or copy an expression and paste it back
     * in.
     * This only contains enough information to allow us to display the expression in a
     * formula, or reevaluate the expression with the aid of an ExprResolver; we no longer
     * cache the result. The expression corresponding to the index can be obtained through
     * the ExprResolver, which looks it up in a subexpression database.
     * The representation includes a UnifiedReal value.  In order to
     * support saving and restoring, we also include the underlying expression itself, and the
     * context (currently just degree mode) used to evaluate it.  The short string representation
     * is also stored in order to avoid potentially expensive re-computation in the UI thread.
     */
    private class PreEval : Token {
        val mIndex: Long
        private val mShortRep: String  // Not internationalized.

        internal constructor(index: Long, shortRep: String) {
            mIndex = index
            mShortRep = shortRep
        }

        @Throws(IOException::class)
        public override// This writes out only a shallow representation of the result, without
        // information about subexpressions. To write out a deep representation, we
        // find referenced subexpressions, and iteratively write those as well.
        fun write(dataOutput: DataOutput) {
            dataOutput.writeByte(TokenKind.PRE_EVAL.ordinal)
            if (mIndex > Integer.MAX_VALUE || mIndex < Integer.MIN_VALUE) {
                // This would be millions of expressions per day for the life of the device.
                throw AssertionError("Expression index too big")
            }
            dataOutput.writeInt(mIndex.toInt())
            dataOutput.writeUTF(mShortRep)
        }

        @Throws(IOException::class)
        internal constructor(dataInput: DataInput) {
            mIndex = dataInput.readInt().toLong()
            mShortRep = dataInput.readUTF()
        }

        public override fun toCharSequence(context: Context): CharSequence {
            return KeyMaps.translateResult(mShortRep)
        }

        public override fun kind(): TokenKind {
            return TokenKind.PRE_EVAL
        }

        fun hasEllipsis(): Boolean {
            return mShortRep.lastIndexOf(KeyMaps.ELLIPSIS) != -1
        }
    }

    constructor() {
        mExpr = ArrayList()
    }

    @Suppress("unused")
    private constructor(expr: ArrayList<Token>) {
        mExpr = expr
    }

    /**
     * Construct CalculatorExpr, by reading it from dataInput.
     */
    @Throws(IOException::class)
    constructor(dataInput: DataInput) {
        mExpr = ArrayList()
        val size = dataInput.readInt()
        for (i in 0 until size) {
            mExpr.add(newToken(dataInput))
        }
    }

    /**
     * Write this expression to dataOutput.
     */
    @Throws(IOException::class)
    fun write(dataOutput: DataOutput) {
        val size = mExpr.size
        dataOutput.writeInt(size)
        for (i in 0 until size) {
            mExpr[i].write(dataOutput)
        }
    }

    /**
     * Use write() above to generate a byte array containing a serialized representation of
     * this expression.
     */
    fun toBytes(): ByteArray {
        val byteArrayStream = ByteArrayOutputStream()
        try {
            DataOutputStream(byteArrayStream).use { outputStream -> write(outputStream) }
        } catch (e: IOException) {
            // Impossible; No IO involved.
            throw AssertionError("Impossible IO exception", e)
        }

        return byteArrayStream.toByteArray()
    }

    /**
     * Does this expression end with a numeric constant?
     * As opposed to an operator or pre-evaluated expression.
     */
    fun hasTrailingConstant(): Boolean {
        val s = mExpr.size
        if (s == 0) {
            return false
        }
        val t = mExpr[s - 1]
        return t is Constant
    }

    /**
     * Does this expression end with a binary operator?
     */
    fun hasTrailingBinary(): Boolean {
        val s = mExpr.size
        if (s == 0) return false
        val t = mExpr[s - 1] as? Operator ?: return false
        return KeyMaps.isBinary(t.id)
    }

    /**
     * Append press of button with given id to expression.
     * If the insertion would clearly result in a syntax error, either just return false
     * and do nothing, or make an adjustment to avoid the problem.  We do the latter only
     * for unambiguous consecutive binary operators, in which case we delete the first
     * operator.
     */
    fun add(id: Int): Boolean {
        var s = mExpr.size
        val d = KeyMaps.digVal(id)
        val binary = KeyMaps.isBinary(id)
        val lastTok = if (s == 0) null else mExpr[s - 1]
        val lastOp = (lastTok as? Operator)?.id ?: 0
        // Quietly replace a trailing binary operator with another one, unless the second
        // operator is minus, in which case we just allow it as a unary minus.
        if (binary && !KeyMaps.isPrefix(id)) {
            if (s == 0 || lastOp == R.id.lparen || KeyMaps.isFunc(lastOp)
                    || KeyMaps.isPrefix(lastOp) && lastOp != R.id.op_sub) {
                return false
            }
            while (hasTrailingBinary()) {
                delete()
            }
            // s invalid and not used below.
        }
        val isConstPiece = d != KeyMaps.NOT_DIGIT || id == R.id.dec_point
        if (isConstPiece) {
            // Since we treat juxtaposition as multiplication, a constant can appear anywhere.
            if (s == 0) {
                mExpr.add(Constant())
                s++
            } else {
                val last = mExpr[s - 1]
                if (last !is Constant) {
                    if (last is PreEval) {
                        // Add explicit multiplication to avoid confusing display.
                        mExpr.add(Operator(R.id.op_mul))
                        s++
                    }
                    mExpr.add(Constant())
                    s++
                }
            }
            return (mExpr[s - 1] as Constant).add(id)
        } else {
            mExpr.add(Operator(id))
            return true
        }
    }

    /**
     * Add exponent to the constant at the end of the expression.
     * Assumes there is a constant at the end of the expression.
     */
    fun addExponent(exp: Int) {
        val lastTok = mExpr[mExpr.size - 1]
        (lastTok as Constant).addExponent(exp)
    }

    /**
     * Remove trailing op_add and op_sub operators.
     */
    fun removeTrailingAdditiveOperators() {
        while (true) {
            val s = mExpr.size
            if (s == 0) {
                break
            }
            val lastTok = mExpr[s - 1] as? Operator ?: break
            val lastOp = lastTok.id
            if (lastOp != R.id.op_add && lastOp != R.id.op_sub) {
                break
            }
            delete()
        }
    }

    /**
     * Append the contents of the argument expression.
     * It is assumed that the argument expression will not change, and thus its pieces can be
     * reused directly.
     */
    fun append(expr2: CalculatorExpr) {
        val s = mExpr.size
        val s2 = expr2.mExpr.size
        // Check that we're not concatenating Constant or PreEval tokens, since the result would
        // look like a single constant, with very mysterious results for the user.
        if (s != 0 && s2 != 0) {
            val last = mExpr[s - 1]
            val first = expr2.mExpr[0]
            if (first !is Operator && last !is Operator) {
                // Fudge it by adding an explicit multiplication.  We would have interpreted it as
                // such anyway, and this makes it recognizable to the user.
                mExpr.add(Operator(R.id.op_mul))
            }
        }
        for (i in 0 until s2) {
            mExpr.add(expr2.mExpr[i])
        }
    }

    /**
     * Undo the last key addition, if any.
     * Or possibly remove a trailing exponent digit.
     */
    fun delete() {
        val s = mExpr.size
        if (s == 0) {
            return
        }
        val last = mExpr[s - 1]
        if (last is Constant) {
            last.delete()
            if (!last.isEmpty) {
                return
            }
        }
        mExpr.removeAt(s - 1)
    }

    /**
     * Remove all tokens from the expression.
     */
    fun clear() {
        mExpr.clear()
    }

    /**
     * Returns a logical deep copy of the CalculatorExpr.
     * Operator and PreEval tokens are immutable, and thus aren't really copied.
     */
    fun clone(): Any {
        val result = CalculatorExpr()
        for (t in mExpr) {
            if (t is Constant) {
                result.mExpr.add(t.clone() as Token)
            } else {
                result.mExpr.add(t)
            }
        }
        return result
    }

    /**
     * Return a new expression consisting of a single token representing the current pre-evaluated
     * expression.
     * The caller supplies the expression index and short string representation.
     * The expression must have been previously evaluated.
     */
    fun abbreviate(index: Long, sr: String): CalculatorExpr {
        val result = CalculatorExpr()
        val t = PreEval(index, sr)
        result.mExpr.add(t)
        return result
    }

    /**
     * Internal evaluation functions return an EvalRet pair.
     * We compute rational (BoundedRational) results when possible, both as a performance
     * optimization, and to detect errors exactly when we can.
     */
    private class EvalRet internal constructor(var nextPos: Int // Next position (expression index) to be parsed.
                                               , val valueUR: UnifiedReal // Constructive Real result of evaluating subexpression.
    )

    /**
     * Internal evaluation functions take an EvalContext argument.
     */
    private class EvalContext {
        val mPrefixLength: Int // Length of prefix to evaluate. Not explicitly saved.
        val mDegreeMode: Boolean
        val mExprResolver: ExprResolver  // Reconstructed, not saved.

        // If we add any other kinds of evaluation modes, they go here.
        internal constructor(degreeMode: Boolean, len: Int, er: ExprResolver) {
            mDegreeMode = degreeMode
            mPrefixLength = len
            mExprResolver = er
        }

        @Suppress("unused")
        @Throws(IOException::class)
        internal constructor(dataInput: DataInput, len: Int, er: ExprResolver) {
            mDegreeMode = dataInput.readBoolean()
            mPrefixLength = len
            mExprResolver = er
        }

        @Suppress("unused")
        @Throws(IOException::class)
        internal fun write(out: DataOutput) {
            out.writeBoolean(mDegreeMode)
        }
    }

    private fun toRadians(x: UnifiedReal, ec: EvalContext): UnifiedReal {
        return if (ec.mDegreeMode) {
            x.multiply(UnifiedReal.RADIANS_PER_DEGREE)
        } else {
            x
        }
    }

    private fun fromRadians(x: UnifiedReal, ec: EvalContext): UnifiedReal {
        return if (ec.mDegreeMode) {
            x.divide(UnifiedReal.RADIANS_PER_DEGREE)
        } else {
            x
        }
    }

    // The following methods can all throw IndexOutOfBoundsException in the event of a syntax
    // error.  We expect that to be caught in eval below.

    private fun isOperatorUnchecked(i: Int, op: Int): Boolean {
        val t = mExpr[i]
        return if (t !is Operator) {
            false
        } else t.id == op
    }

    private fun isOperator(i: Int, op: Int, ec: EvalContext): Boolean {
        return if (i >= ec.mPrefixLength) {
            false
        } else isOperatorUnchecked(i, op)
    }

    class SyntaxException : Exception {
        constructor() : super()
        constructor(s: String) : super(s)
    }

    // The following functions all evaluate some kind of expression starting at position i in
    // mExpr in a specified evaluation context.  They return both the expression value (as
    // constructive real and, if applicable, as BoundedRational) and the position of the next token
    // that was not used as part of the evaluation.
    // This is essentially a simple recursive descent parser combined with expression evaluation.

    @Throws(SyntaxException::class)
    private fun evalUnary(i: Int, ec: EvalContext): EvalRet {
        val t = mExpr[i]
        if (t is Constant) {
            return EvalRet(i + 1, UnifiedReal(t.toRational()))
        }
        if (t is PreEval) {
            val index = t.mIndex
            var res = ec.mExprResolver.getResult(index)
            if (res == null) {
                // We try to minimize this recursive evaluation case, but currently don't
                // completely avoid it.
                res = nestedEval(index, ec.mExprResolver)
            }
            return EvalRet(i + 1, res)
        }
        val argVal: EvalRet
        when ((t as Operator).id) {
            R.id.const_pi -> return EvalRet(i + 1, UnifiedReal.PI)
            R.id.const_e -> return EvalRet(i + 1, UnifiedReal.E)
            R.id.op_sqrt ->
                // Seems to have highest precedence.
                // Does not add implicit paren.
                // Does seem to accept a leading minus.
                return if (isOperator(i + 1, R.id.op_sub, ec)) {
                    argVal = evalUnary(i + 2, ec)
                    EvalRet(argVal.nextPos, argVal.valueUR.negate().sqrt())
                } else {
                    argVal = evalUnary(i + 1, ec)
                    EvalRet(argVal.nextPos, argVal.valueUR.sqrt())
                }
            R.id.lparen -> {
                argVal = evalExpr(i + 1, ec)
                if (isOperator(argVal.nextPos, R.id.rparen, ec)) {
                    argVal.nextPos++
                }
                return EvalRet(argVal.nextPos, argVal.valueUR)
            }
            R.id.fun_sin -> {
                argVal = evalExpr(i + 1, ec)
                if (isOperator(argVal.nextPos, R.id.rparen, ec)) {
                    argVal.nextPos++
                }
                return EvalRet(argVal.nextPos, toRadians(argVal.valueUR, ec).sin())
            }
            R.id.fun_cos -> {
                argVal = evalExpr(i + 1, ec)
                if (isOperator(argVal.nextPos, R.id.rparen, ec)) {
                    argVal.nextPos++
                }
                return EvalRet(argVal.nextPos, toRadians(argVal.valueUR, ec).cos())
            }
            R.id.fun_tan -> {
                argVal = evalExpr(i + 1, ec)
                if (isOperator(argVal.nextPos, R.id.rparen, ec)) {
                    argVal.nextPos++
                }
                val arg = toRadians(argVal.valueUR, ec)
                return EvalRet(argVal.nextPos, arg.sin().divide(arg.cos()))
            }
            R.id.fun_ln -> {
                argVal = evalExpr(i + 1, ec)
                if (isOperator(argVal.nextPos, R.id.rparen, ec)) {
                    argVal.nextPos++
                }
                return EvalRet(argVal.nextPos, argVal.valueUR.ln())
            }
            R.id.fun_exp -> {
                argVal = evalExpr(i + 1, ec)
                if (isOperator(argVal.nextPos, R.id.rparen, ec)) {
                    argVal.nextPos++
                }
                return EvalRet(argVal.nextPos, argVal.valueUR.exp())
            }
            R.id.fun_log -> {
                argVal = evalExpr(i + 1, ec)
                if (isOperator(argVal.nextPos, R.id.rparen, ec)) {
                    argVal.nextPos++
                }
                return EvalRet(argVal.nextPos, argVal.valueUR.ln().divide(UnifiedReal.TEN.ln()))
            }
            R.id.fun_arcsin -> {
                argVal = evalExpr(i + 1, ec)
                if (isOperator(argVal.nextPos, R.id.rparen, ec)) {
                    argVal.nextPos++
                }
                return EvalRet(argVal.nextPos, fromRadians(argVal.valueUR.asin(), ec))
            }
            R.id.fun_arccos -> {
                argVal = evalExpr(i + 1, ec)
                if (isOperator(argVal.nextPos, R.id.rparen, ec)) {
                    argVal.nextPos++
                }
                return EvalRet(argVal.nextPos, fromRadians(argVal.valueUR.acos(), ec))
            }
            R.id.fun_arctan -> {
                argVal = evalExpr(i + 1, ec)
                if (isOperator(argVal.nextPos, R.id.rparen, ec)) {
                    argVal.nextPos++
                }
                return EvalRet(argVal.nextPos, fromRadians(argVal.valueUR.atan(), ec))
            }
            else -> throw SyntaxException("Unrecognized token in expression")
        }
    }

    @Throws(SyntaxException::class)
    private fun evalSuffix(i: Int, ec: EvalContext): EvalRet {
        val tmp = evalUnary(i, ec)
        var cpos = tmp.nextPos
        var valueTemp = tmp.valueUR

        var isFact = isOperator(cpos, R.id.op_fact, ec)
        var isSquared = isOperator(cpos, R.id.op_sqr, ec)
        var isPct = isOperator(cpos, R.id.op_pct, ec)
        while (isFact || isSquared || isPct) {
            valueTemp = when {
                isFact -> valueTemp.fact()
                isSquared -> valueTemp.multiply(valueTemp)
                /* percent */
                else -> valueTemp.multiply(ONE_HUNDREDTH)
            }
            ++cpos
            isFact = isOperator(cpos, R.id.op_fact, ec)
            isSquared = isOperator(cpos, R.id.op_sqr, ec)
            isPct = isOperator(cpos, R.id.op_pct, ec)
        }
        return EvalRet(cpos, valueTemp)
    }

    @Throws(SyntaxException::class)
    private fun evalFactor(i: Int, ec: EvalContext): EvalRet {
        val result1 = evalSuffix(i, ec)
        var cpos = result1.nextPos  // current position
        var value = result1.valueUR   // value so far
        if (isOperator(cpos, R.id.op_pow, ec)) {
            val exp = evalSignedFactor(cpos + 1, ec)
            cpos = exp.nextPos
            value = value.pow(exp.valueUR)
        }
        return EvalRet(cpos, value)
    }

    @Throws(SyntaxException::class)
    private fun evalSignedFactor(i: Int, ec: EvalContext): EvalRet {
        val negative = isOperator(i, R.id.op_sub, ec)
        var cpos = if (negative) i + 1 else i
        val tmp = evalFactor(cpos, ec)
        cpos = tmp.nextPos
        val result = if (negative) tmp.valueUR.negate() else tmp.valueUR
        return EvalRet(cpos, result)
    }

    private fun canStartFactor(i: Int): Boolean {
        if (i >= mExpr.size) return false
        val t = mExpr[i] as? Operator ?: return true
        val id = t.id
        if (KeyMaps.isBinary(id)) return false
        return when (id) {
            R.id.op_fact, R.id.rparen -> false
            else -> true
        }
    }

    @Throws(SyntaxException::class)
    private fun evalTerm(i: Int, ec: EvalContext): EvalRet {
        var tmp = evalSignedFactor(i, ec)
        var cpos = tmp.nextPos   // Current position in expression.

        var isMul = isOperator(cpos, R.id.op_mul, ec)
        var isDiv = isOperator(cpos, R.id.op_div, ec)
        var valueTemp = tmp.valueUR    // Current value.
        while (isMul || isDiv || canStartFactor(cpos)) {
            if (isMul || isDiv) ++cpos
            tmp = evalSignedFactor(cpos, ec)
            valueTemp = if (isDiv) {
                valueTemp.divide(tmp.valueUR)
            } else {
                valueTemp.multiply(tmp.valueUR)
            }
            cpos = tmp.nextPos
            isMul = isOperator(cpos, R.id.op_mul, ec)
            isDiv = isOperator(cpos, R.id.op_div, ec)
        }
        return EvalRet(cpos, valueTemp)
    }

    /**
     * Is the subexpression starting at nextPos a simple percent constant?
     * This is used to recognize expressions like 200+10%, which we handle specially.
     * This is defined as a Constant or PreEval token, followed by a percent sign, and followed
     * by either nothing or an additive operator.
     * Note that we are intentionally far more restrictive in recognizing such expressions than
     * e.g. http://blogs.msdn.com/b/oldnewthing/archive/2008/01/10/7047497.aspx .
     * When in doubt, we fall back to the the naive interpretation of % as 1/100.
     * Note that 100+(10)% yields 100.1 while 100+10% yields 110.  This may be controversial,
     * but is consistent with Google web search.
     */
    private fun isPercent(pos: Int): Boolean {
        if (mExpr.size < pos + 2 || !isOperatorUnchecked(pos + 1, R.id.op_pct)) {
            return false
        }
        val number = mExpr[pos]
        if (number is Operator) {
            return false
        }
        if (mExpr.size == pos + 2) {
            return true
        }
        if (mExpr[pos + 2] !is Operator) {
            return false
        }
        val op = mExpr[pos + 2] as Operator
        return op.id == R.id.op_add || op.id == R.id.op_sub || op.id == R.id.rparen
    }

    /**
     * Compute the multiplicative factor corresponding to an N% addition or subtraction.
     * @param pos position of Constant or PreEval expression token corresponding to N.
     * @param isSubtraction this is a subtraction, as opposed to addition.
     * @param ec usable evaluation context; only length matters.
     * @return UnifiedReal value and position, which is nextPos + 2, i.e. after percent sign
     */
    @Throws(SyntaxException::class)
    private fun getPercentFactor(pos: Int, isSubtraction: Boolean, ec: EvalContext): EvalRet {
        val tmp = evalUnary(pos, ec)
        var valueTemp = if (isSubtraction) tmp.valueUR.negate() else tmp.valueUR
        valueTemp = UnifiedReal.ONE.add(valueTemp.multiply(ONE_HUNDREDTH))
        return EvalRet(pos + 2 /* after percent sign */, valueTemp)
    }

    @Throws(SyntaxException::class)
    private fun evalExpr(i: Int, ec: EvalContext): EvalRet {
        var tmp = evalTerm(i, ec)
        var cpos = tmp.nextPos

        var isPlus = isOperator(cpos, R.id.op_add, ec)
        var isMinus = isOperator(cpos, R.id.op_sub, ec)

        var valueTemp = tmp.valueUR
        while (isPlus || isMinus) {
            if (isPercent(cpos + 1)) {
                tmp = getPercentFactor(cpos + 1, !isPlus, ec)
                valueTemp = valueTemp.multiply(tmp.valueUR)
            } else {
                tmp = evalTerm(cpos + 1, ec)
                valueTemp = if (isPlus) {
                    valueTemp.add(tmp.valueUR)
                } else {
                    valueTemp.subtract(tmp.valueUR)
                }
            }
            cpos = tmp.nextPos
            isPlus = isOperator(cpos, R.id.op_add, ec)
            isMinus = isOperator(cpos, R.id.op_sub, ec)
        }
        return EvalRet(cpos, valueTemp)
    }

    /**
     * Return the starting position of the sequence of trailing binary operators.
     */
    private fun trailingBinaryOpsStart(): Int {
        var result = mExpr.size
        while (result > 0) {
            val last = mExpr[result - 1] as? Operator ?: break
            if (!KeyMaps.isBinary(last.id)) break
            --result
        }
        return result
    }

    /**
     * Is the current expression worth evaluating?
     */
    fun hasInterestingOps(): Boolean {
        val last = trailingBinaryOpsStart()
        var first = 0
        if (last > first && isOperatorUnchecked(first, R.id.op_sub)) {
            // Leading minus is not by itself interesting.
            first++
        }
        for (i in first until last) {
            val t1 = mExpr[i]
            if (t1 is Operator || t1 is PreEval && t1.hasEllipsis()) {
                return true
            }
        }
        return false
    }

    /**
     * Does the expression contain trig operations?
     */
    fun hasTrigFuncs(): Boolean {
        for (t in mExpr) {
            if (t is Operator) {
                if (KeyMaps.isTrigFunc(t.id)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Add the indices of unevaluated PreEval expressions embedded in the current expression to
     * argument.  This includes only directly referenced expressions e, not those indirectly
     * referenced by e. If the index was already present, it is not added. If the argument
     * contained no duplicates, the result will not either. New indices are added to the end of
     * the list.
     */
    private fun addReferencedExprs(list: ArrayList<Long>, er: ExprResolver) {
        for (t in mExpr) {
            if (t is PreEval) {
                val index = t.mIndex
                if (er.getResult(index) == null && !list.contains(index)) {
                    list.add(index)
                }
            }
        }
    }

    /**
     * Return a list of unevaluated expressions transitively referenced by the current one.
     * All expressions in the resulting list will have had er.getExpr() called on them.
     * The resulting list is ordered such that evaluating expressions in list order
     * should trigger few recursive evaluations.
     */
    fun getTransitivelyReferencedExprs(er: ExprResolver): ArrayList<Long> {
        // We could avoid triggering any recursive evaluations by actually building the
        // dependency graph and topologically sorting it. Note that sorting by index works
        // for positive and negative indices separately, but not their union. Currently we
        // just settle for reverse breadth-first-search order, which handles the common case
        // of simple dependency chains well.
        val list = ArrayList<Long>()
        var scanned = 0  // We've added expressions referenced by [0, scanned) to the list
        addReferencedExprs(list, er)
        while (scanned != list.size) {
            er.getExpr(list[scanned++]).addReferencedExprs(list, er)
        }
        list.reverse()
        return list
    }

    /**
     * Evaluate the expression at the given index to a UnifiedReal.
     * Both saves and returns the result.
     */
    @Throws(SyntaxException::class)
    fun nestedEval(index: Long, er: ExprResolver): UnifiedReal {
        val nestedExpr = er.getExpr(index)
        val newEc = EvalContext(er.getDegreeMode(index),
                nestedExpr.trailingBinaryOpsStart(), er)
        val newRes = nestedExpr.evalExpr(0, newEc)
        return er.putResultIfAbsent(index, newRes.valueUR)
    }

    /**
     * Evaluate the expression excluding trailing binary operators.
     * Errors result in exceptions, most of which are unchecked.  Should not be called
     * concurrently with modification of the expression.  May take a very long time; avoid calling
     * from UI thread.
     *
     * @param degreeMode use degrees rather than radians
     */
    @Throws(SyntaxException::class)
    fun eval(degreeMode: Boolean, er: ExprResolver): UnifiedReal
    // And unchecked exceptions thrown by UnifiedReal, CR,
    // and BoundedRational.
    {
        // First evaluate all indirectly referenced expressions in increasing index order.
        // This ensures that subsequent evaluation never encounters an embedded PreEval
        // expression that has not been previously evaluated.
        // We could do the embedded evaluations recursively, but that risks running out of
        // stack space.
        val referenced = getTransitivelyReferencedExprs(er)
        for (index in referenced) {
            nestedEval(index, er)
        }
        try {
            // We currently never include trailing binary operators, but include other trailing
            // operators.  Thus we usually, but not always, display results for prefixes of valid
            // expressions, and don't generate an error where we previously displayed an instant
            // result.  This reflects the Android L design.
            val prefixLen = trailingBinaryOpsStart()
            val ec = EvalContext(degreeMode, prefixLen, er)
            val res = evalExpr(0, ec)
            if (res.nextPos != prefixLen) {
                throw SyntaxException("Failed to parse full expression")
            }
            return res.valueUR
        } catch (e: IndexOutOfBoundsException) {
            throw SyntaxException("Unexpected expression end")
        }

    }

    // Produce a string representation of the expression itself
    fun toSpannableStringBuilder(context: Context): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        for (t in mExpr) {
            ssb.append(t.toCharSequence(context))
        }
        return ssb
    }

    companion object {

        private val tokenKindValues = TokenKind.values()
        @Suppress("unused")
        private val BIG_MILLION = BigInteger.valueOf(1000000)
        @Suppress("unused")
        private val BIG_BILLION = BigInteger.valueOf(1000000000)

        /**
         * Read token from dataInput.
         */
        @Throws(IOException::class)
        fun newToken(dataInput: DataInput): Token {
            val kindByte = dataInput.readByte()
            if (kindByte < 0x20) {
                when (tokenKindValues[kindByte.toInt()]) {
                    TokenKind.CONSTANT -> return Constant(dataInput)
                    TokenKind.PRE_EVAL -> {
                        val pe = PreEval(dataInput)
                        return if (pe.mIndex == -1L) {
                            // Database corrupted by earlier bug.
                            // Return a conspicuously wrong placeholder that won't lead to a crash.
                            val result = Constant()
                            result.add(R.id.dec_point)
                            result
                        } else {
                            pe
                        }
                    }
                    else -> throw IOException("Bad save file format")
                }
            } else {
                return Operator(kindByte)
            }
        }

        private val ONE_HUNDREDTH = UnifiedReal(100).inverse()
    }
}
