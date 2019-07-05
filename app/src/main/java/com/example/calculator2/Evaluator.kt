/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.calculator2

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Handler
import android.preference.PreferenceManager
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import android.text.Spannable
import android.util.Log
import androidx.annotation.RequiresApi

import java.io.ByteArrayInputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Random
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * This implements the calculator evaluation logic.
 * Logically this maintains a signed integer indexed set of expressions, one of which
 * is distinguished as the main expression.
 * The main expression is constructed and edited with append(), delete(), etc.
 * An evaluation can then be started with a call to evaluateAndNotify() or requireResult().
 * This starts an asynchronous computation, which requests display of the initial result, when
 * available.  When initial evaluation is complete, it calls the associated listener's
 * onEvaluate() method.  This occurs in a separate event, possibly quite a bit later.  Once a
 * result has been computed, and before the underlying expression is modified, the
 * stringGet(index) method may be used to produce Strings that represent approximations to various
 * precisions.
 *
 * Actual expressions being evaluated are represented as [CalculatorExpr]s.
 *
 * The Evaluator holds the expressions and all associated state needed for evaluating
 * them.  It provides functionality for saving and restoring this state.  However the underlying
 * CalculatorExprs are exposed to the client, and may be directly accessed after cancelling any
 * in-progress computations by invoking the cancelAll() method.
 *
 * When evaluation is requested, we invoke the eval() method on the CalculatorExpr from a
 * background AsyncTask.  A subsequent stringGet() call for the same expression index returns
 * immediately, though it may return a result containing placeholder ' ' characters.  If we had to
 * return placeholder characters, we start a background task, which invokes the onReevaluate()
 * callback when it completes.  In either case, the background task computes the appropriate
 * result digits by evaluating the UnifiedReal returned by CalculatorExpr.eval() to the required
 * precision.
 *
 * We cache the best decimal approximation we have already computed.  We compute generously to
 * allow for some scrolling without re-computation and to minimize the chance of digits flipping
 * from "0000" to "9999".  The best known result approximation is maintained as a string by
 * mResultString (and often in a different format by the CR representation of the result).  When
 * we are in danger of not having digits to display in response to further scrolling, we also
 * initiate a background computation to higher precision, as if we had generated placeholder
 * characters.
 *
 * The code is designed to ensure that the error in the displayed result (excluding any
 * placeholder characters) is always strictly less than 1 in the last displayed digit.  Typically
 * we actually display a prefix of a result that has this property and additionally is computed to
 * a significantly higher precision.  Thus we almost always round correctly towards zero.  (Fully
 * correct rounding towards zero is not computable, at least given our representation.)
 *
 * Initial expression evaluation may time out.  This may happen in the case of domain errors such
 * as division by zero, or for large computations.  We do not currently time out reevaluations to
 * higher precision, since the original evaluation precluded a domain error that could result in
 * non-termination.  (We may discover that a presumed zero result is actually slightly negative
 * when re-evaluated; but that results in an exception, which we can handle.)  The user can abort
 * either kind of computation.
 *
 * We ensure that only one evaluation of either kind (AsyncEvaluator or AsyncReevaluator) is
 * running at a time.
 */
@Suppress("MemberVisibilityCanBePrivate")
@RequiresApi(Build.VERSION_CODES.N)
class Evaluator internal constructor(// Context for database helper.
        private val mContext: Context) : CalculatorExpr.ExprResolver {

    /**
     * Our [CharMetricsInfo] that can be used when we are really only interested in computing
     * short representations to be embedded on formulas.
     */
    private val mDummyCharMetricsInfo = DummyCharMetricsInfo()
    /**
     * Index of "saved" expression mirroring clipboard. 0 if unused.
     */
    private var mSavedIndex: Long = 0
    /**
     * To update e.g. "memory" contents, we copy the corresponding expression to a permanent index,
     * and then remember that index. Index of "memory" expression. 0 if unused.
     */
    private var mMemoryIndex: Long = 0
    /**
     * Listener that reports changes to the state (empty/filled) of memory. Protected for testing.
     */
    private var mCallback: Callback? = null

    /**
     * A hopefully unique name associated with the "saved" expression mirroring clipboard, it is
     * saved to the shared preference file under the key KEY_PREF_SAVED_NAME ("saved_name") by our
     * [capture] method and restored by the *init* block every time a new instance of [Evaluator]
     * is constructed.
     */
    private var mSavedName: String? = null

    /**
     * The main expression may have changed since the last evaluation in ways that would affect its
     * value.
     */
    private var mChangedValue: Boolean = false

    /**
     * The main expression contains trig functions.
     */
    private var mHasTrigFuncs: Boolean = false

    /**
     * Cache of the [ExprInfo] from our [ExpressionDB] which we have had reason to reference since
     * we were constructed.
     */
    private val mExprs = ConcurrentHashMap<Long, ExprInfo>()

    /**
     * The database holding persistent expressions.
     */
    private val mExprDB: ExpressionDB

    /**
     * The [ExprInfo] of the current main expression (it is stored under the key MAIN_INDEX in our
     * [ExprInfo] cache [mExprs]).
     */
    private var mMainExpr: ExprInfo? = null  //  == mExprs.get(MAIN_INDEX)

    /**
     * The [SharedPreferences] file which we use to persist some important state information.
     */
    private val mSharedPrefs: SharedPreferences

    /**
     * The [Handler] which we use to schedule evaluation timeouts.
     */
    private val mTimeoutHandler: Handler  // Used to schedule evaluation timeouts.

    /**
     * This *interface* defines callbacks which users of [Evaluator] instances can implement in
     * order to be informed of the state of the evaluation we are performing.
     */
    interface EvaluationListener {
        /**
         * Called if evaluation was explicitly cancelled or evaluation timed out.
         *
         * @param index the index of the the expression which was cancelled.
         */
        fun onCancelled(index: Long)

        /**
         * Called if evaluation resulted in an error.
         *
         * @param index the index of the the expression which was cancelled.
         * @param errorId the resource ID of the string describing the error.
         */
        fun onError(index: Long, errorId: Int)

        /**
         * Called if evaluation completed normally.
         *
         * @param index index of expression whose evaluation completed
         * @param initPrecOffset the offset used for initial evaluation
         * @param msdIndex index of first non-zero digit in the computed result string
         * @param lsdOffset offset of last digit in result if result has finite decimal
         * expansion
         * @param truncatedWholePart the integer part of the result
         */
        fun onEvaluate(index: Long, initPrecOffset: Int, msdIndex: Int, lsdOffset: Int,
                       truncatedWholePart: String)

        /**
         * Called in response to a reevaluation request, once more precision is available.
         * Typically the listener wil respond by calling stringGet() to retrieve the new
         * better approximation.
         *
         * @param index the index of the the expression which was reevaluated.
         */
        fun onReevaluate(index: Long)   // More precision is now available; please redraw.
    }

    /**
     * A query interface for derived information based on character widths.
     * This provides information we need to calculate the "preferred precision offset" used
     * to display the initial result. It's used to compute the number of digits we can actually
     * display. All methods are callable from any thread.
     */
    interface CharMetricsInfo {
        /**
         * Return the maximum number of (adjusted, digit-width) characters that will fit in the
         * result display.  May be called asynchronously from non-UI thread.
         *
         * @return the maximum number of characters that will fit in the result display.
         */
        fun maxCharsGet(): Int

        /**
         * Return the number of additional digit widths required to add digit separators to the
         * supplied string prefix. The prefix consists of the first len characters of string s,
         * which is presumed to represent a whole number. Callable from non-UI thread. Returns
         * zero if metrics information is not yet available.
         *
         * @param s the string we are to insert digit separators into.
         * @param len the length of the prefix string in [s] we are to consider.
         * @return the number of additional digit widths required to add digit separators to the
         * supplied string prefix (the first [len] characters of the string [s] is the prefix).
         */
        fun separatorChars(s: String, len: Int): Float

        /**
         * Return extra width credit for presence of a decimal point, as fraction of a digit width.
         * May be called by non-UI thread.
         *
         * @return fraction of a digit width saved by lack of a decimal point in the display
         */
        fun decimalCreditGet(): Float

        /**
         * Return extra width credit for absence of ellipsis, as fraction of a digit width.
         * May be called by non-UI thread.
         *
         * @return the faction of a digit width available when there is no ellipsis in the display.
         */
        fun noEllipsisCreditGet(): Float
    }

    /**
     * A [CharMetricsInfo] that can be used when we are really only interested in computing
     * short representations to be embedded on formulas.
     */
    private inner class DummyCharMetricsInfo : CharMetricsInfo {
        /**
         * Return the maximum number of (adjusted, digit-width) characters that will fit in the
         * result display. We just return the constant SHORT_TARGET_LENGTH (8) plus 10.
         *
         * @return the maximum number of characters that will fit in the result display.
         */
        override fun maxCharsGet(): Int {
            return SHORT_TARGET_LENGTH + 10
        }

        /**
         * Return the number of additional digit widths required to add digit separators to the
         * supplied string prefix. The prefix consists of the first len characters of string s,
         * which is presumed to represent a whole number. We just return 0f.
         *
         * @param s the string we are to insert digit separators into.
         * @param len the length of the prefix string in [s] we are to consider.
         * @return the number of additional digit widths required to add digit separators to the
         * supplied string prefix (the first [len] characters of the string [s] is the prefix).
         */
        override fun separatorChars(s: String, len: Int): Float {
            return 0f
        }

        /**
         * Return extra width credit for presence of a decimal point, as fraction of a digit width.
         * We just return 0f.
         *
         * @return fraction of a digit width saved by lack of a decimal point in the display
         */
        override fun decimalCreditGet(): Float {
            return 0f
        }

        /**
         * Return extra width credit for absence of ellipsis, as fraction of a digit width.
         * We just return 0f.
         *
         * @return the faction of a digit width available when there is no ellipsis in the display.
         */
        override fun noEllipsisCreditGet(): Float {
            return 0f
        }
    }

    /**
     * An individual [CalculatorExpr], together with its evaluation state. Only the main expression
     * may be changed in-place. The HISTORY_MAIN_INDEX expression is periodically reset to be a
     * fresh immutable copy of the main expression. All other expressions are only added and never
     * removed. The expressions themselves are never modified.
     *
     * All fields other than [mExpr] and [mVal] are touched only by the UI thread. For MAIN_INDEX,
     * [mExpr] and [mVal] may change, but are also only ever touched by the UI thread. For all other
     * expressions, [mExpr] does not change once the [ExprInfo] has been (atomically) added to
     * [mExprs]. [mVal] may be asynchronously set by any thread, but we take care that it
     * does not change after that. [mDegreeMode] is handled exactly like [mExpr].
     */
    inner class ExprInfo(var mExpr: CalculatorExpr  // The expression itself.
                                 , var mDegreeMode: Boolean  // Evaluating in degree, not radian, mode.
    ) {

        /**
         * Currently running expression evaluator, if any.  This is either an [AsyncEvaluator]
         * (if [mResultString] == *null* or it's obsolete), or an [AsyncReevaluator].
         * We arrange that only one evaluator is active at a time, in part by maintaining
         * two separate [ExprInfo] structure for the main and history view, so that they can
         * arrange for independent evaluators.
         */
        var mEvaluator: AsyncTask<*, *, *>? = null

        // The remaining fields are valid only if an evaluation completed successfully.

        /**
         * [mVal] always points to an [AtomicReference], but that may be null. This is the
         * [UnifiedReal] that results from evaluating our expression using the `eval` method
         * of [CalculatorExpr].
         */
        var mVal: AtomicReference<UnifiedReal> = AtomicReference()

        /**
         * We cache the best known decimal result in [mResultString]. Whenever that is non-null, it
         * is computed to exactly [mResultStringOffset], which is always > 0. Valid only if
         * [mResultString] is non-null and (for the main expression) [mChangedValue] is *false*.
         * ERRONEOUS_RESULT indicates evaluation resulted in an error.
         */
        var mResultString: String? = null
        var mResultStringOffset = 0
        // Number of digits to which (possibly incomplete) evaluation has been requested.
        // Only accessed by UI thread.
        var mResultStringOffsetReq = 0
        // Position of most significant digit in current cached result, if determined.  This is just
        // the index in mResultString holding the msd.
        var mMsdIndex = INVALID_MSD
        // Long timeout needed for evaluation?
        var mLongTimeout = false
        var mTimeStamp: Long = 0

    }

    private fun setMainExpr(expr: ExprInfo) {
        mMainExpr = expr
        mExprs[MAIN_INDEX] = expr
    }

    init {
        setMainExpr(ExprInfo(CalculatorExpr(), false))
        mSavedName = "none"
        mTimeoutHandler = Handler()

        mExprDB = ExpressionDB(mContext)
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
        mMainExpr!!.mDegreeMode = mSharedPrefs.getBoolean(KEY_PREF_DEGREE_MODE, false)
        val savedIndex = mSharedPrefs.getLong(KEY_PREF_SAVED_INDEX, 0L)
        val memoryIndex = mSharedPrefs.getLong(KEY_PREF_MEMORY_INDEX, 0L)
        if (savedIndex != 0L && savedIndex != -1L /* Recover from old corruption */) {
            setSavedIndexWhenEvaluated(savedIndex)
        }
        if (memoryIndex != 0L && memoryIndex != -1L) {
            setMemoryIndexWhenEvaluated(memoryIndex, false /* no need to persist again */)
        }
        mSavedName = mSharedPrefs.getString(KEY_PREF_SAVED_NAME, "none")
    }

    /**
     * Retrieve minimum expression index.
     * This is the minimum over all expressions, including uncached ones residing only
     * in the data base. If no expressions with negative indices were preserved, this will
     * return a small negative predefined constant.
     * May be called from any thread, but will block until the database is opened.
     */
    fun minIndexGet(): Long {
        return mExprDB.minIndex
    }

    /**
     * Retrieve maximum expression index.
     * This is the maximum over all expressions, including uncached ones residing only
     * in the data base. If no expressions with positive indices were preserved, this will
     * return 0.
     * May be called from any thread, but will block until the database is opened.
     */
    fun maxIndexGet(): Long {
        return mExprDB.maxIndex
    }

    /**
     * Set the Callback for showing dialogs and notifying the UI about memory state changes.
     * @param callback the Callback we should call
     */
    fun setCallback(callback: Callback) {
        mCallback = callback
    }

    /**
     * Does the expression index refer to a transient and mutable expression?
     */
    private fun isMutableIndex(index: Long): Boolean {
        return index == MAIN_INDEX || index == HISTORY_MAIN_INDEX
    }

    /**
     * Result of initial asynchronous result computation.
     * Represents either an error or a result computed to an initial evaluation precision.
     */
    class InitialResult {
        val errorResourceId: Int    // Error string or INVALID_RES_ID.
        val unifiedReal: UnifiedReal        // Constructive real value.
        val newResultString: String       // Null iff it can't be computed.
        val newResultStringOffset: Int
        val initDisplayOffset: Int
        internal val isError: Boolean
            get() = errorResourceId != Calculator2.INVALID_RES_ID

        internal constructor(v: UnifiedReal, s: String, p: Int, idp: Int) {
            errorResourceId = Calculator2.INVALID_RES_ID
            unifiedReal = v
            newResultString = s
            newResultStringOffset = p
            initDisplayOffset = idp
        }

        internal constructor(errorId: Int) {
            errorResourceId = errorId
            unifiedReal = UnifiedReal.ZERO
            newResultString = "BAD"
            newResultStringOffset = 0
            initDisplayOffset = 0
        }
    }

    private fun displayCancelledMessage() {
        if (mCallback != null) {
            mCallback!!.showMessageDialog(0, R.string.cancelled, 0, null)
        }
    }

    // Timeout handling.
    // Expressions are evaluated with a sort timeout or a long timeout.
    // Each implies different maxima on both computation time and bit length.
    // We recheck bit length separately to avoid wasting time on decimal conversions that are
    // destined to fail.

    /**
     * Return the timeout in milliseconds.
     * @param longTimeout a long timeout is in effect
     */
    private fun timeoutGet(longTimeout: Boolean): Long {
        return (if (longTimeout) 15000 else 2000).toLong()
        // Exceeding a few tens of seconds increases the risk of running out of memory
        // and impacting the rest of the system.
    }

    /**
     * Return the maximum number of bits in the result.  Longer results are assumed to time out.
     * @param longTimeout a long timeout is in effect
     */
    private fun maxResultBitsGet(longTimeout: Boolean): Int {
        return if (longTimeout) 700000 else 240000
    }

    private fun displayTimeoutMessage(longTimeout: Boolean) {
        if (mCallback != null) {
            mCallback!!.showMessageDialog(R.string.dialog_timeout, R.string.timeout,
                    if (longTimeout) 0 else R.string.ok_remove_timeout, TIMEOUT_DIALOG_TAG)
        }
    }

    fun setLongTimeout() {
        mMainExpr!!.mLongTimeout = true
    }

    /**
     * Compute initial cache contents and result when we're good and ready.
     * We leave the expression display up, with scrolling disabled, until this computation
     * completes.  Can result in an error display if something goes wrong.  By default we set a
     * timeout to catch runaway computations.
     */
    @SuppressLint("StaticFieldLeak")
    internal inner class AsyncEvaluator(private val mIndex: Long  //  Expression index.
                                        , private val mListener: EvaluationListener?  // Completion callback.
                                        , private val mCharMetricsInfo: CharMetricsInfo  // Where to get result size information.
                                        , private val mDm: Boolean  // degrees
                                        ,
                                        var mRequired: Boolean // Result was requested by user.
    ) : AsyncTask<Void, Void, InitialResult>() {
        private var mQuiet: Boolean = false  // Suppress cancellation message.
        private var mTimeoutRunnable: Runnable? = null
        private val mExprInfo: ExprInfo?  // Current expression.

        init {
            mQuiet = !mRequired || mIndex != MAIN_INDEX
            mExprInfo = mExprs[mIndex]

            if (mExprInfo!!.mEvaluator != null) {
                throw AssertionError("Evaluation already in progress!")
            }
        }

        private fun handleTimeout() {
            // Runs in UI thread.
            val running = status != Status.FINISHED
            if (running && cancel(true)) {

                mExprs[mIndex]!!.mEvaluator = null
                if (mRequired && mIndex == MAIN_INDEX) {
                    // Replace mExpr with clone to avoid races if task still runs for a while.
                    mMainExpr!!.mExpr = mMainExpr!!.mExpr.clone() as CalculatorExpr
                    suppressCancelMessage()
                    displayTimeoutMessage(mExprInfo!!.mLongTimeout)
                }
            }
        }

        fun suppressCancelMessage() {
            mQuiet = true
        }

        override fun onPreExecute() {
            var timeout = if (mRequired) timeoutGet(mExprInfo!!.mLongTimeout) else QUICK_TIMEOUT
            if (mIndex != MAIN_INDEX) {
                // We evaluated the expression before with the current timeout, so this shouldn't
                // ever time out. We evaluate it with a ridiculously long timeout to avoid running
                // down the battery if something does go wrong. But we only log such timeouts, and
                // invoke the listener with onCancelled.
                timeout = NON_MAIN_TIMEOUT
            }
            mTimeoutRunnable = Runnable { handleTimeout() }
            mTimeoutHandler.removeCallbacks(mTimeoutRunnable)
            mTimeoutHandler.postDelayed(mTimeoutRunnable, timeout)
        }

        /**
         * Is a computed result too big for decimal conversion?
         */
        private fun isTooBig(res: UnifiedReal): Boolean {
            val maxBits = if (mRequired)
                maxResultBitsGet(mExprInfo!!.mLongTimeout)
            else
                QUICK_MAX_RESULT_BITS
            return res.approxWholeNumberBitsGreaterThan(maxBits)
        }

        override fun doInBackground(vararg nothing: Void): InitialResult {
            try {
                // mExpr does not change while we are evaluating; thus it's OK to read here.
                var res: UnifiedReal? = mExprInfo!!.mVal.get()
                if (res == null) {
                    try {
                        res = mExprInfo.mExpr.eval(mDm, this@Evaluator)
                        if (isCancelled) {
                            // TODO: This remains very slightly racy. Fix this.
                            throw CR.AbortedException()
                        }
                        res = putResultIfAbsent(mIndex, res)
                    } catch (e: StackOverflowError) {
                        // Absurdly large integer exponents can cause this. There might be other
                        // examples as well. Treat it as a timeout.
                        return InitialResult(R.string.timeout)
                    }

                }
                if (isTooBig(res)) {
                    // Avoid starting a long un-interruptible decimal conversion.
                    return InitialResult(R.string.timeout)
                }
                var precOffset = INIT_PREC
                var initResult = res.toStringTruncated(precOffset)
                var msd = msdIndexOfGet(initResult)
                if (msd == INVALID_MSD) {
                    val leadingZeroBits = res.leadingBinaryZeroes()
                    if (leadingZeroBits < QUICK_MAX_RESULT_BITS) {
                        // Enough initial nonzero digits for most displays.
                        precOffset = 30 + ceil(ln(2.0) / ln(10.0) * leadingZeroBits).toInt()
                        initResult = res.toStringTruncated(precOffset)
                        msd = msdIndexOfGet(initResult)
                        if (msd == INVALID_MSD) {
                            throw AssertionError("Impossible zero result")
                        }
                    } else {
                        // Just try once more at higher fixed precision.
                        precOffset = MAX_MSD_PREC_OFFSET
                        initResult = res.toStringTruncated(precOffset)
                        msd = msdIndexOfGet(initResult)
                    }
                }
                val lsdOffset = lsdOffsetGet(res, initResult, initResult.indexOf('.'))
                val initDisplayOffset = preferredPrecGet(initResult, msd, lsdOffset,
                        mCharMetricsInfo)
                val newPrecOffset = initDisplayOffset + EXTRA_DIGITS
                if (newPrecOffset > precOffset) {
                    precOffset = newPrecOffset
                    initResult = res.toStringTruncated(precOffset)
                }
                return InitialResult(res, initResult, precOffset, initDisplayOffset)
            } catch (e: CalculatorExpr.SyntaxException) {
                return InitialResult(R.string.error_syntax)
            } catch (e: UnifiedReal.ZeroDivisionException) {
                return InitialResult(R.string.error_zero_divide)
            } catch (e: ArithmeticException) {
                return InitialResult(R.string.error_nan)
            } catch (e: CR.PrecisionOverflowException) {
                // Extremely unlikely unless we're actually dividing by zero or the like.
                return InitialResult(R.string.error_overflow)
            } catch (e: CR.AbortedException) {
                return InitialResult(R.string.error_aborted)
            }

        }

        override fun onPostExecute(result: InitialResult) {
            mExprInfo!!.mEvaluator = null
            mTimeoutHandler.removeCallbacks(mTimeoutRunnable)
            if (result.isError) {
                if (result.errorResourceId == R.string.timeout) {
                    // Emulating timeout due to large result.
                    if (mRequired && mIndex == MAIN_INDEX) {

                        displayTimeoutMessage(mExprs[mIndex]!!.mLongTimeout)
                    }
                    mListener?.onCancelled(mIndex)
                } else {
                    if (mRequired) {
                        mExprInfo.mResultString = ERRONEOUS_RESULT
                    }
                    mListener?.onError(mIndex, result.errorResourceId)
                }
                return
            }
            // mExprInfo.mVal was already set asynchronously by child thread.
            mExprInfo.mResultString = result.newResultString
            mExprInfo.mResultStringOffset = result.newResultStringOffset
            val dotIndex = mExprInfo.mResultString!!.indexOf('.')
            val truncatedWholePart = mExprInfo.mResultString!!.substring(0, dotIndex)
            // Recheck display precision; it may change, since display dimensions may have been
            // un-know the first time.  In that case the initial evaluation precision should have
            // been conservative.
            // TODO: Could optimize by remembering display size and checking for change.
            var initPrecOffset = result.initDisplayOffset
            mExprInfo.mMsdIndex = msdIndexOfGet(mExprInfo.mResultString!!)
            val leastDigOffset = lsdOffsetGet(result.unifiedReal, mExprInfo.mResultString,
                    dotIndex)
            val newInitPrecOffset = preferredPrecGet(mExprInfo.mResultString!!,
                    mExprInfo.mMsdIndex, leastDigOffset, mCharMetricsInfo)
            if (newInitPrecOffset < initPrecOffset) {
                initPrecOffset = newInitPrecOffset
            } else {
                // They should be equal.  But nothing horrible should happen if they're not. e.g.
                // because CalculatorResult.MAX_WIDTH was too small.
                Log.i(TAG, "newInitPrecOffset was not less than initPrecOffset")
            }
            mListener?.onEvaluate(mIndex, initPrecOffset, mExprInfo.mMsdIndex, leastDigOffset,
                    truncatedWholePart)
        }

        override fun onCancelled(result: InitialResult) {
            // Invoker resets mEvaluator.
            mTimeoutHandler.removeCallbacks(mTimeoutRunnable)
            if (!mQuiet) {
                displayCancelledMessage()
            } // Otherwise, if mRequired, timeout processing displayed message.
            mListener?.onCancelled(mIndex)
            // Just drop the evaluation; Leave expression displayed.
        }
    }

    /**
     * Result of asynchronous reevaluation.
     */
    private class ReevalResult internal constructor(val newResultString: String, val newResultStringOffset: Int)

    /**
     * Compute new mResultString contents to prec digits to the right of the decimal point.
     * Ensure that onReevaluate() is called after doing so.  If the evaluation fails for reasons
     * other than a timeout, ensure that onError() is called.
     * This assumes that initial evaluation of the expression has been successfully
     * completed.
     */
    @SuppressLint("StaticFieldLeak")
    private inner class AsyncReevaluator internal constructor(private val mIndex: Long  // Index of expression to evaluate.
                                                              , private val mListener: EvaluationListener) : AsyncTask<Int, Void, ReevalResult>() {
        private val mExprInfo: ExprInfo? = mExprs[mIndex]

        override fun doInBackground(vararg prec: Int?): ReevalResult? {
            return try {
                val precOffset = prec[0]
                ReevalResult(mExprInfo!!.mVal.get().toStringTruncated(precOffset!!),
                        precOffset)
            } catch (e: ArithmeticException) {
                null
            } catch (e: CR.PrecisionOverflowException) {
                null
            } catch (e: CR.AbortedException) {
                // Should only happen if the task was cancelled, in which case we don't look at
                // the result.
                null
            }

        }

        override fun onPostExecute(result: ReevalResult?) {
            if (result == null) {
                // This should only be possible in the extremely rare case of encountering a
                // domain error while reevaluating or in case of a precision overflow.  We don't
                // know of a way to get the latter with a plausible amount of user input.
                mExprInfo!!.mResultString = ERRONEOUS_RESULT
                mListener.onError(mIndex, R.string.error_nan)
            } else {
                if (result.newResultStringOffset < mExprInfo!!.mResultStringOffset) {
                    throw AssertionError("Unexpected onPostExecute timing")
                }
                mExprInfo.mResultString = unflipZeroes(mExprInfo.mResultString!!,
                        mExprInfo.mResultStringOffset, result.newResultString,
                        result.newResultStringOffset)
                mExprInfo.mResultStringOffset = result.newResultStringOffset
                mListener.onReevaluate(mIndex)
            }
            mExprInfo.mEvaluator = null
        }
        // On cancellation we do nothing; invoker should have left no trace of us.
    }

    /**
     * If necessary, start an evaluation of the expression at the given index to precOffset.
     * If we start an evaluation the listener is notified on completion.
     * Only called if prior evaluation succeeded.
     */
    private fun ensureCachePrec(index: Long, precOffset: Int, listener: EvaluationListener) {
        val ei = mExprs[index]

        if (ei!!.mResultString != null && ei.mResultStringOffset >= precOffset || ei.mResultStringOffsetReq >= precOffset)
            return
        if (ei.mEvaluator != null) {
            // Ensure we only have one evaluation running at a time.
            ei.mEvaluator!!.cancel(true)
            ei.mEvaluator = null
        }
        val reEval = AsyncReevaluator(index, listener)
        ei.mEvaluator = reEval
        ei.mResultStringOffsetReq = precOffset + PRECOMPUTE_DIGITS
        if (ei.mResultString != null) {
            ei.mResultStringOffsetReq += ei.mResultStringOffsetReq / PRECOMPUTE_DIVISOR
        }
        reEval.execute(ei.mResultStringOffsetReq)
    }

    /**
     * Return most significant digit index for the result of the expression at the given index.
     * Returns an index in the result character array.  Return INVALID_MSD if the current result
     * is too close to zero to determine the result.
     * Result is almost consistent through reevaluations: It may increase by one, once.
     */
    private fun msdIndexGet(index: Long): Int {
        val ei = mExprs[index]

        if (ei!!.mMsdIndex != INVALID_MSD) {
            // 0.100000... can change to 0.0999999...  We may have to correct once by one digit.
            if (ei.mResultString!![ei.mMsdIndex] == '0') {
                ei.mMsdIndex++
            }
            return ei.mMsdIndex
        }
        if (ei.mVal.get().definitelyZero()) {
            return INVALID_MSD  // None exists
        }
        var result = INVALID_MSD
        if (ei.mResultString != null) {
            ei.mMsdIndex = msdIndexOfGet(ei.mResultString!!)
            result = ei.mMsdIndex
        }
        return result
    }

    /**
     * Return result to precOffset[0] digits to the right of the decimal point.
     * PrecOffset[0] is updated if the original value is out of range.  No exponent or other
     * indication of precision is added.  The result is returned immediately, based on the current
     * cache contents, but it may contain blanks for unknown digits.  It may also use
     * uncertain digits within EXTRA_DIGITS.  If either of those occurred, schedule a reevaluation
     * and redisplay operation.  Uncertain digits never appear to the left of the decimal point.
     * PrecOffset[0] may be negative to only retrieve digits to the left of the decimal point.
     * (precOffset[0] = 0 means we include the decimal point, but nothing to the right.
     * precOffset[0] = -1 means we drop the decimal point and start at the ones position.  Should
     * not be invoked before the onEvaluate() callback is received.  This essentially just returns
     * a substring of the full result; a leading minus sign or leading digits can be dropped.
     * Result uses US conventions; is NOT internationalized.  Use resultGet() and UnifiedReal
     * operations to determine whether the result is exact, or whether we dropped trailing digits.
     *
     * @param index Index of expression to approximate
     * @param precOffset Zeroth element indicates desired and actual precision
     * @param maxPrecOffset Maximum adjusted precOffset[0]
     * @param maxDigs Maximum length of result
     * @param truncated Zeroth element is set if leading nonzero digits were dropped
     * @param negative Zeroth element is set if the result is negative.
     * @param listener EvaluationListener to notify when reevaluation is complete.
     */
    fun stringGet(index: Long, precOffset: IntArray, maxPrecOffset: Int, maxDigs: Int,
                  truncated: BooleanArray, negative: BooleanArray, listener: EvaluationListener): String {
        val ei = mExprs[index]
        var currentPrecOffset = precOffset[0]
        // Make sure we eventually get a complete answer

        if (ei!!.mResultString == null) {
            ensureCachePrec(index, currentPrecOffset + EXTRA_DIGITS, listener)
            // Nothing else to do now; seems to happen on rare occasion with weird user input
            // timing; Will repair itself in a jiffy.
            return " "
        } else {
            ensureCachePrec(index, currentPrecOffset + EXTRA_DIGITS + ei.mResultString!!.length / EXTRA_DIVISOR, listener)
        }
        // Compute an appropriate substring of mResultString.  Pad if necessary.
        val len = ei.mResultString!!.length
        val myNegative = ei.mResultString!![0] == '-'
        negative[0] = myNegative
        // Don't scroll left past leftmost digits in mResultString unless that still leaves an
        // integer.
        var integralDigits = len - ei.mResultStringOffset
        // includes 1 for dec. pt
        if (myNegative) {
            --integralDigits
        }
        val minPrecOffset = min(MIN_DISPLAYED_DIGS - integralDigits, -1)
        currentPrecOffset = min(max(currentPrecOffset, minPrecOffset), maxPrecOffset)
        precOffset[0] = currentPrecOffset
        var extraDigs = ei.mResultStringOffset - currentPrecOffset // trailing digits to drop
        var deficit = 0  // The number of digits we're short
        if (extraDigs < 0) {
            extraDigs = 0
            deficit = min(currentPrecOffset - ei.mResultStringOffset, maxDigs)
        }
        val endIndex = len - extraDigs
        if (endIndex < 1) {
            return " "
        }
        val startIndex = max(endIndex + deficit - maxDigs, 0)
        truncated[0] = startIndex > msdIndexGet(index)
        var result = ei.mResultString!!.substring(startIndex, endIndex)
        if (deficit > 0) {
            result += StringUtils.repeat(' ', deficit)
            // Blank character is replaced during translation.
            // Since we always compute past the decimal point, this never fills in the spot
            // where the decimal point should go, and we can otherwise treat placeholders
            // as though they were digits.
        }
        return result
    }

    /**
     * Clear the cache for the main expression.
     */
    private fun clearMainCache() {
        mMainExpr!!.mVal.set(null)
        mMainExpr!!.mResultString = null
        mMainExpr!!.mResultStringOffsetReq = 0
        mMainExpr!!.mResultStringOffset = mMainExpr!!.mResultStringOffsetReq
        mMainExpr!!.mMsdIndex = INVALID_MSD
    }


    fun clearMain() {
        mMainExpr!!.mExpr.clear()
        mHasTrigFuncs = false
        clearMainCache()
        mMainExpr!!.mLongTimeout = false
    }

    fun clearEverything() {
        val dm = mMainExpr!!.mDegreeMode
        cancelAll(true)
        setSavedIndex(0)
        setMemoryIndex(0)
        mExprDB.eraseAll()
        mExprs.clear()
        setMainExpr(ExprInfo(CalculatorExpr(), dm))
    }

    /**
     * Start asynchronous evaluation.
     * Invoke listener on successful completion. If the result is required, invoke
     * onCancelled() if cancelled.
     * @param index index of expression to be evaluated.
     * @param required result was explicitly requested by user.
     */
    private fun evaluateResult(index: Long, listener: EvaluationListener?, cmi: CharMetricsInfo,
                               required: Boolean) {
        val ei = mExprs[index]
        if (index == MAIN_INDEX) {
            clearMainCache()
        }  // Otherwise the expression is immutable.

        val eval = AsyncEvaluator(index, listener, cmi, ei!!.mDegreeMode, required)
        ei.mEvaluator = eval
        eval.execute()
        if (index == MAIN_INDEX) {
            mChangedValue = false
        }
    }

    /**
     * Notify listener of a previously completed evaluation.
     */
    internal fun notifyImmediately(index: Long, ei: ExprInfo?, listener: EvaluationListener?,
                                   cmi: CharMetricsInfo) {
        val dotIndex = ei!!.mResultString!!.indexOf('.')
        val truncatedWholePart = ei.mResultString!!.substring(0, dotIndex)
        val leastDigOffset = lsdOffsetGet(ei.mVal.get(), ei.mResultString, dotIndex)
        val msdIndex = msdIndexGet(index)
        val preferredPrecOffset = preferredPrecGet(ei.mResultString!!, msdIndex, leastDigOffset, cmi)
        listener?.onEvaluate(index, preferredPrecOffset, msdIndex, leastDigOffset, truncatedWholePart)
    }

    /**
     * Start optional evaluation of expression and display when ready.
     * @param index of expression to be evaluated.
     * Can quietly time out without a listener callback.
     * No-op if cmi.maxCharsGet() == 0.
     */
    fun evaluateAndNotify(index: Long, listener: EvaluationListener?, cmi: CharMetricsInfo) {
        if (cmi.maxCharsGet() == 0) {
            // Probably shouldn't happen. If it does, we didn't promise to do anything anyway.
            return
        }
        val ei = ensureExprIsCached(index)
        if (ei.mResultString != null && ei.mResultString != ERRONEOUS_RESULT
                && !(index == MAIN_INDEX && mChangedValue)) {
            // Already done. Just notify.
            notifyImmediately(MAIN_INDEX, mMainExpr, listener, cmi)
            return
        } else if (ei.mEvaluator != null) {
            // We only allow a single listener per expression, so this request must be redundant.
            return
        }
        evaluateResult(index, listener, cmi, false)
    }

    /**
     * Start required evaluation of expression at given index and call back listener when ready.
     * If index is MAIN_INDEX, we may also directly display a timeout message.
     * Uses longer timeouts than optional evaluation.
     * Requires cmi.maxCharsGet() != 0.
     */
    fun requireResult(index: Long, listener: EvaluationListener?, cmi: CharMetricsInfo) {
        if (cmi.maxCharsGet() == 0) {
            throw AssertionError("requireResult called too early")
        }
        val ei = ensureExprIsCached(index)
        if (ei.mResultString == null || index == MAIN_INDEX && mChangedValue) {
            if (index == HISTORY_MAIN_INDEX) {
                // We don't want to compute a result for HISTORY_MAIN_INDEX that was
                // not already computed for the main expression. Pretend we timed out.
                // The error case doesn't get here.
                listener?.onCancelled(index)
            } else
                if (ei.mEvaluator is AsyncEvaluator && (ei.mEvaluator as AsyncEvaluator).mRequired) {
                    // Duplicate request; ignore.
                } else {
                    // (Re)start evaluator in requested mode, i.e. with longer timeout.
                    cancel(ei, true)
                    evaluateResult(index, listener, cmi, true)
                }
        } else if (ei.mResultString == ERRONEOUS_RESULT) {
            // Just re-evaluate to generate a new notification.
            cancel(ei, true)
            evaluateResult(index, listener, cmi, true)
        } else {
            notifyImmediately(index, ei, listener, cmi)
        }
    }

    /**
     * Whether this expression has explicitly been evaluated (User pressed "=")
     */
    fun hasResult(index: Long): Boolean {
        val ei = ensureExprIsCached(index)
        return ei.mResultString != null
    }

    /**
     * Is a reevaluation still in progress?
     */
    fun evaluationInProgress(index: Long): Boolean {
        val ei = mExprs[index]
        return ei?.mEvaluator != null
    }

    /**
     * Cancel any current background task associated with the given ExprInfo.
     * @param quiet suppress cancellation message
     * @return true if we cancelled an initial evaluation
     */
    private fun cancel(expr: ExprInfo, quiet: Boolean): Boolean {
        if (expr.mEvaluator != null) {
            if (quiet && expr.mEvaluator is AsyncEvaluator) {
                (expr.mEvaluator as AsyncEvaluator).suppressCancelMessage()
            }
            // Reevaluation in progress.
            if (expr.mVal.get() != null) {
                expr.mEvaluator!!.cancel(true)
                expr.mResultStringOffsetReq = expr.mResultStringOffset
                // Background computation touches only constructive reals.
                // OK not to wait.
                expr.mEvaluator = null
            } else {
                expr.mEvaluator!!.cancel(true)
                if (expr === mMainExpr) {
                    // The expression is modifiable, and the AsyncTask is reading it.
                    // There seems to be no good way to wait for cancellation.
                    // Give ourselves a new copy to work on instead.
                    mMainExpr!!.mExpr = mMainExpr!!.mExpr.clone() as CalculatorExpr
                    // Approximation of constructive reals should be thread-safe,
                    // so we can let that continue until it notices the cancellation.
                    mChangedValue = true    // Didn't do the expected evaluation.
                }
                expr.mEvaluator = null
                return true
            }
        }
        return false
    }

    /**
     * Cancel any current background task associated with the given ExprInfo.
     * @param quiet suppress cancellation message
     * @return true if we cancelled an initial evaluation
     */
    fun cancel(index: Long, quiet: Boolean): Boolean {
        val ei = mExprs[index]
        return ei?.let { cancel(it, quiet) } ?: false
    }

    fun cancelAll(quiet: Boolean) {
        // TODO: May want to keep active evaluators in a HashSet to avoid traversing
        // all expressions we've looked at.
        for (expr in mExprs.values) {
            cancel(expr, quiet)
        }
    }

    /**
     * Quietly cancel all evaluations associated with expressions other than the main one.
     * These are currently the evaluations associated with the history fragment.
     */
    fun cancelNonMain() {
        // TODO: May want to keep active evaluators in a HashSet to avoid traversing
        // all expressions we've looked at.
        for (expr in mExprs.values) {
            if (expr !== mMainExpr) {
                cancel(expr, true)
            }
        }
    }

    /**
     * Restore the evaluator state, including the current expression.
     */
    fun restoreInstanceState(dataInput: DataInput) {
        mChangedValue = true
        try {
            mMainExpr!!.mDegreeMode = dataInput.readBoolean()
            mMainExpr!!.mLongTimeout = dataInput.readBoolean()
            mMainExpr!!.mExpr = CalculatorExpr(dataInput)
            mHasTrigFuncs = hasTrigFuncs()
        } catch (e: IOException) {
            Log.v("Calculator", "Exception while restoring:\n$e")
        }

    }

    /**
     * Save the evaluator state, including the expression and any saved value.
     */
    fun saveInstanceState(out: DataOutput) {
        try {
            out.writeBoolean(mMainExpr!!.mDegreeMode)
            out.writeBoolean(mMainExpr!!.mLongTimeout)
            mMainExpr!!.mExpr.write(out)
        } catch (e: IOException) {
            Log.v("Calculator", "Exception while saving state:\n$e")
        }

    }


    /**
     * Append a button press to the main expression.
     * @param id Button identifier for the character or operator to be added.
     * @return false if we rejected the insertion due to obvious syntax issues, and the expression
     * is unchanged; true otherwise
     */
    fun append(id: Int): Boolean {
        return if (id == R.id.fun_10pow) {
            add10pow()  // Handled as macro expansion.
            true
        } else {
            mChangedValue = mChangedValue || !KeyMaps.isBinary(id)
            if (mMainExpr!!.mExpr.add(id)) {
                if (!mHasTrigFuncs) {
                    mHasTrigFuncs = KeyMaps.isTrigFunc(id)
                }
                true
            } else {
                false
            }
        }
    }

    /**
     * Delete last token from main expression.
     */
    fun delete() {
        mChangedValue = true
        mMainExpr!!.mExpr.delete()
        if (mMainExpr!!.mExpr.isEmpty) {
            mMainExpr!!.mLongTimeout = false
        }
        mHasTrigFuncs = hasTrigFuncs()
    }

    /**
     * Set degree mode for main expression.
     */
    fun setDegreeMode(degreeMode: Boolean) {
        mChangedValue = true
        mMainExpr!!.mDegreeMode = degreeMode

        mSharedPrefs.edit()
                .putBoolean(KEY_PREF_DEGREE_MODE, degreeMode)
                .apply()
    }

    /**
     * Return an ExprInfo for a copy of the expression with the given index.
     * We remove trailing binary operators in the copy.
     * mTimeStamp is not copied.
     */
    private fun copy(index: Long, copyValue: Boolean): ExprInfo {
        val fromEi = mExprs[index]

        val ei = ExprInfo(fromEi!!.mExpr.clone() as CalculatorExpr, fromEi.mDegreeMode)
        while (ei.mExpr.hasTrailingBinary()) {
            ei.mExpr.delete()
        }
        if (copyValue) {
            ei.mVal = AtomicReference(fromEi.mVal.get())
            ei.mResultString = fromEi.mResultString
            ei.mResultStringOffsetReq = fromEi.mResultStringOffset
            ei.mResultStringOffset = ei.mResultStringOffsetReq
            ei.mMsdIndex = fromEi.mMsdIndex
        }
        ei.mLongTimeout = fromEi.mLongTimeout
        return ei
    }

    /**
     * Return an ExprInfo corresponding to the sum of the expressions at the
     * two indices.
     * index1 should correspond to an immutable expression, and should thus NOT
     * be MAIN_INDEX. Index2 may be MAIN_INDEX. Both expressions are presumed
     * to have been evaluated.  The result is unevaluated.
     * Can return null if evaluation resulted in an error (a very unlikely case).
     */
    private fun sum(index1: Long, index2: Long): ExprInfo? {
        return generalizedSum(index1, index2, R.id.op_add)
    }

    /**
     * Return an ExprInfo corresponding to the subtraction of the value at the subtrahend index
     * from value at the minuend index (minuend - subtrahend = result). Both are presumed to have
     * been previously evaluated. The result is unevaluated. Can return null.
     */
    private fun difference(minuendIndex: Long, subtrahendIndex: Long): ExprInfo? {
        return generalizedSum(minuendIndex, subtrahendIndex, R.id.op_sub)
    }

    private fun generalizedSum(index1: Long, index2: Long, op: Int): ExprInfo? {
        // TODO: Consider not collapsing expr2, to save database space.
        // Note that this is a bit tricky, since our expressions can contain unbalanced lparens.
        val result = CalculatorExpr()
        val collapsed1 = collapsedExprGet(index1)
        val collapsed2 = collapsedExprGet(index2)
        if (collapsed1 == null || collapsed2 == null) {
            return null
        }
        result.append(collapsed1)
        result.add(op)
        result.append(collapsed2)
        val resultEi = ExprInfo(result, false /* dont care about degrees/radians */)

        resultEi.mLongTimeout = mExprs[index1]!!.mLongTimeout || mExprs[index2]!!.mLongTimeout
        return resultEi
    }

    /**
     * Add the expression described by the argument to the database.
     * Returns the new row id in the database.
     * Fills in timestamp in ei, if it was not previously set.
     * If inHistory is true, add it with a positive index, so it will appear in the history.
     */
    private fun addToDB(inHistory: Boolean, ei: ExprInfo): Long {
        val serializedExpr = ei.mExpr.toBytes()
        val rd = ExpressionDB.RowData(serializedExpr, ei.mDegreeMode,
                ei.mLongTimeout, 0)
        val resultIndex = mExprDB.addRow(!inHistory, rd)
        if (mExprs[resultIndex] != null) {
            throw AssertionError("result slot already occupied! + Slot = $resultIndex")
        }
        // Add newly assigned date to the cache.
        ei.mTimeStamp = rd.mTimeStamp
        if (resultIndex == MAIN_INDEX) {
            throw AssertionError("Should not store main expression")
        }
        mExprs[resultIndex] = ei
        return resultIndex
    }

    /**
     * Preserve a copy of the expression at oldIndex at a new index.
     * This is useful only of oldIndex is MAIN_INDEX or HISTORY_MAIN_INDEX.
     * This assumes that initial evaluation completed successfully.
     * @param inHistory use a positive index so the result appears in the history.
     * @return the new index
     */
    fun preserve(oldIndex: Long, inHistory: Boolean): Long {
        val ei = copy(oldIndex, true)
        if (ei.mResultString == null || ei.mResultString == ERRONEOUS_RESULT) {
            throw AssertionError("Preserving unevaluated expression")
        }
        return addToDB(inHistory, ei)
    }

    /**
     * Preserve a copy of the current main expression as the most recent history entry,
     * assuming it is already in the database, but may have been lost from the cache.
     */
    fun represerve() {
        val resultIndex = maxIndexGet()
        // This requires database access only if the local state was preserved, but we
        // recreated the Evaluator.  That excludes the common cases of device rotation, etc.
        // TODO: Revisit once we deal with database failures. We could just copy from
        // MAIN_INDEX instead, but that loses the timestamp.
        ensureExprIsCached(resultIndex)
    }

    /**
     * Discard previous expression in HISTORY_MAIN_INDEX and replace it by a fresh copy
     * of the main expression. Note that the HISTORY_MAIN_INDEX expression is not preserved
     * in the database or anywhere else; it is always reconstructed when needed.
     */
    fun copyMainToHistory() {
        cancel(HISTORY_MAIN_INDEX, true /* quiet */)
        val ei = copy(MAIN_INDEX, true)
        mExprs[HISTORY_MAIN_INDEX] = ei
    }

    /**
     * @return the [CalculatorExpr] representation of the result of the given
     * expression.
     * The resulting expression contains a single "token" with the pre-evaluated result.
     * The client should ensure that this is never invoked unless initial evaluation of the
     * expression has been completed.
     */
    private fun collapsedExprGet(index: Long): CalculatorExpr? {
        val realIndex = if (isMutableIndex(index)) preserve(index, false) else index
        val ei = mExprs[realIndex]

        val rs = ei!!.mResultString
        // An error can occur here only under extremely unlikely conditions.
        // Check anyway, and just refuse.
        // rs *should* never be null, but it happens. Check as a workaround to protect against
        // crashes until we find the root cause (b/34801142)
        if (rs == null || rs == ERRONEOUS_RESULT) {
            return null
        }
        val dotIndex = rs.indexOf('.')
        val leastDigOffset = lsdOffsetGet(ei.mVal.get(), rs, dotIndex)
        return ei.mExpr.abbreviate(realIndex,
                shortStringGet(rs, msdIndexOfGet(rs), leastDigOffset))
    }

    /**
     * Abbreviate the indicated expression to a pre-evaluated expression node,
     * and use that as the new main expression.
     * This should not be called unless the expression was previously evaluated and produced a
     * non-error result.  Pre-evaluated expressions can never represent an expression for which
     * evaluation to a constructive real diverges.  Subsequent re-evaluation will also not
     * diverge, though it may generate errors of various kinds.  E.g.  sqrt(-10^-1000) .
     */
    fun collapse(index: Long) {

        val longTimeout = mExprs[index]!!.mLongTimeout
        val abbrvExpr = collapsedExprGet(index)
        clearMain()
        assert(abbrvExpr != null)
        mMainExpr!!.mExpr.append(abbrvExpr!!)
        mMainExpr!!.mLongTimeout = longTimeout
        mChangedValue = true
        mHasTrigFuncs = false  // Degree mode no longer affects expression value.
    }

    /**
     * Mark the expression as changed, preventing next evaluation request from being ignored.
     */
    fun touch() {
        mChangedValue = true
    }

    private abstract inner class SetWhenDoneListener : EvaluationListener {
        private fun badCall() {
            throw AssertionError("unexpected callback")
        }

        internal abstract fun setNow()
        override fun onCancelled(index: Long) {}  // Extremely unlikely; leave unset.
        override fun onError(index: Long, errorId: Int) {}  // Extremely unlikely; leave unset.
        override fun onEvaluate(index: Long, initPrecOffset: Int, msdIndex: Int, lsdOffset: Int,
                                truncatedWholePart: String) {
            setNow()
        }

        override fun onReevaluate(index: Long) {
            badCall()
        }
    }

    private inner class SetMemoryWhenDoneListener internal constructor(internal val mIndex: Long, internal val mPersist: Boolean) : SetWhenDoneListener() {
        override fun setNow() {
            if (mMemoryIndex != 0L) {
                throw AssertionError("Overwriting nonzero memory index")
            }
            if (mPersist) {
                setMemoryIndex(mIndex)
            } else {
                mMemoryIndex = mIndex
            }
        }
    }

    private inner class SetSavedWhenDoneListener internal constructor(internal val mIndex: Long) : SetWhenDoneListener() {
        override fun setNow() {
            mSavedIndex = mIndex
        }
    }

    /**
     * Set the local and persistent memory index.
     */
    private fun setMemoryIndex(index: Long) {
        mMemoryIndex = index
        mSharedPrefs.edit()
                .putLong(KEY_PREF_MEMORY_INDEX, index)
                .apply()

        if (mCallback != null) {
            mCallback!!.onMemoryStateChanged()
        }
    }

    /**
     * Set the local and persistent saved index.
     */
    private fun setSavedIndex(index: Long) {
        mSavedIndex = index
        mSharedPrefs.edit()
                .putLong(KEY_PREF_SAVED_INDEX, index)
                .apply()
    }

    /**
     * Set mMemoryIndex (possibly including the persistent version) to index when we finish
     * evaluating the corresponding expression.
     */
    internal fun setMemoryIndexWhenEvaluated(index: Long, persist: Boolean) {
        requireResult(index, SetMemoryWhenDoneListener(index, persist), mDummyCharMetricsInfo)
    }

    /**
     * Set mSavedIndex (not the persistent version) to index when we finish evaluating
     * the corresponding expression.
     */
    internal fun setSavedIndexWhenEvaluated(index: Long) {
        requireResult(index, SetSavedWhenDoneListener(index), mDummyCharMetricsInfo)
    }

    /**
     * Save an immutable version of the expression at the given index as the saved value.
     * mExpr is left alone.  Return false if result is unavailable.
     */
    private fun copyToSaved(index: Long): Boolean {

        if (mExprs[index]!!.mResultString == null || mExprs[index]!!.mResultString == ERRONEOUS_RESULT) {
            return false
        }
        setSavedIndex(if (isMutableIndex(index)) preserve(index, false) else index)
        return true
    }

    /**
     * Save an immutable version of the expression at the given index as the "memory" value.
     * The expression at index is presumed to have been evaluated.
     */
    fun copyToMemory(index: Long) {
        setMemoryIndex(if (isMutableIndex(index)) preserve(index, false) else index)
    }

    /**
     * Save an an expression representing the sum of "memory" and the expression with the
     * given index. Make mMemoryIndex point to it when we complete evaluating.
     */
    fun addToMemory(index: Long) {
        val newEi = sum(mMemoryIndex, index)
        if (newEi != null) {
            val newIndex = addToDB(false, newEi)
            mMemoryIndex = 0  // Invalidate while we're evaluating.
            setMemoryIndexWhenEvaluated(newIndex, true /* persist */)
        }
    }

    /**
     * Save an an expression representing the subtraction of the expression with the given index
     * from "memory." Make mMemoryIndex point to it when we complete evaluating.
     */
    fun subtractFromMemory(index: Long) {
        val newEi = difference(mMemoryIndex, index)
        if (newEi != null) {
            val newIndex = addToDB(false, newEi)
            mMemoryIndex = 0  // Invalidate while we're evaluating.
            setMemoryIndexWhenEvaluated(newIndex, true /* persist */)
        }
    }

    /**
     * Return index of "saved" expression, or 0.
     */
    fun savedIndexGet(): Long {
        return mSavedIndex
    }

    /**
     * Return index of "memory" expression, or 0.
     */
    fun memoryIndexGet(): Long {
        return mMemoryIndex
    }

    private fun uriForSaved(): Uri {
        return Uri.Builder().scheme("tag")
                .encodedOpaquePart(mSavedName)
                .build()
    }

    /**
     * Save the index expression as the saved location and return a URI describing it.
     * The URI is used to distinguish this particular result from others we may generate.
     */
    fun capture(index: Long): Uri? {
        if (!copyToSaved(index)) return null
        // Generate a new (entirely private) URI for this result.
        // Attempt to conform to RFC4151, though it's unclear it matters.
        val tz = TimeZone.getDefault()
        @SuppressLint("SimpleDateFormat")
        val df = SimpleDateFormat("yyyy-MM-dd")
        df.timeZone = tz
        val isoDate = df.format(Date())
        mSavedName = ("calculator2.android.com," + isoDate + ":"
                + (Random().nextInt() and 0x3fffffff))
        mSharedPrefs.edit()
                .putString(KEY_PREF_SAVED_NAME, mSavedName)
                .apply()
        return uriForSaved()
    }

    fun isLastSaved(uri: Uri): Boolean {
        return mSavedIndex != 0L && uri == uriForSaved()
    }

    /**
     * Append the expression at index as a pre-evaluated expression to the main expression.
     */
    fun appendExpr(index: Long) {
        val ei = mExprs[index]
        mChangedValue = true

        mMainExpr!!.mLongTimeout = mMainExpr!!.mLongTimeout or ei!!.mLongTimeout
        val collapsed = collapsedExprGet(index)
        if (collapsed != null) {

            mMainExpr!!.mExpr.append(collapsedExprGet(index)!!)
        }
    }

    /**
     * Add the power of 10 operator to the main expression.
     * This is treated essentially as a macro expansion.
     */
    private fun add10pow() {
        val ten = CalculatorExpr()
        ten.add(R.id.digit_1)
        ten.add(R.id.digit_0)
        mChangedValue = true  // For consistency.  Reevaluation is probably not useful.
        mMainExpr!!.mExpr.append(ten)
        mMainExpr!!.mExpr.add(R.id.op_pow)
    }

    /**
     * Ensure that the expression with the given index is in mExprs.
     * We assume that if it's either already in mExprs or mExprDB.
     * When we're done, the expression in mExprs may still contain references to other
     * subexpressions that are not yet cached.
     */
    private fun ensureExprIsCached(index: Long): ExprInfo {
        var ei = mExprs[index]
        if (ei != null) {
            return ei
        }
        if (index == MAIN_INDEX) {
            throw AssertionError("Main expression should be cached")
        }
        val row = mExprDB.getRow(index)
        val serializedExpr = DataInputStream(ByteArrayInputStream(row.mExpression))
        try {
            ei = ExprInfo(CalculatorExpr(serializedExpr), row.degreeMode())
            ei.mTimeStamp = row.mTimeStamp
            ei.mLongTimeout = row.longTimeout()
        } catch (e: IOException) {
            throw AssertionError("IO Exception without real IO:$e")
        }

        val newEi = (mExprs as  MutableMap<Long, ExprInfo>).putIfAbsent(index, ei)
        return newEi ?: ei
    }

    override fun exprGet(index: Long): CalculatorExpr {
        return ensureExprIsCached(index).mExpr
    }

    /*
     * Return timestamp associated with the expression in milliseconds since epoch.
     * Yields zero if the expression has not been written to or read from the database.
     */
    fun timeStampGet(index: Long): Long {
        return ensureExprIsCached(index).mTimeStamp
    }

    override fun degreeModeGet(index: Long): Boolean {
        return ensureExprIsCached(index).mDegreeMode
    }

    override fun resultGet(index: Long): UnifiedReal? {
        return ensureExprIsCached(index).mVal.get()
    }

    override fun putResultIfAbsent(index: Long, result: UnifiedReal): UnifiedReal {
        val ei = mExprs[index]

        return if (ei!!.mVal.compareAndSet(null, result)) {
            result
        } else {
            // Cannot change once non-null.
            ei.mVal.get()
        }
    }

    /**
     * Does the current main expression contain trig functions?
     * Might its value depend on DEG/RAD mode?
     */
    fun hasTrigFuncs(): Boolean {
        return mHasTrigFuncs
    }

    /**
     * Add the exponent represented by s[begin..end) to the constant at the end of current
     * expression.
     * The end of the current expression must be a constant.  Exponents have the same syntax as
     * for exponentEnd().
     */
    fun addExponent(s: String, begin: Int, end: Int) {
        var sign = 1
        var exp = 0
        var i = begin + 1
        // We do the decimal conversion ourselves to exactly match exponentEnd() conventions
        // and handle various kinds of digits on input.  Also avoids allocation.
        if (KeyMaps.keyForChar(s[i]) == R.id.op_sub) {
            sign = -1
            ++i
        }
        while (i < end) {
            exp = 10 * exp + Character.digit(s[i], 10)
            ++i
        }
        mMainExpr!!.mExpr.addExponent(sign * exp)
        mChangedValue = true
    }

    /**
     * Generate a String representation of the expression at the given index.
     * This has the side effect of adding the expression to mExprs.
     * The expression must exist in the database.
     */
    fun exprAsStringGet(index: Long): String {
        return exprAsSpannableGet(index).toString()
    }

    fun exprAsSpannableGet(index: Long): Spannable {
        return exprGet(index).toSpannableStringBuilder(mContext)
    }

    /**
     * Generate a String representation of all expressions in the database.
     * Debugging only.
     */
    @Suppress("unused", "UNUSED_VARIABLE")
    fun historyAsString(): String {
        val startIndex = minIndexGet()
        val endIndex = maxIndexGet()
        val sb = StringBuilder()
        for (i in minIndexGet() until ExpressionDB.MAXIMUM_MIN_INDEX) {
            sb.append(i).append(": ").append(exprAsStringGet(i)).append("\n")
        }
        for (i in 1 until maxIndexGet()) {
            sb.append(i).append(": ").append(exprAsStringGet(i)).append("\n")
        }
        sb.append("Memory index = ").append(memoryIndexGet())
        sb.append(" Saved index = ").append(savedIndexGet()).append("\n")
        return sb.toString()
    }

    /**
     * Wait for pending writes to the database to complete.
     */
    fun waitForWrites() {
        mExprDB.waitForWrites()
    }

    /**
     * Destroy the current evaluator, forcing getEvaluator to allocate a new one.
     * This is needed for testing, since Roboelectric apparently doesn't let us preserve
     * an open database across tests. Cf. https://github.com/robolectric/robolectric/issues/1890 .
     */
    fun destroyEvaluator() {
        mExprDB.close()
        evaluator = null
    }

    interface Callback {
        fun onMemoryStateChanged()
        fun showMessageDialog(@StringRes title: Int, @StringRes message: Int,
                              @StringRes positiveButtonLabel: Int, tag: String?)
    }

    companion object {
        internal const val TAG = "Evaluator"

        @SuppressLint("StaticFieldLeak")
        private var evaluator: Evaluator? = null

        var TIMEOUT_DIALOG_TAG = "timeout"

        fun instanceGet(context: Context): Evaluator {
            if (evaluator == null) {
                evaluator = Evaluator(context.applicationContext)
            }
            return evaluator as Evaluator
        }

        const val MAIN_INDEX: Long = 0  // Index of main expression.
        // Once final evaluation of an expression is complete, or when we need to save
        // a partial result, we copy the main expression to a non-zero index.
        // At that point, the expression no longer changes, and is preserved
        // until the entire history is cleared. Only expressions at nonzero indices
        // may be embedded in other expressions.
        // Each expression index can only have one outstanding evaluation request at a time.
        // To avoid conflicts between the history and main View, we copy the main expression
        // to allow independent evaluation by both.
        const val HISTORY_MAIN_INDEX: Long = -1  // Read-only copy of main expression.

        // When naming variables and fields, "Offset" denotes a character offset in a string
        // representing a decimal number, where the offset is relative to the decimal point.  1 =
        // tenths position, -1 = units position.  Integer.MAX_VALUE is sometimes used for the offset
        // of the last digit in an a non-terminating decimal expansion.  We use the suffix "Index" to
        // denote a zero-based absolute index into such a string. (In other contexts, like above,
        // we also use "index" to refer to the key in mExprs below, the list of all known
        // expressions.)

        private const val KEY_PREF_DEGREE_MODE = "degree_mode"
        private const val KEY_PREF_SAVED_INDEX = "saved_index"
        private const val KEY_PREF_MEMORY_INDEX = "memory_index"
        private const val KEY_PREF_SAVED_NAME = "saved_name"

        // The minimum number of extra digits we always try to compute to improve the chance of
        // producing a correctly-rounded-towards-zero result.  The extra digits can be displayed to
        // avoid generating placeholder digits, but should only be displayed briefly while computing.
        private const val EXTRA_DIGITS = 20

        // We adjust EXTRA_DIGITS by adding the length of the previous result divided by
        // EXTRA_DIVISOR.  This helps hide recompute latency when long results are requested;
        // We start the re-computation substantially before the need is likely to be visible.
        private const val EXTRA_DIVISOR = 5

        // In addition to insisting on extra digits (see above), we minimize reevaluation
        // frequency by pre-computing an extra PRECOMPUTE_DIGITS
        // + <current_precision_offset>/PRECOMPUTE_DIVISOR digits, whenever we are forced to
        // reevaluate.  The last term is dropped if prec < 0.
        private const val PRECOMPUTE_DIGITS = 30
        private const val PRECOMPUTE_DIVISOR = 5

        // Initial evaluation precision.  Enough to guarantee that we can compute the short
        // representation, and that we rarely have to evaluate nonzero results to MAX_MSD_PREC_OFFSET.
        // It also helps if this is at least EXTRA_DIGITS + display width, so that we don't
        // immediately need a second evaluation.
        private const val INIT_PREC = 50

        // The largest number of digits to the right of the decimal point to which we will evaluate to
        // compute proper scientific notation for values close to zero.  Chosen to ensure that we
        // always to better than IEEE double precision at identifying non-zeros. And then some.
        // This is used only when we cannot a priori determine the most significant digit position, as
        // we always can if we have a rational representation.
        private const val MAX_MSD_PREC_OFFSET = 1100

        // If we can replace an exponent by this many leading zeroes, we do so.  Also used in
        // estimating exponent size for truncating short representation.
        private const val EXP_COST = 3

        const val INVALID_MSD = Integer.MAX_VALUE

        // Used to represent an erroneous result or a required evaluation. Not displayed.
        private const val ERRONEOUS_RESULT = "ERR"

        /**
         * Timeout for unrequested, speculative evaluations, in milliseconds.
         */
        private const val QUICK_TIMEOUT: Long = 1000

        /**
         * Timeout for non-MAIN expressions. Note that there may be many such evaluations in
         * progress on the same thread or core. Thus the evaluation latency may include that needed
         * to complete previously enqueued evaluations. Thus the longTimeout flag is not very
         * meaningful, and currently ignored.
         * Since this is only used for expressions that we have previously successfully evaluated,
         * these timeouts should never trigger.
         */
        private const val NON_MAIN_TIMEOUT: Long = 100000

        /**
         * Maximum result bit length for unrequested, speculative evaluations.
         * Also used to bound evaluation precision for small non-zero fractions.
         */
        private const val QUICK_MAX_RESULT_BITS = 150000

        /**
         * Check whether a new higher precision result flips previously computed trailing 9s
         * to zeroes.  If so, flip them back.  Return the adjusted result.
         * Assumes newPrecOffset >= oldPrecOffset > 0.
         * Since our results are accurate to < 1 ulp, this can only happen if the true result
         * is less than the new result with trailing zeroes, and thus appending 9s to the
         * old result must also be correct.  Such flips are impossible if the newly computed
         * digits consist of anything other than zeroes.
         * It is unclear that there are real cases in which this is necessary,
         * but we have failed to prove there aren't such cases.
         */
        @VisibleForTesting
        fun unflipZeroes(oldDigs: String, oldPrecOffset: Int, newDigs: String,
                         newPrecOffset: Int): String {
            val oldLen = oldDigs.length
            if (oldDigs[oldLen - 1] != '9') {
                return newDigs
            }
            val newLen = newDigs.length
            val precDiff = newPrecOffset - oldPrecOffset
            val oldLastInNew = newLen - 1 - precDiff
            if (newDigs[oldLastInNew] != '0') {
                return newDigs
            }
            // Earlier digits could not have changed without a 0 to 9 or 9 to 0 flip at end.
            // The former is OK.
            if (newDigs.substring(newLen - precDiff) != StringUtils.repeat('0', precDiff)) {
                throw AssertionError("New approximation invalidates old one!")
            }
            return oldDigs + StringUtils.repeat('9', precDiff)
        }

        /**
         * Return the rightmost nonzero digit position, if any.
         * @param value UnifiedReal value of result.
         * @param cache Current cached decimal string representation of result.
         * @param decIndex Index of decimal point in cache.
         * @return Position of rightmost nonzero digit relative to decimal point.
         * Integer.MIN_VALUE if we cannot determine.  Integer.MAX_VALUE if there is no lsd,
         * or we cannot determine it.
         */
        internal fun lsdOffsetGet(value: UnifiedReal, cache: String?, decIndex: Int): Int {
            if (value.definitelyZero()) return Integer.MIN_VALUE
            var result = value.digitsRequired()
            if (result == 0) {
                var i: Int = -1
                while (decIndex + i > 0 && cache!![decIndex + i] == '0') {
                    --i
                }
                result = i
            }
            return result
        }

        // TODO: We may want to consistently specify the position of the current result
        // window using the left-most visible digit index instead of the offset for the rightmost one.
        // It seems likely that would simplify the logic.

        /**
         * Retrieve the preferred precision "offset" for the currently displayed result.
         * May be called from non-UI thread.
         * @param cache Current approximation as string.
         * @param msd Position of most significant digit in result.  Index in cache.
         * Can be INVALID_MSD if we haven't found it yet.
         * @param lastDigitOffset Position of least significant digit (1 = tenths digit)
         * or Integer.MAX_VALUE.
         */
        private fun preferredPrecGet(cache: String, msd: Int, lastDigitOffset: Int,
                                     cm: CharMetricsInfo): Int {
            var msdLocal = msd
            var lastDigitOffsetLocal = lastDigitOffset
            val lineLength = cm.maxCharsGet()
            val wholeSize = cache.indexOf('.')
            val rawSepChars = cm.separatorChars(cache, wholeSize)
            val rawSepCharsNoDecimal = rawSepChars - cm.noEllipsisCreditGet()
            val rawSepCharsWithDecimal = rawSepCharsNoDecimal - cm.decimalCreditGet()
            val sepCharsNoDecimal = ceil(max(rawSepCharsNoDecimal, 0.0f).toDouble()).toInt()
            val sepCharsWithDecimal = ceil(max(rawSepCharsWithDecimal, 0.0f).toDouble()).toInt()
            val negative = if (cache[0] == '-') 1 else 0
            // Don't display decimal point if result is an integer.
            if (lastDigitOffsetLocal == 0) {
                lastDigitOffsetLocal = -1
            }
            if (lastDigitOffsetLocal != Integer.MAX_VALUE) {
                if (wholeSize <= lineLength - sepCharsNoDecimal && lastDigitOffsetLocal <= 0) {
                    // Exact integer.  Prefer to display as integer, without decimal point.
                    return -1
                }
                if (lastDigitOffsetLocal >= 0 && wholeSize + lastDigitOffsetLocal + 1 /* decimal pt. */ <= lineLength - sepCharsWithDecimal) {
                    // Display full exact number without scientific notation.
                    return lastDigitOffsetLocal
                }
            }
            if (msdLocal > wholeSize && msdLocal <= wholeSize + EXP_COST + 1) {
                // Display number without scientific notation.  Treat leading zero as msd.
                msdLocal = wholeSize - 1
            }
            if (msdLocal > QUICK_MAX_RESULT_BITS) {
                // Display a probable but uncertain 0 as "0.000000000", without exponent.  That's a
                // judgment call, but less likely to confuse naive users.  A more informative and
                // confusing option would be to use a large negative exponent.
                // Treat extremely large msd values as unknown to avoid slow computations.
                return lineLength - 2
            }
            // Return position corresponding to having msd at left, effectively presuming scientific
            // notation that preserves the left part of the result.
            // After adjustment for the space required by an exponent, evaluating to the resulting
            // precision should not overflow the display.
            var result = msdLocal - wholeSize + lineLength - negative - 1
            if (wholeSize <= lineLength - sepCharsNoDecimal) {
                // Fits without scientific notation; will need space for separators.
                result -= if (wholeSize < lineLength - sepCharsWithDecimal) {
                    sepCharsWithDecimal
                } else {
                    sepCharsNoDecimal
                }
            }
            return result
        }

        private const val SHORT_TARGET_LENGTH = 8
        private const val SHORT_UNCERTAIN_ZERO = "0.00000" + KeyMaps.ELLIPSIS

        /**
         * Get a short representation of the value represented by the string cache.
         * We try to match the CalculatorResult code when the result is finite
         * and small enough to suit our needs.
         * The result is not internationalized.
         * @param cache String approximation of value.  Assumed to be long enough
         * that if it doesn't contain enough significant digits, we can
         * reasonably abbreviate as SHORT_UNCERTAIN_ZERO.
         * @param msdIndex Index of most significant digit in cache, or INVALID_MSD.
         * @param lsdOffset Position of least significant digit in finite representation,
         * relative to decimal point, or MAX_VALUE.
         */
        private fun shortStringGet(cache: String, msdIndex: Int, lsdOffset: Int): String {
            var msdIndexLocal = msdIndex
            var lsdOffsetLocal = lsdOffset
            // This somewhat mirrors the display formatting code, but
            // - The constants are different, since we don't want to use the whole display.
            // - This is an easier problem, since we don't support scrolling and the length
            //   is a bit flexible.
            // TODO: Think about refactoring this to remove partial redundancy with CalculatorResult.
            val dotIndex = cache.indexOf('.')
            val negative = if (cache[0] == '-') 1 else 0
            val negativeSign = if (negative == 1) "-" else ""

            // Ensure we don't have to worry about running off the end of cache.
            if (msdIndexLocal >= cache.length - SHORT_TARGET_LENGTH) {
                msdIndexLocal = INVALID_MSD
            }
            if (msdIndexLocal == INVALID_MSD) {
                return if (lsdOffsetLocal < INIT_PREC) {
                    "0"
                } else {
                    SHORT_UNCERTAIN_ZERO
                }
            }
            // Avoid scientific notation for small numbers of zeros.
            // Instead stretch significant digits to include decimal point.
            if (lsdOffsetLocal < -1 && dotIndex - msdIndexLocal + negative <= SHORT_TARGET_LENGTH
                    && lsdOffsetLocal >= -CalculatorResult.MAX_TRAILING_ZEROES - 1) {
                // Whole number that fits in allotted space.
                // CalculatorResult would not use scientific notation either.
                lsdOffsetLocal = -1
            }
            if (msdIndexLocal > dotIndex) {
                if (msdIndexLocal <= dotIndex + EXP_COST + 1) {
                    // Preferred display format in this case is with leading zeroes, even if
                    // it doesn't fit entirely.  Replicate that here.
                    msdIndexLocal = dotIndex - 1
                } else
                    if (lsdOffsetLocal <= SHORT_TARGET_LENGTH - negative - 2 && lsdOffsetLocal <= CalculatorResult.MAX_LEADING_ZEROES + 1) {
                        // Fraction that fits entirely in allotted space.
                        // CalculatorResult would not use scientific notation either.
                        msdIndexLocal = dotIndex - 1
                    }
            }
            var exponent = dotIndex - msdIndexLocal
            if (exponent > 0) {
                // Adjust for the fact that the decimal point itself takes space.
                exponent--
            }
            if (lsdOffsetLocal != Integer.MAX_VALUE) {
                val lsdIndex = dotIndex + lsdOffsetLocal
                val totalDigits = lsdIndex - msdIndexLocal + negative + 1
                if (totalDigits <= SHORT_TARGET_LENGTH && dotIndex > msdIndexLocal && lsdOffsetLocal >= -1) {
                    // Fits, no exponent needed.
                    val wholeWithCommas = StringUtils.addCommas(cache, msdIndexLocal, dotIndex)
                    return negativeSign + wholeWithCommas + cache.substring(dotIndex, lsdIndex + 1)
                }
                if (totalDigits <= SHORT_TARGET_LENGTH - 3) {
                    return (negativeSign + cache[msdIndexLocal] + "."
                            + cache.substring(msdIndexLocal + 1, lsdIndex + 1) + "E" + exponent)
                }
            }
            // We need to abbreviate.
            if (dotIndex > msdIndexLocal && dotIndex < msdIndexLocal + SHORT_TARGET_LENGTH - negative - 1) {
                val wholeWithCommas = StringUtils.addCommas(cache, msdIndexLocal, dotIndex)
                return (negativeSign + wholeWithCommas
                        + cache.substring(dotIndex, msdIndexLocal + SHORT_TARGET_LENGTH - negative - 1)
                        + KeyMaps.ELLIPSIS)
            }
            // Need abbreviation + exponent
            return (negativeSign + cache[msdIndexLocal] + "."
                    + cache.substring(msdIndexLocal + 1, msdIndexLocal + SHORT_TARGET_LENGTH - negative - 4)
                    + KeyMaps.ELLIPSIS + "E" + exponent)
        }

        /**
         * Return the most significant digit index in the given numeric string.
         * Return INVALID_MSD if there are not enough digits to prove the numeric value is
         * different from zero.  As usual, we assume an error of strictly less than 1 ulp.
         */
        fun msdIndexOfGet(s: String): Int {
            val len = s.length
            var nonzeroIndex = -1
            for (i in 0 until len) {
                val c = s[i]
                if (c != '-' && c != '.' && c != '0') {
                    nonzeroIndex = i
                    break
                }
            }
            return if (nonzeroIndex >= 0 && (nonzeroIndex < len - 1 || s[nonzeroIndex] != '1')) {
                nonzeroIndex
            } else {
                INVALID_MSD
            }
        }

        // Refuse to scroll past the point at which this many digits from the whole number
        // part of the result are still displayed.  Avoids silly displays like 1E1.
        private const val MIN_DISPLAYED_DIGS = 5

        /**
         * Maximum number of characters in a scientific notation exponent.
         */
        private const val MAX_EXP_CHARS = 8

        /**
         * Return the index of the character after the exponent starting at s[offset].
         * Return offset if there is no exponent at that position.
         * Exponents have syntax E[-]digit* .  "E2" and "E-2" are valid.  "E+2" and "e2" are not.
         * We allow any Unicode digits, and either of the commonly used minus characters.
         */
        fun exponentEnd(s: String, offset: Int): Int {
            var i = offset
            val len = s.length
            if (i >= len - 1 || s[i] != 'E') {
                return offset
            }
            ++i
            if (KeyMaps.keyForChar(s[i]) == R.id.op_sub) {
                ++i
            }
            if (i == len || !Character.isDigit(s[i])) {
                return offset
            }
            ++i
            while (i < len && Character.isDigit(s[i])) {
                ++i
                if (i > offset + MAX_EXP_CHARS) {
                    return offset
                }
            }
            return i
        }
    }
}
