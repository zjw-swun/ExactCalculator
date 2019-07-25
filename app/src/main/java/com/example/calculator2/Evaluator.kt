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
class Evaluator internal constructor(
        private val mContext: Context // Context for database helper.
) : CalculatorExpr.ExprResolver {

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
         * @param lsdOffset offset of last digit in result if result has finite decimal expansion
         * @param truncatedWholePart the integer part of the result
         */
        fun onEvaluate(
                index: Long,
                initPrecOffset: Int,
                msdIndex: Int,
                lsdOffset: Int,
                truncatedWholePart: String
        )

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
        fun separatorChars(
                s: String,
                len: Int
        ): Float

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
        override fun separatorChars(
                s: String,
                len: Int
        ): Float {
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
    inner class ExprInfo(
            var mExpr: CalculatorExpr, // The expression itself.
            var mDegreeMode: Boolean   // Evaluating in degree, not radian, mode.
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
        /**
         * Number of significant digits in [mResultString].
         */
        var mResultStringOffset = 0
        /**
         * Number of digits to which (possibly incomplete) evaluation has been requested.
         * Only accessed by UI thread.
         */
        var mResultStringOffsetReq = 0
        /**
         * Position of most significant digit in current cached result, if determined.  This is just
         * the index in [mResultString] holding the msd.
         */
        var mMsdIndex = INVALID_MSD
        /**
         * Long timeout needed for evaluation?
         */
        var mLongTimeout = false
        /**
         * Time in milliseconds when the expression we are the [Evaluator] for is stored to the
         * database when the `addRow` method of [ExpressionDB] calls the `toContentValues` method
         * to convert us to a `ContentValues` instance.
         */
        var mTimeStamp: Long = 0

    }

    /**
     * Our *init* block. First we call our `setMainExpr` method to initialize our field `mMainExpr`
     * to a new instance of `ExprInfo` constructed using a new instance  of `CalculatorExpr` and
     * *false* for its degree mode (ie. radian mode). We initialize our field `mSavedName` to the
     * string "none", initialize our field `mTimeoutHandler` to a new instance of `Handler` and
     * initialize our field `mExprDB` to a new instance of `ExpressionDB`.
     *
     * We then initialize our field `mSharedPrefs` to the default `SharedPreferences` file for our
     * application. We use `mSharedPrefs` to retrieve the `Boolean` stored under the key
     * KEY_PREF_DEGREE_MODE in the database and use it to initialize the `mDegreeMode` field of
     * `mMainExpr`, defaulting to *false*. We initialize our variable `savedIndex` to the `Long`
     * value store under the key KEY_PREF_SAVED_INDEX defaulting to 0L, and `memoryIndex` to the
     * `Long` stored under the key KEY_PREF_MEMORY_INDEX defaulting to 0L. If `savedIndex` is not
     * equal to 0L and not equal to -1L we call our `setSavedIndexWhenEvaluated` method to set
     * `mSavedIndex` (not the persistent version) to `savedIndex` when we finish evaluating our
     * expression. If `memoryIndex` is not equal to 0L and not equal to -1L we call our method
     * `setMemoryIndexWhenEvaluated` to have us set `mMemoryIndex` to `memoryIndex` (without
     * updating the persistent version) when we finish evaluating our expression. Finally we
     * initialize our field `mSavedName` by retrieving the string stored under the key
     * KEY_PREF_SAVED_NAME defaulting to "none".
     */
    init {
        setMainExpr(ExprInfo(CalculatorExpr(),false))
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
     * Sets our field [mMainExpr] to the [ExprInfo] in our parameter [expr] and stores [expr] in our
     * [mExprs] cache under the key MAIN_INDEX.
     *
     * @param expr the [ExprInfo] we are to be the [Evaluator] for.
     */
    private fun setMainExpr(expr: ExprInfo) {
        mMainExpr = expr
        mExprs[MAIN_INDEX] = expr
    }

    /**
     * Retrieve minimum expression index. This is the minimum over all expressions, including
     * uncached ones residing only in the data base. If no expressions with negative indices were
     * preserved, this will return a small negative predefined constant. May be called from any
     * thread, but will block until the database is opened. We just return the value that our field
     * [mExprDB] returns for its field `mMinIndex`.
     *
     * @return the minimum index value in our [ExpressionDB].
     */
    fun minIndexGet(): Long {
        return mExprDB.minIndexGet()
    }

    /**
     * Retrieve maximum expression index. This is the maximum over all expressions, including
     * uncached ones residing only in the data base. If no expressions with positive indices were
     * preserved, this will return 0. May be called from any thread, but will block until the
     * database is opened. We just return the value that our field [mExprDB] returns for its field
     * `mMaxIndex`.
     *
     * @return the maximum index value in our [ExpressionDB].
     */
    fun maxIndexGet(): Long {
        return mExprDB.maxIndexGet()
    }

    /**
     * Set the Callback for showing dialogs and notifying the UI about memory state changes. We just
     * store our parameter [callback] in our [Callback] field [mCallback].
     *
     * @param callback the Callback we should call
     */
    fun setCallback(callback: Callback) {
        mCallback = callback
    }

    /**
     * Does the expression at index [index] refer to a transient and mutable expression? Only the
     * current expression in the calculator display is mutable, and there are two indices which
     * point to it: MAIN_INDEX is the expression in the calculator display and HISTORY_MAIN_INDEX
     * is the copy of that expression displayed in the `RecyclerView` of the [HistoryFragment].
     *
     * @param index the index of the expression to test.
     * @return *true* if [index] is MAIN_INDEX or HISTORY_MAIN_INDEX
     */
    private fun isMutableIndex(index: Long): Boolean {
        return index == MAIN_INDEX || index == HISTORY_MAIN_INDEX
    }

    /**
     * Result of initial asynchronous result computation. Represents either an error or a result
     * computed to an initial evaluation precision.
     */
    class InitialResult {
        /**
         * Resource ID of an error string or INVALID_RES_ID.
         */
        val errorResourceId: Int
        /**
         * Constructive real value of this initial result.
         */
        val unifiedReal: UnifiedReal
        /**
         * Truncated [String] representation of the result. Null iff it can't be computed.
         */
        val newResultString: String
        /**
         * Precision "offset" of [newResultString]
         */
        val newResultStringOffset: Int
        /**
         * Preferred precision "offset" for the currently displayed result.
         */
        val initDisplayOffset: Int
        /**
         * Flag indicating that we contain an error instead of a numerical result.
         */
        internal val isError: Boolean
            get() = errorResourceId != Calculator2.INVALID_RES_ID

        /**
         * Our constructor which is used to return a good preliminary result from the background
         * [AsyncEvaluator] task. We just store our parameters in their appropriate fields, and set
         * our field [errorResourceId] to the special value INVALID_RES_ID so that our [isError]
         * method know our result is a valid one.
         *
         * @param v [UnifiedReal] value we will contain.
         * @param s [String] representation of our value.
         * @param p Display precision "offset" of our [String] representation.
         * @param idp Preferred precision "offset" for displaying us as a result.
         */
        internal constructor(
                v: UnifiedReal,
                s: String,
                p: Int,
                idp: Int
        ) {
            errorResourceId = Calculator2.INVALID_RES_ID
            unifiedReal = v
            newResultString = s
            newResultStringOffset = p
            initDisplayOffset = idp
        }

        /**
         * The constructor for use when an error occurs during evaluation by [AsyncEvaluator]. We
         * store our parameter in our field [errorResourceId], and set our other fields to error
         * values.
         *
         * @param errorId the resource ID of an appropriate error message.
         */
        internal constructor(errorId: Int) {
            errorResourceId = errorId
            unifiedReal = UnifiedReal.ZERO
            newResultString = "BAD"
            newResultStringOffset = 0
            initDisplayOffset = 0
        }
    }

    /**
     * Calls the `showMessageDialog` method of our callback [mCallback] if it is not null in order
     * to pop up a dialog to inform the user that evaluation has been cancelled.
     */
    private fun displayCancelledMessage() {
        if (mCallback != null) {
            mCallback!!.showMessageDialog(0, R.string.cancelled, 0, null)
        }
    }

    // Timeout handling.
    // Expressions are evaluated with a short timeout or a long timeout.
    // Each implies different maxima on both computation time and bit length.
    // We recheck bit length separately to avoid wasting time on decimal conversions that are
    // destined to fail.

    /**
     * Return the timeout in milliseconds. This is the timeout used to schedule [mTimeoutHandler]
     * running of `mTimeoutRunnable` to timeout execution by cancelling the [AsyncEvaluator].
     *
     * @param longTimeout a long timeout is in effect
     * @return If [longTimeout] is *true* we return 15,000 milliseconds, if *false* we return 2,000.
     */
    private fun timeoutGet(longTimeout: Boolean): Long {
        return (if (longTimeout) 15_000 else 2_000).toLong()
        // Exceeding a few tens of seconds increases the risk of running out of memory
        // and impacting the rest of the system.
    }

    /**
     * Return the maximum number of bits in the result. Longer results are assumed to time out.
     *
     * @param longTimeout a long timeout is in effect
     * @return If [longTimeout] is *true* we return 700,000 bits, if *false* we return 240,000.
     */
    private fun maxResultBitsGet(longTimeout: Boolean): Int {
        return if (longTimeout) 700_000 else 240_000
    }

    /**
     * Calls the `showMessageDialog` method of our callback [mCallback] if it is not null in order
     * to pop up a dialog to inform the user that evaluation has timed out, and if [longTimeout] is
     * *false* the dialog will also display the button "Use longer timeouts" which will allow the
     * user to increase the maximum amount of time we attempt to evaluate the expression before
     * "timing out".
     *
     * @param longTimeout a long timeout is in effect
     */
    private fun displayTimeoutMessage(longTimeout: Boolean) {
        if (mCallback != null) {
            mCallback!!.showMessageDialog(R.string.dialog_timeout, R.string.timeout,
                    if (longTimeout) 0 else R.string.ok_remove_timeout, TIMEOUT_DIALOG_TAG)
        }
    }

    /**
     * Setter for the `mLongTimeout` field of our [mMainExpr], it is called when the user decides to
     * increase the maximum amount of time we attempt to evaluate the expression before "timing out".
     */
    fun setLongTimeout() {
        mMainExpr!!.mLongTimeout = true
    }

    /**
     * Compute initial cache contents and result when we're good and ready.
     * We leave the expression display up, with scrolling disabled, until this computation
     * completes. Can result in an error display if something goes wrong. By default we set a
     * timeout to catch runaway computations.
     *
     * @param mIndex Expression index.
     * @param mListener Completion callback.
     * @param mCharMetricsInfo Where to get result size information.
     * @param mDm degree mode, if *true* trig functions should use degree mode.
     * @param mRequired If *true* result was requested by user.
     */
    @SuppressLint("StaticFieldLeak")
    internal inner class AsyncEvaluator(
            private val mIndex: Long, // Expression index.
            private val mListener: EvaluationListener?, // Completion callback.
            private val mCharMetricsInfo: CharMetricsInfo, // Where to get result size information.
            private val mDm: Boolean, // degrees
            var mRequired: Boolean // Result was requested by user.
    ) : AsyncTask<Void, Void, InitialResult>() {
        /**
         * Suppress cancellation message if *true*.
         */
        private var mQuiet: Boolean = false
        /**
         * The [Runnable] that is scheduled to run when a timeout is reached.
         */
        private var mTimeoutRunnable: Runnable? = null
        /**
         * Current expression.
         */
        private val mExprInfo: ExprInfo?

        /**
         * Our *init* block. We set our `mQuiet` field to *true* if our `mRequired` field is *false*
         * (the user has not requested a result) or if our `mIndex` field is not the MAIN_INDEX
         * index value (the current calculator display is the MAIN_INDEX). We set our current
         * expression field `mExprInfo` to the `ExprInfo` stored under key `mIndex` in `mExprs`.
         * If the `mEvaluator` field of `mExprInfo` is not *null* we throw an AssertionError:
         * "Evaluation already in progress!".
         */
        init {
            mQuiet = !mRequired || mIndex != MAIN_INDEX
            mExprInfo = mExprs[mIndex]

            if (mExprInfo!!.mEvaluator != null) {
                throw AssertionError("Evaluation already in progress!")
            }
        }

        /**
         * This is called by the [mTimeoutRunnable] which is scheduled to run in our [onPreExecute]
         * override, and handles timeouts. First we initialize our variable `running` to *true* if
         * our `status` property is not FINISHED (if our status is FINISHED our [onPostExecute]
         * override has finished and since it removes all [mTimeoutRunnable] callbacks early in its
         * execution this would be odd). Then if `running` is *true* and the `cancel` method returns
         * *true* indicating that it was able to cancel this [AsyncEvaluator] we:
         * - We set the `mEvaluator` field of the [ExprInfo] stored under key [mIndex] in [mExprs]
         * to *null*.
         * - If our [mRequired] field is *true*, and [mIndex] is the MAIN_INDEX we set the `mExpr`
         * field of [mMainExpr] to a clone of its current `mExpr` field, call our
         * `suppressCancelMessage` method to suppress the "cancelled" dialog by setting our [mQuiet]
         * field to *true*, and then call our [displayTimeoutMessage] method with the `mLongTimeout`
         * field of [mExprInfo] to display the "timed out" dialog.
         */
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

        /**
         * Suppresses the "cancelled" dialog by setting our [mQuiet] field to *true*.
         */
        fun suppressCancelMessage() {
            mQuiet = true
        }

        /**
         * Runs on the UI thread before [doInBackground]. We initialize our variable `timeout` to
         * the value returned by our [timeoutGet] method for the `mLongTimeout` field of [mExprInfo]
         * if our [mRequired] field is *true* (the user has requested evaluation) or to the value
         * QUICK_TIMEOUT (1,000 milliseconds) if it is *false*. Then if [mIndex] is not MAIN_INDEX
         * we set `timeout` to NON_MAIN_TIMEOUT (100,000 milliseconds) since the expression had been
         * evaluated before with the current timeout so we evaluate it with this ridiculously long
         * timeout to avoid running down the battery if something does go wrong.
         *
         * Next we initialize [mTimeoutRunnable] with a [Runnable] whose lambda calls our method
         * [handleTimeout], remove all [mTimeoutRunnable] callbacks from [mTimeoutHandler] then
         * have [mTimeoutHandler] schedule [mTimeoutRunnable] to run after `timeout` milliseconds.
         */
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
         * Is a computed result too big for decimal conversion? If our field [mRequired] is *true*
         * (the user has requested a result) we initialize our variable `maxBits` to the value
         * returned by our [maxResultBitsGet] method for the `mLongTimeout` value of [mExprInfo]
         * (this is 700_000 if it is *true* or 240_000 if *false*), and if [mRequired] is *false*
         * we initialize it to QUICK_MAX_RESULT_BITS (150_000). We then return the value returned
         * by the `approxWholeNumberBitsGreaterThan` method of [res] for `maxBits` (this returns
         * *true* if the number of bits to the left of the decimal point is greater than `maxBits`).
         *
         * @param res the [UnifiedReal] result we are to check for size.
         * @return *true* is [res] is too big for decimal conversion.
         */
        private fun isTooBig(res: UnifiedReal): Boolean {
            val maxBits = if (mRequired)
                maxResultBitsGet(mExprInfo!!.mLongTimeout)
            else
                QUICK_MAX_RESULT_BITS
            return res.approxWholeNumberBitsGreaterThan(maxBits)
        }

        /**
         * Override this method to perform a computation on a background thread. We initialize our
         * variable `res` by fetching the [UnifiedReal] held by the `mVal` field of [mExprInfo] (if
         * there is one). If `res` is *null*, we set `res` to the [UnifiedReal] returned by the
         * `eval` method of the `mExpr` field of [mExprInfo] for the degree mode of [mDm] using
         * *this* as the [Evaluator] (this is all wrapped in a try block intended to catch
         * StackOverflowError in which case we return an [InitialResult] reporting "Value may be
         * infinite or undefined"). If we were cancelled while executing `eval` we throw
         * AbortedException, otherwise we call our [putResultIfAbsent] method to update the `mVal`
         * field of the [Evaluator.ExprInfo] stored under index [mIndex] with `res` if it is not
         * already set (if it was already set `res` is set to the value stored there on return).
         *
         * If our method [isTooBig] decides `res` is too big for decimal conversion we return an
         * [InitialResult] reporting the error "Value may be infinite or undefined". Otherwise we
         * initialize our variable `precOffset` to INIT_PREC, initialize our variable `initResult`
         * to the truncated string representation of `res` truncated to `precOffset`, and initialize
         * our variable `msd` to the most significant digit index of `initResult` that our
         * [msdIndexOfGet] method returns. If `msd` is the value INVALID_MSD ([Integer.MAX_VALUE],
         * which indicates that there are not enough digits to prove the numeric value is different
         * from zero) we so some additional processing in order to try to get a valid `msd`:
         * - We initialize our variable `leadingZeroBits` to the upper bound on the number of
         * leading zero bits returned by the `leadingBinaryZeroes` method of `res`.
         * - If this is less than QUICK_MAX_RESULT_BITS (150_000) we set `precOffset` to 30 plus
         * `leadingZeroBits` times the log of 2 divided by the log of 10, set `initResult` to the
         * string representation of `res` truncated to `precOffset`, and set `msd` to the most
         * significant digit index of `initResult` that our [msdIndexOfGet] method returns (if `msd`
         * is still INVALID_MSD we throw an AssertionError).
         * - If `leadingZeroBits` was greater than or equal to QUICK_MAX_RESULT_BITS, we just set
         * `precOffset` to MAX_MSD_PREC_OFFSET (1_100), set `initResult` to the string representation
         * of `res` truncated to `precOffset`, and set `msd` to the most significant digit index of
         * `initResult` that our [msdIndexOfGet] method returns.
         *
         * We next initialize our variable `lsdOffset` to the rightmost nonzero digit position that
         * is returned by our [lsdOffsetGet] method for `res`, `initResult`, and the location of the
         * first '.' character in `initResult`, we initialize our variable `initDisplayOffset` to the
         * value returned by our [preferredPrecGet] method returns as the preferred precision
         * "offset" for the currently displayed result for `initResult` as the current string
         * approximation, `msd` as the most significant digit, `lsdOffset` as the least significant
         * digit, and [mCharMetricsInfo] as the [CharMetricsInfo] the is to be used. We intialize
         * our variable `newPrecOffset` to `initDisplayOffset` plus EXTRA_DIGITS and if this is
         * greater than `precOffset` we set `precOffset` to `newPrecOffset`, and set `initResult`
         * to the string representation of `res` truncated to `precOffset`.
         *
         * Finally we return an [InitialResult] constructed to hold `res` as its [UnifiedReal] value,
         * `initResult` as the [String] representation of its value, `precOffset` as the precision
         * "offset" of our [String] representation, and `initDisplayOffset` as the preferred precision
         * "offset" for displaying as a result.
         *
         * The above is all wrapped in a *try* block with five *catch* blocks:
         * - [CalculatorExpr.SyntaxException] we return the error [InitialResult] "Bad expression"
         * - [UnifiedReal.ZeroDivisionException] we return the error [InitialResult] "Can't divide by 0"
         * - [ArithmeticException] we return the error [InitialResult] "Not a number"
         * - [CR.PrecisionOverflowException] we return the error [InitialResult] "Infinite?"
         * - [CR.AbortedException] we return the error [InitialResult] "Aborted"
         *
         * @param nothing The parameters of the task (there are none).
         * @return An [InitialResult] instance constructed to hold either the resource ID of an
         * error string if an error occurred or the [UnifiedReal] that results from evaluating the
         * expression in our [mExprInfo], a string representation of this value and precision
         * "offset" information.
         */
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
                val initDisplayOffset = preferredPrecGet(initResult, msd, lsdOffset, mCharMetricsInfo)
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

        /**
         * Runs on the UI thread after [doInBackground]. The specified result is the value returned
         * by [doInBackground]. First we set the `mEvaluator` field of [mExprInfo] to *null*, and
         * remove all [mTimeoutRunnable] runnables from the queue of [mTimeoutHandler]. If our
         * [result] is an [InitialResult] which contains only the resource ID of an error string
         * (its `isError` method returns *true*) we branch on whether the `errorResourceId` field
         * of [result] is for a timeout (R.string.timeout):
         * - It was a timeout: If our [mRequired] field is *true* (user requested a result) and our
         * [mIndex] field is MAIN_INDEX we call our [displayTimeoutMessage] method with the value
         * of the `mLongTimeout` of the [ExprInfo] in [mExprs] at index [mIndex] in order to ask
         * the user if he wants to try a longer timeout value. We then call the `onCancelled` method
         * of [mListener] to report the cancelled evaluation.
         * - A different error: if our [mRequired] field is *true* we set the `mResultString` field
         * of [mExprInfo] to ERRONEOUS_RESULT ("ERR"), then we call the `onError` method of
         * [mListener] with [mIndex] and the `errorResourceId` field of [result].
         *
         * No matter which error we just return now.
         *
         * If [result] is not an error we set the `mResultString` field of [mExprInfo] to the
         * `newResultString` field of [result], and the `mResultStringOffset` field to the
         * `newResultStringOffset` field of [result]. We initialize our variable `dotIndex` to the
         * index of the first '.' character in the `mResultString` field of [mExprInfo], and our
         * variable `truncatedWholePart` to the substring of the `mResultString` field of
         * [mExprInfo] between index 0 and index `dotIndex`. We initialize our variable
         * `initPrecOffset` to the `initDisplayOffset` field of [result], then set the `mMsdIndex`
         * field of [mExprInfo] to the most significant digit index returned by our [msdIndexOfGet]
         * method for the `mResultString` field of [mExprInfo]. We then initialize our variable
         * `leastDigOffset` to the index of the rightmost nonzero digit position returned by our
         * [lsdOffsetGet] method for the [UnifiedReal] in the `unifiedReal` field of [result], the
         * current cached decimal string representation in the `mResultString` field of [mExprInfo],
         * and index of decimal point given by `dotIndex`. We then initialize our variable
         * `newInitPrecOffset` to the preferred precision "offset" for the currently displayed result
         * returned by our [preferredPrecGet] method for the `mResultString` field of [mExprInfo],
         * its `mMsdIndex` field, with `leastDigOffset` as the position of the least significant
         * digit, and [mCharMetricsInfo] as the [CharMetricsInfo] in use. If `newInitPrecOffset` is
         * less than `initPrecOffset` we set `initPrecOffset` to `newInitPrecOffset` (if not we just
         * log this anomaly). Finally we call `onEvaluate` override of [mListener] with [mIndex] as
         * the index of the expression whose evaluation completed, `initPrecOffset` as the offset
         * used for initial evaluation, the `mMsdIndex` field of [mExprInfo] as the index of the
         * first non-zero digit in the computed result string, `leastDigOffset` as the offset of the
         * last digit in the result if result has finite decimal expansion, and `truncatedWholePart`
         * as the integer part of the result.
         *
         * @param result an [InitialResult] instance containing either the resource ID of an error
         * string, or the result of evaluating our expression.
         */
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
            val leastDigOffset = lsdOffsetGet(result.unifiedReal, mExprInfo.mResultString, dotIndex)
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

        /**
         * Runs on the UI thread after [cancel] is invoked and [doInBackground] has finished.
         * First we remove all [mTimeoutRunnable] runnables from the queue of [mTimeoutHandler].
         * If our [mQuiet] field is *false* we call our [displayCancelledMessage] method to pop
         * up a cancelled dialog to inform the user. Finally we call the `onCancelled` override
         * of [mListener] with the index [mIndex].
         *
         * @param result The result, if any, computed in [doInBackground], can be null
         */
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
    private class ReevalResult internal constructor(
            val newResultString: String,
            val newResultStringOffset: Int
    )

    /**
     * Compute new mResultString contents to prec digits to the right of the decimal point.
     * Ensure that onReevaluate() is called after doing so.  If the evaluation fails for reasons
     * other than a timeout, ensure that onError() is called.
     * This assumes that initial evaluation of the expression has been successfully
     * completed.
     */
    @SuppressLint("StaticFieldLeak")
    private inner class AsyncReevaluator internal constructor(
            private val mIndex: Long, // Index of expression to evaluate.
            private val mListener: EvaluationListener
    ) : AsyncTask<Int, Void, ReevalResult>() {

        /**
         * [ExprInfo] of the expression we are to reevaluate.
         */
        private val mExprInfo: ExprInfo? = mExprs[mIndex]

        /**
         * Override this method to perform a computation on a background thread. The specified
         * parameters are the parameters passed to [execute] by the caller of this task. Wrapped
         * in a *try* block intended to catch [ArithmeticException], [CR.PrecisionOverflowException]
         * or [CR.AbortedException] and return *null* for these exceptions we initialize our
         * variable `precOffset` to the zeroth entry in our parameter [prec] then return an
         * [ReevalResult] constructed to hold the result of creating a string representation of
         * the value of the [UnifiedReal] contained in the `mVal` field of [mExprInfo] truncated to
         * `precOffset` digits and the offset value of `precOffset`.
         *
         * @param prec precision of the result string (digits to the right of the decimal place
         * @return a [ReevalResult] containing the new result string and its precision.
         */
        override fun doInBackground(vararg prec: Int?): ReevalResult? {
            return try {
                val precOffset = prec[0]
                ReevalResult(mExprInfo!!.mVal.get().toStringTruncated(precOffset!!), precOffset)
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

        /**
         * Runs on the UI thread after [doInBackground]. [result] is the [ReevalResult] returned by
         * [doInBackground]. If [result] is *null* we set the `mResultString` field of [mExprInfo]
         * to the string ERRONEOUS_RESULT ("ERR"), and call the `onError` callback of [mListener]
         * with our [mIndex] field as the index of the expression and the resource ID for the string
         * "Not a number". Otherwise we make sure the `newResultStringOffset` field of [result] is
         * greater or equal ot the `mResultStringOffset` field of [mExprInfo] (throwing an
         * [AssertionError] if it is not) then set the `mResultString` field of [mExprInfo] to the
         * result of using our [unflipZeroes] method to correcting any digits which flipped to 0's
         * during the reevaluation, set the `mResultStringOffset` field of [mExprInfo] to the
         * `newResultStringOffset` field of [result], and call the `onReevaluate` method of [mListener]
         * with our expression index [mIndex].
         *
         * Finally we set the `mEvaluator` field of [mExprInfo] to *null*.
         *
         * @param result The result of the operation computed by [doInBackground].
         */
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

        // On cancellation we do nothing; invoker should have left no trace of us,
        // Therefore we do not implement onCancelled()

    }

    /**
     * If necessary, start an evaluation of the expression at the given [index] to [precOffset].
     * If we start an evaluation the listener is notified on completion. Only called if prior
     * evaluation succeeded. We initialize our variable `ei` with the [Evaluator.ExprInfo] at
     * index [index] in [mExprs]. If the `mResultString` field of `ei` is not *null* and the
     * `mResultStringOffset` field of `ei` is greater than or equal to [precOffset], OR the
     * `mResultStringOffsetReq` field of `ei` is greater than or equal to [precOffset] we return
     * having done nothing (either the precision of the old result is good enough already, or there
     * is already an evaluation in progress which will be good enough when it finishes). If the
     * `mEvaluator` field of `ei` is not *null* we cancel that expression evaluator and set the
     * field to *null* (in order to ensure we only have one evaluation running at a time). We
     * initialize our variable `reEval` with a new instance of [AsyncReevaluator] constructed to
     * evaluate the expression at index [index] with [listener] as its [EvaluationListener]. We set
     * the `mEvaluator` field  of `ei` to `reEval`, set the `mResultStringOffsetReq` of `ei` to
     * [precOffset] plus PRECOMPUTE_DIGITS (30 additional digits in order to minimize reevaluation
     * frequency). If the `mResultString` field of `ei` is not *null* we add the `mResultStringOffsetReq`
     * field divided by PRECOMPUTE_DIVISOR to the `mResultStringOffsetReq` field (this has the
     * effect of adding an additional 20% to the request precision when there is an existing result
     * string). We then call the `execute` method of `reEval` to have it start an asynchronous
     * reevaluation to the precision given by the `mResultStringOffsetReq` field of `ei`.
     *
     * @param index index of the expression we are to evaluate
     * @param precOffset precision we are to evaluate to.
     * @param listener [EvaluationListener] whose callbacks should be informed of evaluation result.
     */
    private fun ensureCachePrec(
            index: Long,
            precOffset: Int,
            listener: EvaluationListener
    ) {
        val ei = mExprs[index]

        if (ei!!.mResultString != null && ei.mResultStringOffset >= precOffset
                || ei.mResultStringOffsetReq >= precOffset) return
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
     * is too close to zero to determine the result. Result is almost consistent through
     * reevaluations: It may increase by one, once. We initialize our variable `ei` to the
     * [Evaluator.ExprInfo] in [mExprs] at index [index]. If the `mMsdIndex` field of `ei` is not
     * INVALID_MSD we check if the character at `mMsdIndex` in the `mResultString` field of `ei`
     * is '0' and increment the `mMsdIndex` field of `ei` and '0' character of not return the
     * `mMsdIndex` field of `ei` to the caller.
     *
     * Otherwise the current value of `mMsdIndex` is INVALID_MSD, so we first check to see if this
     * is because the result is a real 0 so we call the `definitelyZero()` method of the [UnifiedReal]
     * in the `mVal` field of `ei` and if it returns *true* we return INVALID_MSD to the caller as
     * there is no most significant digit in an absolute 0.
     *
     * We need to search for the most significant digit index at this point so we initialize our
     * variable `result` to INVALID_MSD, then if the `mResultString` field of `ei` is not *null*
     * we set the `mMsdIndex` field of `ei` to the value returned by our [msdIndexOfGet] method
     * when it searches the `mResultString` field of `ei` then we set `result` to that field.
     * Finally we return `result` to the caller.
     *
     * @param index index of the expression whose result we should examine
     * @return the index of the most significant digit of the result string of the expression.
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
     * Return result to `precOffset[0]` digits to the right of the decimal point. `precOffset[0]` is
     * updated if the original value is out of range. No exponent or other indication of precision
     * is added.  The result is returned immediately, based on the current cache contents, but it
     * may contain blanks for unknown digits. It may also use uncertain digits within EXTRA_DIGITS.
     * If either of those occurred, schedule a reevaluation and redisplay operation. Uncertain digits
     * never appear to the left of the decimal point. `precOffset[0]` may be negative to only retrieve
     * digits to the left of the decimal point. (`precOffset[0]` = 0 means we include the decimal
     * point, but nothing to the right. precOffset[0] = -1 means we drop the decimal point and start
     * at the ones position. Should not be invoked before the `onEvaluate` callback is received. This
     * essentially just returns a substring of the full result; a leading minus sign or leading
     * digits can be dropped. Result uses US conventions; is NOT internationalized. Use resultGet()
     * and UnifiedReal operations to determine whether the result is exact, or whether we dropped
     * trailing digits.
     *
     * We initialize our variable `ei` to the [Evaluator.ExprInfo] in [mExprs] at index [index]. We
     * initialize our variable `currentPrecOffset` to the value at index 0 in [precOffset]. If the
     * `mResultString` field of `ei` is *null* we just call our [ensureCachePrec] method to have it
     * start an [AsyncReevaluator] that will eventually set the `mResultString` to the precision of
     * `currentPrecOffset` plus EXTRA_DIGITS using [listener] for callbacks then we return the string
     * " " to the caller. Otherwise we initialize our variable `newPrec` to `currentPrecOffset` plus
     * EXTRA_DIGITS plus the length of the `mResultString` field of `ei` divided by EXTRA_DIVISOR and
     * call our [ensureCachePrec] method to have it start an [AsyncReevaluator] that will eventually
     * set the `mResultString` to the precision of `newPrec` using [listener] for callbacks.
     *
     * We now need to compute an appropriate substring of the `mResultString` field padding it if it
     * is necessary. We initialize our variable `len` to the length of the `mResultString` field of
     * `ei`, and initialize our variable `myNegative` to *true* if the zeroth character of the
     * `mResultString` field of `ei` is '-', we then save `myNegative` in index 0 of [negative]. We
     * initialize our variable `integralDigits` to `len` minus the `mResultStringOffset` field of
     * `ei` and if `myNegative` is *true* decrement `integralDigits`. We initialize our variable
     * `minPrecOffset` to the minimum of MIN_DISPLAYED_DIGS minus `integralDigits` and minus 1. We
     * then set `currentPrecOffset` to the minimum of the maximum of `currentPrecOffset` and
     * `minPrecOffset`, and [maxPrecOffset]. We then save `currentPrecOffset` in the zeroth index of
     * [precOffset]. We initialize our variable `extraDigs` to the `mResultStringOffset` field of
     * `ei` minus `currentPrecOffset` (this is the number of trailing digits to drop). We initialize
     * our variable `deficit` to 0 (number of digits we're short). Then if `extraDigs` is less than
     * 0 we set `extraDigs` to 0 and set `deficit` to the minimum of `currentPrecOffset` minus the
     * `mResultStringOffset` field of `ei` and [maxDigs]. We initialize our variable `endIndex` to
     * `len` minus `extraDigs`, and if `endIndex` is less than 1 we return the string " ". Otherwise
     * we initialize our variable `startIndex` to the maximum of `endIndex` plus `deficit` minus
     * `maxDigs` and 0. We set the zeroth entry in [truncated] to *true* if `startIndex` is greater
     * than the value that our [msdIndexGet] method returns as the index for the character which is
     * the most significant digit in the result string of the expression at index [index]. We then
     * initialize our variable `result` to the substring of the `mResultString` field of `ei` between
     * `startIndex` and `endIndex`. If `deficit` is greater than 0 we add `deficit` blank characters
     * to the end of `result`. Finally we return `result` to the caller.
     *
     * @param index Index of expression to approximate
     * @param precOffset Zeroth element indicates desired and actual precision
     * @param maxPrecOffset Maximum adjusted precOffset[0]
     * @param maxDigs Maximum length of result
     * @param truncated Zeroth element is set if leading nonzero digits were dropped
     * @param negative Zeroth element is set if the result is negative.
     * @param listener EvaluationListener to notify when reevaluation is complete.
     * @return [String] representation of the result to at least `precOffset[0]` digits
     */
    fun stringGet(
            index: Long,
            precOffset: IntArray,
            maxPrecOffset: Int,
            maxDigs: Int,
            truncated: BooleanArray,
            negative: BooleanArray,
            listener: EvaluationListener
    ): String {
        val ei = mExprs[index]
        var currentPrecOffset = precOffset[0]
        // Make sure we eventually get a complete answer

        if (ei!!.mResultString == null) {
            ensureCachePrec(index, currentPrecOffset + EXTRA_DIGITS, listener)
            // Nothing else to do now; seems to happen on rare occasion with weird user input
            // timing; Will repair itself in a jiffy.
            return " "
        } else {
            val newPrec = currentPrecOffset + EXTRA_DIGITS + ei.mResultString!!.length / EXTRA_DIVISOR
            ensureCachePrec(index, newPrec, listener)
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
     * Clear the cache for the main expression. We set the `mVal` field of [mMainExpr] to *null*
     * (the [UnifiedReal] that results from evaluating its expression), set its `mResultString`
     * field to *null*, set its `mResultStringOffsetReq` field to 0, set its `mResultStringOffset`
     * field to its `mResultStringOffsetReq` (0 recall), and set its `mMsdIndex` field to INVALID_MSD.
     */
    private fun clearMainCache() {
        mMainExpr!!.mVal.set(null)
        mMainExpr!!.mResultString = null
        mMainExpr!!.mResultStringOffsetReq = 0
        mMainExpr!!.mResultStringOffset = mMainExpr!!.mResultStringOffsetReq
        mMainExpr!!.mMsdIndex = INVALID_MSD
    }

    /**
     * "Clears" the main expression. First we call the `clear()` method of the [CalculatorExpr] in
     * the `mExpr` field of [mMainExpr], then we set our [mHasTrigFuncs] field to false, and call
     * our [clearMainCache] method to clear the cache for the main expression. Finally we set the
     * `mLongTimeout` field of [mMainExpr] to *false*.
     */
    fun clearMain() {
        mMainExpr!!.mExpr.clear()
        mHasTrigFuncs = false
        clearMainCache()
        mMainExpr!!.mLongTimeout = false
    }

    /**
     * This is called by the [AlertDialogFragment] which is launched by the "Clear History" option
     * of the [HistoryFragment] and clears all stored and cached expressions. First we save the
     * value of the `mDegreeMode` field of [mMainExpr] in our variable `dm`. We call our [cancelAll]
     * method with *true* for the "quiet" flag to have it call their `cancel` method to cancel all
     * background tasks associated with all the [Evaluator.ExprInfo] in our [mExprs] cache. We set
     * the index of the saved expression to 0, and set the index of the memory expression to 0. We
     * then call the `eraseAll` method of [mExprDB] to have it erase all database entries, clear
     * [mExprs] of all of its [ExprInfo] entries, and finally call our [setMainExpr] method to have
     * it set our field [mMainExpr] to a new instance of [ExprInfo] constructed using a new instance
     * of [CalculatorExpr] and `dm` as its degree mode.
     */
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
     * Start asynchronous evaluation. Invoke listener on successful completion. If the result is
     * required, invoke `onCancelled()` if cancelled. We initialize our variable `ei` the the
     * [ExprInfo] at index [index] in [mExprs]. If [index] is the MAIN_INDEX we call our
     * [clearMainCache] method to clear the cache for the main expression (all the other expressions
     * are immutable so their cache remains valid forever). We initialize our variable `eval` with
     * a new instance of [AsyncEvaluator] constructed to evaluate the expression with index [index],
     * using [listener] as the [EvaluationListener] whose completion callback it should call, [cmi]
     * as the [CharMetricsInfo] to use to get result size information, the `mDegreeMode` field of
     * `ei` for the degree mode flag, and [required] to indicate whether the result was requested by
     * the user pressing the "=" button. We then set the `mEvaluator` field of `ei` to `eval`, and
     * call the `execute` method of `ei` to have it start the background thread running. Finally if
     * [index] is MAIN_INDEX we set our [mChangedValue] field to *false* to indicate that the main
     * expression has not changed since the last evaluation in a way that would affect its value.
     *
     * @param index index of expression to be evaluated.
     * @param listener the [EvaluationListener] to invoke on successful completion.
     * @param cmi the [CharMetricsInfo] to query for information derived from character widths
     * @param required result was explicitly requested by user.
     */
    private fun evaluateResult(
            index: Long,
            listener: EvaluationListener?,
            cmi: CharMetricsInfo,
            required: Boolean
    ) {
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
     * Notify listener of a previously completed evaluation immediately. We initialize our variable
     * `dotIndex` with the index of any '.' character in the `mResultString` field of [ei]. We then
     * initialize our variable `truncatedWholePart` to the substring of the `mResultString` field of
     * [ei] between 0 and `dotIndex`. We initialize our field `leastDigOffset` to the least the
     * index of the least significant digit that our [lsdOffsetGet] method finds when searching for
     * the rightmost nonzero digit position when examining the [UnifiedReal] in the `mVal` field of
     * `ei` with the `mResultString` field of `ei` as the cached decimal string representation of
     * the result, and `dotIndex` as the index of the decimal point in that cache. We initialize our
     * variable `msdIndex` with the index of the most significant digit of the result string of the
     * expression with index [index] that our [msdIndexGet] method returns. We initialize our variable
     * `preferredPrecOffset` to the value that our [preferredPrecGet] method returns as the preferred
     * precision "offset" for the currently displayed result when passed the `mResultString` field of
     * `ei` as the current approximation as a string, `msdIndex` as the position of most significant
     * digit in that result, `leastDigOffset` as the position of the least significant digit, and
     * [cmi] as the [CharMetricsInfo] to query for information derived from character widths. Finally
     * we call the `onEvaluate` callback of [listener] to notify it of the normal evaluation completion
     * of the expression with index [index], passing it `preferredPrecOffset` as the offset used for
     * initial evaluation, `msdIndex` as the index of the first non-zero digit in the computed result
     * string, `leastDigOffset` as the offset of last digit in result if result has finite decimal
     * expansion, and `truncatedWholePart` as the integer part of the result.
     *
     * @param index index of expression to be evaluated.
     * @param ei the [ExprInfo] containing the result of the evaluation.
     * @param listener the [EvaluationListener] to invoke on successful completion.
     * @param cmi the [CharMetricsInfo] to query for information derived from character widths
     */
    internal fun notifyImmediately(
            index: Long,
            ei: ExprInfo?,
            listener: EvaluationListener?,
            cmi: CharMetricsInfo
    ) {
        val dotIndex = ei!!.mResultString!!.indexOf('.')
        val truncatedWholePart = ei.mResultString!!.substring(0, dotIndex)
        val leastDigOffset = lsdOffsetGet(ei.mVal.get(), ei.mResultString, dotIndex)
        val msdIndex = msdIndexGet(index)
        val preferredPrecOffset = preferredPrecGet(ei.mResultString!!, msdIndex, leastDigOffset, cmi)
        listener?.onEvaluate(index, preferredPrecOffset, msdIndex, leastDigOffset, truncatedWholePart)
    }

    /**
     * Start optional evaluation of expression and display when ready. Can quietly time out without
     * a listener callback. No-op if cmi.maxCharsGet() == 0. If the `maxCharsGet` method of [cmi]
     * returns 0 indicating that no characters will fit in the result display we just return having
     * done nothing. Otherwise we initialize our variable `ei` with the [ExprInfo] returned by our
     * [ensureExprIsCached] method after it ensures that the expression with index [index] is cached
     * in [mExprs]. If the `mResultString` field of `ei` is not *null* and is not equal to
     * ERRONEOUS_RESULT, and if [index] is MAIN_INDEX our [mChangedValue] field is not *true* we
     * are already done so we just call our [notifyImmediately] method with MAIN_INDEX as the index
     * of the expression, [mMainExpr] as the [ExprInfo] containing the result of the evaluation,
     * [listener] as the [EvaluationListener] to invoke, and [cmi] as the [CharMetricsInfo] to query
     * for information derived from character widths and then we return. Otherwise we make sure
     * that the `mEvaluator` field of `ei` is *null* and if it is not we just return since this
     * request must be redundant. If it was *null* we call our [evaluateResult] method to have it
     * start the asynchronous evaluation of the expression with index [index], using [listener] as
     * the [EvaluationListener] to invoke on successful completion, [cmi] as the [CharMetricsInfo]
     * to query for information derived from character widths, and *false* as the required flag to
     * indicate the result was not requested by the user.
     *
     * @param index of expression to be evaluated.
     * @param listener the [EvaluationListener] to have its completion callback called when done.
     * @param cmi the [CharMetricsInfo] to query for information derived from character widths
     */
    fun evaluateAndNotify(
            index: Long,
            listener: EvaluationListener?,
            cmi: CharMetricsInfo
    ) {
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
     * Start required evaluation of expression at given index and call back listener when ready. If
     * index is MAIN_INDEX, we may also directly display a timeout message. Uses longer timeouts
     * than optional evaluation. Requires cmi.maxCharsGet() != 0. If the `maxCharsGet` method of
     * [cmi] returns 0 (no room for any characters in the display) we throw an AssertionError
     * "requireResult called too early". Otherwise we initialize our variable `ei` with the
     * [ExprInfo] that our [ensureExprIsCached] method returns after it ensures that the expression
     * at index [index] is cached in [mExprs]. We branch on whether the `mResultString` field of `ei`
     * is *null* or [index] is MAIN_INDEX and our [mChangedValue] is *true*:
     * - We do not have a cached string or we are the main expression which has changed:
     *     - If [index] is HISTORY_MAIN_INDEX, we pretend we timed out by calling the `onCancelled`
     *     method of [listener] for [index] since we don't want to compute a result for it that was
     *     not already computed for the main expression.
     *     - If the `mEvaluator` field of `ei` is an [AsyncEvaluator] and its `mRequired` field is
     *     *true* this is a duplicate request so we jus ignore it
     *     - Otherwise we want to restart the evaluator so we call our `cancel` method to cancel
     *     `ei` quietly, then call our [evaluateResult] method to have it start the asynchronous
     *     evaluation of the expression with index [index], using [listener] for its the
     *     [EvaluationListener] to notify about successful completion, and [cmi] as the
     *     [CharMetricsInfo] to query for information derived from character widths
     * - When the `mResultString` field of `ei` is ERRONEOUS_RESULT we just re-evaluate to generate
     * a new notification by calling our `cancel` method to cancel `ei` quietly, then calling our
     * [evaluateResult] method to have it start the asynchronous evaluation of the expression with
     * index [index], using [listener] for its the [EvaluationListener] to notify about successful
     * completion, and [cmi] as the [CharMetricsInfo] to query for information derived from character
     * widths.
     * - For all other values of the `mResultString` field of `ei` we just call our [notifyImmediately]
     * method to have it immediately notify [listener] of the completed evaluation of the expression
     * with index [index], using `ei` as the [ExprInfo] containing the result of the evaluation,
     * calling the [EvaluationListener] callback of [listener], and using [cmi] as the [CharMetricsInfo]
     * to query for information derived from character widths.
     *
     * @param index Index of expression to be evaluated.
     * @param listener the [EvaluationListener] to have its completion callback called when done.
     * @param cmi the [CharMetricsInfo] to query for information derived from character widths
     */
    fun requireResult(
            index: Long,
            listener: EvaluationListener?,
            cmi: CharMetricsInfo
    ) {
        if (cmi.maxCharsGet() == 0) {
            throw AssertionError("requireResult called too early")
        }
        val ei = ensureExprIsCached(index)
        when {
            ei.mResultString == null || index == MAIN_INDEX && mChangedValue ->
                when {
                    index == HISTORY_MAIN_INDEX ->
                        // We don't want to compute a result for HISTORY_MAIN_INDEX that was
                        // not already computed for the main expression. Pretend we timed out.
                        // The error case doesn't get here.
                        listener?.onCancelled(index)
                    ei.mEvaluator is AsyncEvaluator && (ei.mEvaluator as AsyncEvaluator).mRequired -> {
                        // Duplicate request; ignore.
                    }
                    else -> {
                        // (Re)start evaluator in requested mode, i.e. with longer timeout.
                        cancel(ei, true)
                        evaluateResult(index, listener, cmi, true)
                    }
                }
            ei.mResultString == ERRONEOUS_RESULT -> {
                // Just re-evaluate to generate a new notification.
                cancel(ei, true)
                evaluateResult(index, listener, cmi, true)
            }
            else -> notifyImmediately(index, ei, listener, cmi)
        }
    }

    /**
     * Whether this expression has explicitly been evaluated (User pressed "="). We initialize our
     * variable `ei` with the [ExprInfo] that our [ensureExprIsCached] method returns after it ensures
     * that the expression at index [index] is cached in [mExprs]. Then we return *true* if the
     * `mResultString` field of `ei` is not *null*.
     *
     * @param index The index of the expression we are to query.
     * @return *true* if the `mResultString` field of the [Evaluator.ExprInfo] of the expression
     * with index [index] is not *null*
     */
    fun hasResult(index: Long): Boolean {
        val ei = ensureExprIsCached(index)
        return ei.mResultString != null
    }

    /**
     * Is a reevaluation still in progress? We initialize our variable `ei` with the [ExprInfo] that
     * our [ensureExprIsCached] method returns after it ensures that the expression at index [index]
     * is cached in [mExprs]. Then we return *true* if the `mEvaluator` field of `ei` is not *null*.
     *
     * @param index The index of the expression we are to query.
     * @return *true* if the `mEvaluator` field of the [Evaluator.ExprInfo] of the expression
     * with index [index] is not *null*
     */
    fun evaluationInProgress(index: Long): Boolean {
        val ei = mExprs[index]
        return ei?.mEvaluator != null
    }

    /**
     * Cancel any current background task associated with the given [ExprInfo]. If the `mEvaluator`
     * field of [expr] is *null* we just return *false*. If it is not *null* we check if our [quiet]
     * parameter is *true* and the `mEvaluator` field of [expr] is a [AsyncEvaluator] instance and
     * if so we call the `suppressCancelMessage` of the `mEvaluator` field of [expr] which sets itd
     * `mQuiet` field to *true* to suppress the "cancelled" dialog. Then if the [UnifiedReal] in the
     * `mVal` field of [expr] is not *null* we call the `cancel` method of the `mEvaluator` field of
     * [expr] to cancel the background task (interrupting it if necessary), set the field
     * `mResultStringOffsetReq` of [expr] to its `mResultStringOffset` field, and set its `mEvaluator`
     * field to *null* (then fall through to return *false*). If the `mVal` field of [expr] is *null*
     * we call the `cancel` method of the `mEvaluator` field of [expr] to cancel the background task
     * (interrupting it if necessary). If [expr] is the same as [mMainExpr] we have a problem as
     * the expression is modifiable, and the [AsyncTask] is reading it, so we need to clone the
     * [CalculatorExpr] in the `mExpr` field of [mMainExpr] and set that field to that clone, then
     * set our [mChangedValue] field to *true* (to indicate that we didn't do the expected evaluation).
     * In either case we set the `mEvaluator` field of [expr] to *null* and return *true*.
     *
     * @param expr The [ExprInfo] whose background task we should cancel.
     * @param quiet suppress cancellation message
     * @return *true* if we cancelled an initial evaluation
     */
    private fun cancel(
            expr: ExprInfo,
            quiet: Boolean
    ): Boolean {
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
     * Cancel any current background task associated with the [ExprInfo] with index [index]. We
     * initialize our variable `ei` with the [ExprInfo] in our [mExprs] cache at index [index],
     * then return the result of calling our overloaded method `cancel(ExprInfo,Boolean)` with *it*
     * and our parameter [quiet] (Elvis'ing to return *false* if `ei` was *null*).
     *
     * @param index index of the expression whose background task we are to cancel
     * @param quiet suppress cancellation message
     * @return *true* if we cancelled an initial evaluation
     */
    fun cancel(
            index: Long,
            quiet: Boolean
    ): Boolean {
        val ei = mExprs[index]
        return ei?.let { cancel(it, quiet) } ?: false
    }

    /**
     * Cancel all background tasks associated with the [ExprInfo] objects in [mExprs] suppressing
     * the cancellation message if our [quiet] parameter is *true*. We loop over `expr` for all
     * of the [Evaluator.ExprInfo] in our [mExprs] cache calling our [cancel] method to cancel any
     * current background task associated with `expr`, and suppressing the cancellation message if
     * our [quiet] parameter is *true*.
     *
     * @param quiet suppress cancellation message
     */
    fun cancelAll(quiet: Boolean) {
        // TODO: May want to keep active evaluators in a HashSet to avoid traversing
        // all expressions we've looked at.
        for (expr in mExprs.values) {
            cancel(expr, quiet)
        }
    }

    /**
     * Quietly cancel all evaluations associated with expressions other than the main one.
     * These are currently the evaluations associated with the history fragment. We loop over
     * `expr` for all of the [Evaluator.ExprInfo] in our [mExprs] cache calling our [cancel] method
     * to cancel any current background task associated with `expr` if `expr` is not the same as
     * [mMainExpr] suppressing the cancellation message.
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
     * Restore the evaluator state, including the current expression. First we set our [mChangedValue]
     * field to *true*, then wrapped in a *try* block intended to catch and log [IOException] we set
     * the `mDegreeMode` field of [mMainExpr] to the [Boolean] read from our parameter [dataInput],
     * its `mLongTimeout` field to the [Boolean] read from our parameter [dataInput], and its
     * `mExpr` field to a [CalculatorExpr] instance constructed by reading from our parameter
     * [dataInput]. We then set our [mHasTrigFuncs] to the [Boolean] returned by our [hasTrigFuncs]
     * method.
     *
     * @param dataInput the [DataInput] we are to read our state from.
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
     * Save the evaluator state, including the expression and any saved value. Wrapped in a *try*
     * block intended to catch and log [IOException] we write the [Boolean] in the `mDegreeMode`
     * field of [mMainExpr] to our parameter [dataOutput], followed by its `mLongTimeout` field.
     * We then call the `write` method of the `mExpr` field of [mMainExpr] to have that
     * [CalculatorExpr] write itself to our parameter [dataOutput].
     *
     * @param dataOutput the [DataOutput] we are to write our state to.
     */
    fun saveInstanceState(dataOutput: DataOutput) {
        try {
            dataOutput.writeBoolean(mMainExpr!!.mDegreeMode)
            dataOutput.writeBoolean(mMainExpr!!.mLongTimeout)
            mMainExpr!!.mExpr.write(dataOutput)
        } catch (e: IOException) {
            Log.v("Calculator", "Exception while saving state:\n$e")
        }

    }

    /**
     * Append a button press to the main expression. If the key id [id] is R.id.fun_10pow (10^X) we
     * call our [add10pow] to add the three keys this expands to: ('1', '0', and '^') to the main
     * expression [mMainExpr], then return *true*. Otherwise we set our field [mChangedValue] to
     * its current value or'ed with *true* if the [KeyMaps.isBinary] method returns *false* for [id]
     * (which indicates that the key is not a binary operator). Then if the `add` method of the
     * `mExpr` field of [mMainExpr] returns *true* to indicate it was able to add the [id] key to the
     * expression, we set [mHasTrigFuncs] to *true* if it is currently *false* and the method
     * [KeyMaps.isTrigFunc] returns *true* to indicate that the [id] key is a trig function then
     * return *true* to the caller. If the `add` method of the `mExpr` field of [mMainExpr] returns
     * *false* to indicate it was not able to add the [id] key to the expression we return *false*
     * to reject the insertion.
     *
     * @param id Button identifier for the character or operator to be added.
     * @return *false* if we rejected the insertion due to obvious syntax issues, and the expression
     * is unchanged; *true* otherwise
     */
    fun append(id: Int): Boolean {
        return if (id == R.id.fun_10pow) { // toggled by the INV button
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
     * Delete last token from main expression. We set our [mChangedValue] field to *true* the mark
     * the expression as having changed. We then call the `delete` method of the `mExpr` field of
     * [mMainExpr] to delete the last character added if any. If the `isEmpty` method of the `mExpr`
     * field of [mMainExpr] returns *true* to indicate that the [CalculatorExpr] now contains no
     * elements we set the `mLongTimeout` field of [mMainExpr] to *false*. Finally we set our
     * [mHasTrigFuncs] to the value that our [hasTrigFuncs] method returns as the value of our
     * [mHasTrigFuncs] field (*true* if the main expression contains trig functions).
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
     * Set degree mode for main expression. We set our [mChangedValue] to *true*, then save our
     * parameter [degreeMode] in the `mDegreeMode` field of [mMainExpr]. Finally we use our field
     * [mSharedPrefs] to create a new `Editor` for our preference file, which we use to store
     * [degreeMode] as a [Boolean] under the key KEY_PREF_DEGREE_MODE, and then commit the edit.
     *
     * @param degreeMode *true* to use degrees for trig functions, *false* to use radians.
     */
    fun setDegreeMode(degreeMode: Boolean) {
        mChangedValue = true
        mMainExpr!!.mDegreeMode = degreeMode

        mSharedPrefs.edit()
                .putBoolean(KEY_PREF_DEGREE_MODE, degreeMode)
                .apply()
    }

    /**
     * Return an [ExprInfo] for a copy of the expression with the given index. We remove trailing
     * binary operators in the copy. The `mTimeStamp` field is not copied. We initialize our variable
     * `fromEi` to the [ExprInfo] at index [index] in [mExprs]. We then initialize our variable `ei`
     * to a new instance of [ExprInfo] constructed from a clone of the [CalculatorExpr] in the `mExpr`
     * field of `fromEi` and the `mDegreeMode` field of `fromEi`. While the `hasTrailingBinary` method
     * of the `mExpr` field of `ei` returns *true* we call the `delete` method of the `mExpr` field
     * of `ei` to delete that trailing binary operator from the [CalculatorExpr]. If our [copyValue]
     * parameter is *true* we set the `mVal` field of `ei` to an [AtomicReference] constructed from
     * the `mVal` field of `fromEi`, set the `mResultString` field of `ei` to the `mResultString`
     * field of `fromEi`, set the `mResultStringOffsetReq` field of `ei` to the `mResultStringOffsetReq`
     * field of `fromEi`, set the `mResultStringOffset` field of `ei` to the `mResultStringOffset`
     * field of `fromEi`, and set the `mMsdIndex` field of `ei` to the `mMsdIndex` field of `fromEi`.
     * Whether [copyValue] is *true* or *false* we set the `mLongTimeout` field of `ei` to the
     * `mLongTimeout` field of `fromEi` and return `ei` to the caller.
     *
     * @param index the index of the the expression which we should copy from.
     * @param copyValue if *true* the results of the last evaluation of the expression are copied
     * also (we are always called with *true* at the moment).
     * @return An [ExprInfo] constructed to be a deep copy clone of the [ExprInfo] at index [index]
     * in [mExprs]
     */
    private fun copy(
            index: Long,
            copyValue: Boolean
    ): ExprInfo {
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
     * Return an [ExprInfo] corresponding to the sum of the expressions at the two indices. [index1]
     * should correspond to an immutable expression, and should thus NOT be MAIN_INDEX. [index2] may
     * be MAIN_INDEX. Both expressions are presumed to have been evaluated.  The result is unevaluated.
     * Can return *null* if evaluation resulted in an error (a very unlikely case). We just return the
     * [ExprInfo] returned by our [generalizedSum] method that it constructs for our parameters [index1]
     * and [index2] and the resource ID for the addition button R.id.op_add.
     *
     * @param index1 Index of the first addend [ExprInfo] in our [mExprs] cache, should be an immutable
     * expression (ie. not MAIN_INDEX).
     * @param index2 Index of the second addend [ExprInfo] in our [mExprs] cache, can be any expression.
     * @return an [ExprInfo] constructed by adding an R.id.op_add ("+" addition) operator between the
     * [CalculatorExpr] of the [ExprInfo] instances corresponding to our two parameters.
     */
    private fun sum(
            index1: Long,
            index2: Long
    ): ExprInfo? {
        return generalizedSum(index1, index2, R.id.op_add)
    }

    /**
     * Return an [ExprInfo] corresponding to the subtraction of the value at the [subtrahendIndex]
     * index from value at the [minuendIndex] index (minuend - subtrahend = result). Both are presumed
     * to have been previously evaluated. The result is unevaluated. Can return null. We just return
     * the [ExprInfo] returned by our [generalizedSum] method that it constructs for our parameters
     * [minuendIndex] and [subtrahendIndex] and the resource ID for the subtraction button R.id.op_sub.
     *
     * @param minuendIndex Index of the minuend [ExprInfo] in our [mExprs] cache
     * @param subtrahendIndex Index of the subtrahend [ExprInfo] in our [mExprs] cache
     * @return an [ExprInfo] constructed by adding an R.id.op_sub ("-" subtraction) operator between
     * the [CalculatorExpr] of the [ExprInfo] of our two parameters.
     */
    private fun difference(
            minuendIndex: Long,
            subtrahendIndex: Long
    ): ExprInfo? {
        return generalizedSum(minuendIndex, subtrahendIndex, R.id.op_sub)
    }

    /**
     * Return an [ExprInfo] whose [CalculatorExpr] is constructed by joining the [CalculatorExpr] of
     * the two [ExprInfo] parameters stored under [index1] and [index2] in the [mExprs] cache by the
     * binary operator whose button resource ID is [op]. First we initialize our variable `result`
     * with a new instance of [CalculatorExpr]. We intialize our variable `collapsed1` with the
     * [CalculatorExpr] returned by our [collapsedExprGet] method for the [ExprInfo] in the [mExprs]
     * cache stored under index [index1], and our variable `collapsed2` with the [CalculatorExpr] it
     * returns for the [ExprInfo] in the [mExprs] cache stored under index [index2]. If `collapsed1`
     * or `collapsed2` is *null* we return *null* to the caller. We append `collapsed1` to `result`
     * add `op` to it, and then append `collapsed2` to it. We initialize our variable `resultEi` to
     * a new instance of [ExprInfo] constructed from `result` with *false* for the degree mode flag.
     * We set the `mLongTimeout` field of `resultEi` to the logical or of the `mLongTimeout` field
     * of the [ExprInfo] stored under index [index1] in [mExprs] and [ExprInfo] stored under index
     * [index2], then we return `resultEi` to the caller.
     *
     * @param index1 Index of the first [ExprInfo] in our [mExprs] cache, should be an immutable
     * expression (ie. not MAIN_INDEX).
     * @param index2 Index of the second [ExprInfo] in our [mExprs] cache, can be any expression.
     * @param op The resource ID of the operator button to place between the two [CalculatorExpr]
     * of the [ExprInfo] referenced by our parameters [index1] and [index2].
     * @return an [ExprInfo] constructed by adding an [op] operator between the [CalculatorExpr]
     * of the [ExprInfo] of our parameters [index1] and [index2].
     */
    private fun generalizedSum(
            index1: Long,
            index2: Long,
            op: Int
    ): ExprInfo? {
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
     * Add the expression described by the argument to the database. Returns the new row id in the
     * database. Fills in timestamp in [ei], if it was not previously set. If [inHistory] is *true*,
     * add it with a positive index, so it will appear in the history. We initialize our variable
     * `serializedExpr` to the [ByteArray] created from the `mExpr` [CalculatorExpr] field of [ei]
     * by its `toBytes` method. We initialize our variable `rd` to an [ExpressionDB.RowData] instance
     * constructed from `serializedExpr`, the `mDegreeMode` field of [ei], the `mLongTimeout` field
     * of [ei] and a timestamp of 0. We then initialize our variable `resultIndex` to the database
     * index that the `addRow` method of [mExprDB] returns after adding `rd` to the database outside
     * of the existing range (the row will be added with a negative index if [inHistory] is *false*
     * and will not appear in the history). If the [ExprInfo] stored at index `resultIndex` in our
     * [mExprs] cache is not *null* we throw an [AssertionError] ("result slot already occupied!...")
     * otherwise we set the `mTimeStamp` field of [ei] to the `mTimeStamp` field of `rd`. Then if
     * `resultIndex` is MAIN_INDEX we throw an [AssertionError] ("Should not store main expression").
     * Otherwise we store [ei] in our [mExprs] cache under index `resultIndex` and return `resultIndex`
     * to the caller.
     *
     * @param inHistory if *true* add the [ExprInfo] in [ei] to the database with a positive index,
     * so it will appear in the history
     * @param ei the [ExprInfo] to add to the database.
     * @return the index of the [ExprInfo] in our [mExprs] cache.
     */
    private fun addToDB(
            inHistory: Boolean,
            ei: ExprInfo
    ): Long {
        val serializedExpr = ei.mExpr.toBytes()
        val rd = ExpressionDB.RowData(serializedExpr, ei.mDegreeMode, ei.mLongTimeout, 0)
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
     * Preserve a copy of the expression at [oldIndex] at a new index. This is useful only if
     * [oldIndex] is MAIN_INDEX or HISTORY_MAIN_INDEX. This assumes that initial evaluation
     * completed successfully. We initialize our variable `ei` to the [ExprInfo] that our [copy]
     * method creates by making a deep copy of the [ExprInfo] at index [oldIndex] in our [mExprs]
     * cache (passing *true* for its `copyValue` parameter so that the results of its last
     * evaluation are copied too). If the `mResultString` field of `ei` is *null* or equal to
     * ERRONEOUS_RESULT we throw an [AssertionError] ("Preserving unevaluated expression").
     * Otherwise we return the index that our [addToDB] method returns after it adds `ei` to our
     * database (and to our history if our [inHistory] parameter is *true*).
     *
     * @param oldIndex index of the expression in our [mExprs] cache of [ExprInfo].
     * @param inHistory use a positive index so the result appears in the history.
     * @return the new index
     */
    fun preserve(
            oldIndex: Long,
            inHistory: Boolean
    ): Long {
        val ei = copy(oldIndex, true)
        if (ei.mResultString == null || ei.mResultString == ERRONEOUS_RESULT) {
            throw AssertionError("Preserving unevaluated expression")
        }
        return addToDB(inHistory, ei)
    }

    /**
     * Preserve a copy of the current main expression as the most recent history entry, assuming it
     * is already in the database, but may have been lost from the cache. We initialize our variable
     * `resultIndex` to the maximum index value in our database that is returned by our [maxIndexGet]
     * method (this is the index of the last expression added to the database which appears in the
     * history). Then we call our [ensureExprIsCached] method with `resultIndex` to have it make sure
     * that the [ExprInfo] with index `resultIndex` is in our [mExprs] cache.
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
     * Discard previous expression in HISTORY_MAIN_INDEX and replace it by a fresh copy of the main
     * expression. Note that the HISTORY_MAIN_INDEX expression is not preserved in the database or
     * anywhere else; it is always reconstructed when needed. First we call our method `cancel` to
     * cancel any background task associated with the [ExprInfo] with index HISTORY_MAIN_INDEX with
     * the quiet flag *true* to suppress the cancel dialog. Then we initialize our variable `ei` to
     * the deep copy of the [ExprInfo] with index MAIN_INDEX copying the results of its evaluation
     * as well. Then we store `ei` in our [mExprs] cache under index HISTORY_MAIN_INDEX.
     */
    fun copyMainToHistory() {
        cancel(HISTORY_MAIN_INDEX, true /* quiet */)
        val ei = copy(MAIN_INDEX, true)
        mExprs[HISTORY_MAIN_INDEX] = ei
    }

    /**
     * "Collapses" the expression with index [index] to the [CalculatorExpr] representation of its
     * result. The resulting expression contains a single "token" with the pre-evaluated result.
     * The client should ensure that this is never invoked unless initial evaluation of the
     * expression has been completed. If our parameter [index] refers to a mutable expression (only
     * MAIN_INDEX or HISTORY_MAIN_INDEX are mutable since they refer to the current expression in
     * the calculator display) we call our [preserve] method to preserve a copy of the expression at
     * [index] at a new index and we use the new index that it returns to initialize our variable
     * `realIndex`, otherwise we initialize it to [index]. We initialize our variable `ei` to the
     * [ExprInfo] stored at index `realIndex` in our [mExprs] cache, and our variable `rs` to the
     * `mResultString` field of `ei`. If `rs` is *null* or equal to ERRONEOUS_RESULT we return
     * *null* to the caller. Otherwise we initialize our variable `dotIndex` to the index of the
     * first '.' character in `rs`, and our variable `leastDigOffset` to the index of the rightmost
     * nonzero digit position that our [lsdOffsetGet] method returns when it examines the [UnifiedReal]
     * in the `mVal` field of `ei` using `rs` as the cached short result and starts looking in `rs`
     * starting at `dotIndex`. Finally we return the [CalculatorExpr] that the `abbreviate` method
     * of the [CalculatorExpr] in the `mExpr` field of `ei` returns when it constructs a new
     * expression consisting of a single token representing the current pre-evaluated expression
     * using `realIndex` as the database index of the expression it is to abbreviate to a single
     * token, and the short string representation that our [shortStringGet] method creates using
     * `rs` as the string approximation of value, the most significant digit index that our method
     * [msdIndexOfGet] returns for `rs` and `leastDigOffset` as the position of the least significant
     * digit in finite representation, relative to the decimal point (could be MAX_VALUE if does not
     * have a finite representation).
     *
     * @param index Index of the [ExprInfo] in our [mExprs] cache we are interested in.
     * @return the [CalculatorExpr] representation of the result of the given expression.
     */
    private fun collapsedExprGet(
            index: Long
    ): CalculatorExpr? {
        val realIndex = if (isMutableIndex(index)) preserve(index,false) else index
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
        return ei.mExpr.abbreviate(realIndex, shortStringGet(rs, msdIndexOfGet(rs), leastDigOffset))
    }

    /**
     * Abbreviate the indicated expression to a pre-evaluated expression node, and use that as the
     * new main expression. This should not be called unless the expression was previously evaluated
     * and produced a non-error result. Pre-evaluated expressions can never represent an expression
     * for which evaluation to a constructive real diverges. Subsequent re-evaluation will also not
     * diverge, though it may generate errors of various kinds (E.g. sqrt(-10^-1000)). We initialize
     * our variable `longTimeout` to the `mLongTimeout` field of the [ExprInfo] stored at index
     * [index] in our [mExprs] cache. We then initialize our variable `abbrvExpr` to the "collapsed"
     * [CalculatorExpr] that our [collapsedExprGet] method constructs from the [ExprInfo] stored at
     * index [index] in our [mExprs] cache. We call our [clearMain] method to completely clear the
     * current main expression. We use an [assert] to make sure `abbrvExpr` is not *null*, then call
     * the `append` method of the [CalculatorExpr] in the `mExpr` field of [mMainExpr] to append
     * `abbrvExpr` to it (`abbrvExpr` is now the only token in the main expression). We then set the
     * `mLongTimeout` field of [mMainExpr] to our variable `longTimeout`, set [mChangedValue] to
     * *true*, and set [mHasTrigFuncs] to *false* (change of degree mode cannot affect the collapsed
     * expression).
     *
     * @param index index of the [ExprInfo] in our [mExprs] we are to "collapse".
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

    /**
     * This abstract class is implemented by our [SetMemoryWhenDoneListener] class which overrides
     * our [setNow] method to set the memory index when our [onEvaluate] override is called and by
     * our [SetSavedWhenDoneListener] class which overrides our [setNow] method to set the index of
     * the "saved" expression mirroring the clipboard when our [onEvaluate] override is called.
     */
    private abstract inner class SetWhenDoneListener : EvaluationListener {
        /**
         * This method exists solely to be called to throw an [AssertionError] if anyone calls our
         * [onReevaluate] override.
         */
        private fun badCall() {
            throw AssertionError("unexpected callback")
        }

        /**
         * Classes which implement us need to override this method in order for our [onEvaluate]
         * override to call it to do whatever needs to be done with the evaluation result.
         */
        internal abstract fun setNow()

        /**
         * Called if evaluation was explicitly cancelled or evaluation timed out. We ignore.
         *
         * @param index the index of the the expression which was cancelled.
         */
        override fun onCancelled(index: Long) {}  // Extremely unlikely; leave unset.

        /**
         * Called if evaluation resulted in an error. We ignore.
         *
         * @param index the index of the the expression which was cancelled.
         * @param errorId the resource ID of the string describing the error.
         */
        override fun onError(index: Long, errorId: Int) {}  // Extremely unlikely; leave unset.

        /**
         * Called if evaluation completed normally. We just call the [setNow] method which classes
         * which inherit from us need to implement.
         *
         * @param index index of expression whose evaluation completed
         * @param initPrecOffset the offset used for initial evaluation
         * @param msdIndex index of first non-zero digit in the computed result string
         * @param lsdOffset offset of last digit in result if result has finite decimal expansion
         * @param truncatedWholePart the integer part of the result
         */
        override fun onEvaluate(
                index: Long,
                initPrecOffset: Int,
                msdIndex: Int,
                lsdOffset: Int,
                truncatedWholePart: String) {
            setNow()
        }

        /**
         * Called in response to a reevaluation request, once more precision is available. Typically
         * the listener wil respond by calling stringGet() to retrieve the new better approximation.
         * We just call our [badCall] method to throw an [AssertionError].
         *
         * @param index the index of the the expression which was reevaluated.
         */
        override fun onReevaluate(index: Long) {
            badCall()
        }
    }

    /**
     * This subclass of [SetWhenDoneListener] overrides the [setNow] method to set the [mMemoryIndex]
     * field to [mIndex] when the `onEvaluate` method of that [EvaluationListener] subclass is called
     * after the evaluation of the expression is complete. It is used as the [EvaluationListener] when
     * the result of the manipulation of the "memory" expression is stored back to "memory". If our
     * construction parameter [mPersist] is *true* [mIndex] is stored to our preference file too.
     */
    private inner class SetMemoryWhenDoneListener internal constructor(
            internal val mIndex: Long,
            internal val mPersist: Boolean
    ) : SetWhenDoneListener() {
        /**
         * This override of [SetWhenDoneListener.setNow] is used when the result of the evaluation
         * needs to be stored to the memory expression [mMemoryIndex] (ie. when the user selects the
         * "add to memory" or "subtract from memory" options from the context menu that pops up when
         * the calculator display is long clicked, as well as in the *init* block of [Evaluator] to
         * restore the index of the memory expression from the shared preference file). If our
         * [mMemoryIndex] field is not 0 we throw an [AssertionError] "Overwriting nonzero memory
         * index". If our [mPersist] field is *true* we call our [setMemoryIndex] method to set
         * [mMemoryIndex] to [mIndex] and write the new value to our shared preference file.
         * Otherwise we just set [mMemoryIndex] to [mIndex].
         */
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

    /**
     * This subclass of [SetWhenDoneListener] overrides the [setNow] method to set the [mSavedIndex]
     * field to [mIndex] when the `onEvaluate` method of that [EvaluationListener] subclass is called
     * after the evaluation of the expression is complete. It is used as the [EvaluationListener] in
     * the *init* block of [Evaluator] in order to restore the expression which mirrors the clipboard
     * to that which was stored in our shared preferences file.
     */
    private inner class SetSavedWhenDoneListener internal constructor(
            internal val mIndex: Long
    ) : SetWhenDoneListener() {
        /**
         * This override of [SetWhenDoneListener.setNow] is used when the result of the evaluation
         * needs to be stored to [mSavedIndex] (the "saved" expression mirroring the clipboard) and
         * that is exactly what it does.
         */
        override fun setNow() {
            mSavedIndex = mIndex
        }
    }

    /**
     * Set the local and persistent memory index. First we set our field [mMemoryIndex] to our
     * parameter [index]. Then we use our field [mSharedPrefs] to create a new instance of
     * [SharedPreferences.Editor] which we use to store the [Long] value [index] under the key
     * KEY_PREF_MEMORY_INDEX, and then we commit the change. If our field [mCallback] is not *null*
     * we call its `onMemoryStateChanged` override to report that the "memory" index has changed.
     *
     * @param index Index of the expression which is to become the "memory" index.
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
     * Set the local and persistent saved index (index of the expression which has been copied to the
     * clipboard). We set our field [mSavedIndex] to our parameter [index], then use our field
     * [mSharedPrefs] to create a new instance of [SharedPreferences.Editor] which we use to store
     * the [Long] value [index] under the key KEY_PREF_SAVED_INDEX, and then we commit the change.
     *
     * @param index Index of the expression which has been "copied" to the clipboard.
     */
    private fun setSavedIndex(index: Long) {
        mSavedIndex = index
        mSharedPrefs.edit()
                .putLong(KEY_PREF_SAVED_INDEX, index)
                .apply()
    }

    /**
     * Set [mMemoryIndex] (possibly including the persistent version) to [index] when we finish
     * evaluating the corresponding expression. We just call our [requireResult] method to have it
     * evaluate the expression with index [index] in the background using a new instance of
     * [SetMemoryWhenDoneListener] constructed using our parameters as its [EvaluationListener], and
     * [mDummyCharMetricsInfo] as its [CharMetricsInfo].
     *
     * @param index Index of the expression which is to become the memory index after evaluation.
     * @param persist If *true* write the new index value to our shared preferences file.
     */
    internal fun setMemoryIndexWhenEvaluated(index: Long, persist: Boolean) {
        requireResult(index, SetMemoryWhenDoneListener(index, persist), mDummyCharMetricsInfo)
    }

    /**
     * Set [mSavedIndex] (not the persistent version) to [index] when we finish evaluating the
     * corresponding expression. This is called from the *init* block of [Evaluator] to restore
     * [mSavedIndex] to the value that was stored in our shared preferences file. We just call our
     * [requireResult] method to have it evaluate the expression with index [index] in the background
     * using a new instance of [SetSavedWhenDoneListener] constructed using our parameter [index] as
     * its [EvaluationListener], and [mDummyCharMetricsInfo] as its [CharMetricsInfo].
     *
     * @param index Index of the expression which is to become the saved index after evaluation.
     */
    internal fun setSavedIndexWhenEvaluated(index: Long) {
        requireResult(index, SetSavedWhenDoneListener(index), mDummyCharMetricsInfo)
    }

    /**
     * Save an immutable version of the expression at the given [index] as the saved value. `mExpr`
     * is left alone. Return *false* if result is unavailable. If the `mResultString` field of the
     * [ExprInfo] at index [index] in our [mExprs] cache is *null* or equal to ERRONEOUS_RESULT we
     * just return *false* to the caller. Otherwise we call our [setSavedIndex] method with [index]
     * if our [isMutableIndex] method returns *false* (the expression is an immutable one from history)
     * or the index returned by our [preserve] method after it preserves the expression to our database
     * if it is a mutable index ([setSavedIndex] sets our [mSavedIndex] field and stores the value
     * to our shared preferences file). Finally we return *true* to the caller.
     *
     * @param index Index of the expression which is to become the saved index
     * @return *false* if result is unavailable, or *true* if it was.
     */
    private fun copyToSaved(index: Long): Boolean {

        if (mExprs[index]!!.mResultString == null) return false
        if (mExprs[index]!!.mResultString == ERRONEOUS_RESULT) return false
        setSavedIndex(if (isMutableIndex(index)) preserve(index, false) else index)
        return true
    }

    /**
     * Save an immutable version of the expression at the given [index] as the "memory" value. The
     * expression at [index] is presumed to have been evaluated. We call our [setMemoryIndex] method
     * with [index] if our [isMutableIndex] method returns *false* (the expression is an immutable
     * one from history) or the index returned by our [preserve] method after it preserves the
     * expression to our database if it is a mutable index ([setMemoryIndex] sets our [mMemoryIndex]
     * field and stores the value to our shared preferences file).
     *
     * @param index Index of the expression which is to become the index of the "memory" value.
     */
    fun copyToMemory(index: Long) {
        setMemoryIndex(if (isMutableIndex(index)) preserve(index, false) else index)
    }

    /**
     * Save an an expression representing the sum of "memory" and the expression with the given
     * [index]. Make [mMemoryIndex] point to it when we complete evaluating. We initialize our
     * variable `newEi` to the [ExprInfo] returned by our [sum] method that it constructs by
     * joining the expressions with indices [mMemoryIndex] and [index] with a "+" add operator.
     * If `newEi` is not *null* we initialize our variable `newIndex` to the index that our
     * [addToDB] method returns after adding `newEi` to our database without making it visible
     * in history. We then set [mMemoryIndex] to 0 (we need to invalidate it while we're evaluating)
     * and call our [setMemoryIndexWhenEvaluated] to start the background evaluation of the expression
     * with index `newIndex`, setting [mMemoryIndex] to `newIndex` when the evaluation is done and
     * persisting the new value to our shared preferences file.
     *
     * @param index Index of the expression which is to be added to the "memory" value.
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
     * Save an an expression representing the subtraction of the expression with the given [index]
     * from "memory." Make [mMemoryIndex] point to it when we complete evaluating. We initialize our
     * variable `newEi` to the [ExprInfo] returned by our [difference] method that it constructs by
     * joining the expressions with indices [mMemoryIndex] and [index] with a "-" subtract operator.
     * If `newEi` is not *null* we initialize our variable `newIndex` to the index that our
     * [addToDB] method returns after adding `newEi` to our database without making it visible
     * in history. We then set [mMemoryIndex] to 0 (we need to invalidate it while we're evaluating)
     * and call our [setMemoryIndexWhenEvaluated] to start the background evaluation of the expression
     * with index `newIndex`, setting [mMemoryIndex] to `newIndex` when the evaluation is done and
     * persisting the new value to our shared preferences file.
     *
     * @param index Index of the expression which is to be subtracted from the "memory" value.
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
     *
     * @return the value of our [mSavedIndex] field.
     */
    fun savedIndexGet(): Long {
        return mSavedIndex
    }

    /**
     * Return index of "memory" expression, or 0.
     *
     * @return the value of our [mMemoryIndex] field.
     */
    fun memoryIndexGet(): Long {
        return mMemoryIndex
    }

    /**
     * Builds and returns a unique private [Uri] from our [mSavedName] field that is used as the
     * [Uri] part of a `ClipData` `Item` when one of our expressions is copied to the clipboard.
     * We construct a [Uri.Builder], set its scheme to "tag", set its previously encoded opaque
     * scheme-specific-part to our [mSavedName], build the [Uri] then return it to the caller.
     *
     * @return a unique private [Uri] describing expression to be saved to the clipboard.
     */
    private fun uriForSaved(): Uri {
        return Uri.Builder().scheme("tag")
                .encodedOpaquePart(mSavedName)
                .build()
    }

    /**
     * Save the [index] expression as the saved location and return a [Uri] describing it. The [Uri]
     * is used to distinguish this particular result from others we may generate. If our method
     * [copyToSaved] returns *false* because a result for the expression with index [index] is not
     * available we return *null*. Otherwise we initialize our variable `tz` with the default
     * [TimeZone] for our host, and our variable `df` with an instance of [SimpleDateFormat] for the
     * format "yyyy-MM-dd", set the `timeZone` field of `df` to `tz`, then initialize our variable
     * `isoDate` with the [Date] of the current time formatted according to the format of `df`. We
     * then set our field [mSavedName] to the string formed by concatenating "calculator2.android.com,"
     * followed by `isoDate`, followed by the string ":", followed by the string value of the lower
     * 30 bits of the next random [Int]. We then use our field [mSharedPrefs] to create a shared
     * preferences `Editor` which we use to store [mSavedName] in our shared preferences file under
     * the key KEY_PREF_SAVED_NAME and commit the change. Finally we return the [Uri] returned by
     * our [uriForSaved] method to the caller.
     *
     * @param index Index of the expression which is to be captured to the clipboard.
     * @return a unique private [Uri] describing the result copied to the clipboard.
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

    /**
     * Tests its parameter [uri] to see if it references the expression with index [mSavedIndex]
     * and saved name [mSavedName] that we recently copied to the clipboard. If [mSavedIndex] is
     * equal to 0 we return *false* (we have no expression on the clipboard), otherwise we return
     * *true* if our parameter [uri] is equal to the [Uri] that our [uriForSaved] method constructs
     * from [mSavedName].
     *
     * @param uri the [Uri] that we want to test to see if it references [mSavedIndex]
     * @return *true* if our parameter [uri] is equal to that which our [uriForSaved] constructs for
     * the current value of [mSavedName].
     */
    fun isLastSaved(uri: Uri): Boolean {
        return mSavedIndex != 0L && uri == uriForSaved()
    }

    /**
     * Append the expression at index [index] as a pre-evaluated expression to the main expression.
     * We initialize our variable `ei` with the [ExprInfo] stored under index [index] in our [mExprs]
     * cache, and set our [mChangedValue] field to *true* (the main expression will have been changed
     * and need to be reevaluated when we return). We then set the `mLongTimeout` field of [mMainExpr]
     * to the logical or of the `mLongTimeout` field of [mMainExpr] and the `mLongTimeout` field of
     * `ei`. We initialize our variable `collapsed` to the [CalculatorExpr] returned by our method
     * [collapsedExprGet] for the expression with index [index], and if that is not *null* we call
     * the `append` method of the [CalculatorExpr] in the `mExpr` field of [mMainExpr] to have it
     * append the collapsed [CalculatorExpr] that our method [collapsedExprGet] returns for the
     * expression with index [index] to itself.
     *
     * @param index Index of the expression which is to be appended to the main expression.
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
     * Add the power of 10 operator to the main expression. This is treated essentially as a macro
     * expansion. We initialize our variable `ten` with a new instance of [CalculatorExpr]. We add
     * the resource id for the key "1" to `ten` followed by the resource id for the key "0". We
     * set our [mChangedValue] field to *true* then use the `append` method of the [CalculatorExpr]
     * field `mExpr` of [mMainExpr] to append `ten` to the main expression, followed by a call the
     * the `add` method of `mExpr` to add the resource id for the "power" key to the main expression.
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
     * Ensure that the expression with the given index [index] is in [mExprs]. We assume that if it
     * is either already in [mExprs] or [mExprDB]. When we're done, the expression in [mExprs] may
     * still contain references to other subexpressions that are not yet cached. First we initialize
     * our variable `ei` with the [ExprInfo] stored under index [index] in our cache [mExprs], and
     * if this is not *null* we return it to the caller. If [index] is MAIN_INDEX we throw an
     * [AssertionError] "Main expression should be cached". Otherwise we initialize our variable
     * `row` with the [ExpressionDB.RowData] that the `rowGet` method of our [mExprDB] field returns
     * for the database row index [index]. We initialize our variable `serializedExpr` with a new
     * instance of [DataInputStream] constructed from a [ByteArrayInputStream] constructed from the
     * `mExpression` byte array field of `row`. Then wrapped in a *try* block intended to catch
     * [IOException] in order to re-throw it as an [AssertionError] we set `ei` to a new instance of
     * [ExprInfo] constructed from a [CalculatorExpr] that is constructed by reading in `serializedExpr`
     * and the `degreeMode` that the `degreeMode` method of `row` extracts from the flags of the row.
     * We then set the `mTimeStamp` field of `ei` to the `mTimeStamp` field of `row` and the
     * `mLongTimeout` field of `ei` to the `longTimeout` that the `longTimeout` method of `row`
     * extracts from the flags of the row. We initialize our variable `newEi` to the [ExprInfo]
     * returned by the `putIfAbsent` method of [mExprs] after storing `ei` under key [index] in
     * [mExprs]. We then return `newEi` to the caller (or `ei` if `newEi` is *null*).
     *
     * @param index Index of the expression we need cached in our [ExprInfo] cache [mExprs].
     * @return the [ExprInfo] that is cached under index [index] in our [mExprs] cache.
     */
    private fun ensureExprIsCached(index: Long): ExprInfo {
        var ei = mExprs[index]
        if (ei != null) {
            return ei
        }
        if (index == MAIN_INDEX) {
            throw AssertionError("Main expression should be cached")
        }
        val row = mExprDB.rowGet(index)
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

    /**
     * Returns the [CalculatorExpr] in the `mExpr` field of the [ExprInfo] at index [index] of our
     * [mExprs] cache that our [ensureExprIsCached] method returns after it makes sure that that
     * expression is in the cache. Part of the [CalculatorExpr.ExprResolver] interface.
     *
     * @param index the index of the the expression whose [CalculatorExpr] we need to get.
     * @return The [CalculatorExpr] in the `mExpr` field of the [ExprInfo] with index [index].
     */
    override fun exprGet(index: Long): CalculatorExpr {
        return ensureExprIsCached(index).mExpr
    }

    /**
     * Return timestamp associated with the expression in milliseconds since epoch. Yields zero if
     * the expression has not been written to or read from the database. We return the `mTimeStamp`
     * field of the [ExprInfo] at index [index] of our [mExprs] cache that our [ensureExprIsCached]
     * method returns after it makes sure that that expression is in the cache.
     *
     * @param index the index of the the expression whose time stamp we want.
     * @return the timestamp in the `mTimeStamp` field of the [ExprInfo] with index [index].
     */
    fun timeStampGet(index: Long): Long {
        return ensureExprIsCached(index).mTimeStamp
    }

    /**
     * Returns the degree mode flag in the `mDegreeMode` field of the [ExprInfo] at index [index] of
     * our [mExprs] cache that our [ensureExprIsCached] method returns after it makes sure that that
     * expression is in the cache. Part of the [CalculatorExpr.ExprResolver] interface.
     *
     * @param index the index of the the expression whose degree mode flag we want.
     * @return the degree mode flag in the `mDegreeMode` field of the [ExprInfo] with index [index].
     */
    override fun degreeModeGet(index: Long): Boolean {
        return ensureExprIsCached(index).mDegreeMode
    }

    /**
     * Returns the [UnifiedReal] that results from evaluating the expression at index [index] of our
     * [mExprs] cache that our [ensureExprIsCached] method returns (after it makes sure that that
     * expression is in the cache) using the `eval` method. Part of the [CalculatorExpr.ExprResolver]
     * interface.
     *
     * @param index the index of the the expression which we want evaluated to a [UnifiedReal].
     * @return the [UnifiedReal] that results from calling the `eval` method of the expression which
     * is cached under index [index] in our [mExprs] cache of [ExprInfo] expressions.
     */
    override fun resultGet(index: Long): UnifiedReal? {
        return ensureExprIsCached(index).mVal.get()
    }

    /**
     * Atomically test for an existing result, and set it if there was none. Return the prior result
     * if there was one, or the new one if there was not. May only be called after [exprGet].
     *
     * @param index index of the expression that we are interested in.
     * @param result the [UnifiedReal] we are to save at index [index].
     * @return if there was no [UnifiedReal] at [index] we return [result], otherwise we return
     * the [UnifiedReal] which already occupies position [index].
     */
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
     * (Might its value depend on DEG/RAD mode?)
     *
     * @return *true* if our [mHasTrigFuncs] is *true* (indicating that the main expression has trig
     * functions.
     */
    fun hasTrigFuncs(): Boolean {
        return mHasTrigFuncs
    }

    /**
     * Add the exponent represented by s[begin..end) to the constant at the end of current
     * expression. The end of the current expression must be a constant. Exponents have the
     * same syntax as for [exponentEnd]. We initialize our variable `sign` to 1, our variable
     * `exp` to 0, and our variable `i` to [begin] plus 1. If our [KeyMaps.keyForChar] decides
     * that the resource ID of the key that produces the character at index `i` in [s] is
     * the minus character (R.id.op_sub), we set `sign` to minus 1, and increment `i`. We then
     * loop while `i` is less than [end] setting `exp` to 10 times `exp` plus the numeric value
     * of the character at index `i` in [s] in the 10 radix that the [Character.digit] method
     * returns. When done calculating `exp` we call the `addExponent` method of the `mExpr` field
     * of [mMainExpr] to have it add `sign` times `exp` as an exponent to the `Constant` at the
     * end of the expression. Finally we set our [mChangedValue] field to *true* to indicate that
     * the main expression has changed and needs to be reevaluated.
     *
     * @param s the [String] which contains the exponent to add to the end of the current expression.
     * @param begin the beginning index of the substring of [s] to add as an exponent.
     * @param end the ending index of the substring of [s] to add as an exponent.
     */
    fun addExponent(s: String, begin: Int, end: Int) {
        var sign = 1
        var exp = 0
        var i = begin + 1
        // We do the decimal conversion ourselves to exactly match exponentEnd() conventions
        // and handle various kinds of digits on input. Also avoids allocation.
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
     * Generate a [String] representation of the expression at the given index. This has the side
     * effect of adding the expression to [mExprs]. The expression must exist in the database.
     * We just return the result returned by the `toString` method of the [Spannable] returned
     * by our [exprAsSpannableGet] method for [index].
     *
     * @param index index of the expression that we are interested in.
     * @return [String] representation of the expression at the given [index].
     */
    fun exprAsStringGet(index: Long): String {
        return exprAsSpannableGet(index).toString()
    }

    /**
     * Generate a [Spannable] representation of the expression at the given index. This has the side
     * effect of adding the expression to [mExprs]. The expression must exist in the database. We
     * chain the [CalculatorExpr] returned by our [exprGet] method returns for the expression at
     * index [index] to a call to its `toSpannableStringBuilder` method for our [Context] field
     * [mContext] returning the `SpannableStringBuilder` string representation of the expression
     * (`SpannableStringBuilder` implements the [Spannable] interface).
     *
     * @param index index of the expression that we are interested in.
     * @return [Spannable] representation of the expression at the given [index].
     */
    fun exprAsSpannableGet(index: Long): Spannable {
        return exprGet(index).toSpannableStringBuilder(mContext)
    }

    /**
     * Generate a String representation of all expressions in the database. Debugging only.
     *
     * @return a [String] which contains the [String] representation of all the expressions in the
     * database
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
     * Wait for pending writes to the database to complete. We just call the `waitForWrites` method
     * of our field [mExprDB] which loops until its `mIncompleteWrites` counter goes to 0 when all
     * of the background writing threads have finished their work. This is called from the
     * `onSaveInstanceState` override to delay exiting since the state it has saved may depend on
     * expressions being written.
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

    /**
     * This is the interface which is implemented by users of [Evaluator] instances which want to
     * be notified of important state changes or events.
     */
    interface Callback {
        /**
         * This callback is called when the state of the "memory" register has changed, and in our
         * case it is called when the index of the memory expression has changed or when the history
         * has been cleared.
         */
        fun onMemoryStateChanged()

        /**
         * This callback is called when the [Evaluator] needs to show an alert dialog. We call it
         * when our background evaluation is canceled, or times out with resource IDs appropriate
         * for those occurrences.
         *
         * @param title resource id for the title string
         * @param message resource id for the displayed message string
         * @param positiveButtonLabel label for second button, if any. If non-null, activity must
         * implement *AlertDialogFragment.OnClickListener* to respond to that button being clicked.
         * @param tag tag for the *Fragment* that the *FragmentManager* will add.
         */
        fun showMessageDialog(@StringRes title: Int, @StringRes message: Int,
                              @StringRes positiveButtonLabel: Int, tag: String?)
    }

    /**
     * Our static methods, variables, and constants.
     */
    companion object {
        /**
         * TAG used for logging.
         */
        internal const val TAG = "Evaluator"

        /**
         * Our singleton [Evaluator] instance.
         */
        @SuppressLint("StaticFieldLeak")
        private var evaluator: Evaluator? = null

        /**
         * The fragment TAG for our timeout [AlertDialogFragment].
         */
        const val TIMEOUT_DIALOG_TAG = "timeout"

        /**
         * The lazy getter for our singleton [Evaluator] instance. If our [evaluator] field is *null*
         * we set it to a new instance of [Evaluator]. In any case we then return [evaluator] to the
         * caller.
         *
         * @param context the application [Context] to use to construct an [Evaluator] if needed.
         * @return our singleton [Evaluator] instance.
         */
        fun instanceGet(context: Context): Evaluator {
            if (evaluator == null) {
                evaluator = Evaluator(context.applicationContext)
            }
            return evaluator as Evaluator
        }

        /**
         * Index of main expression.
         */
        const val MAIN_INDEX: Long = 0

        // Once final evaluation of an expression is complete, or when we need to save
        // a partial result, we copy the main expression to a non-zero index.
        // At that point, the expression no longer changes, and is preserved
        // until the entire history is cleared. Only expressions at nonzero indices
        // may be embedded in other expressions.
        // Each expression index can only have one outstanding evaluation request at a time.
        // To avoid conflicts between the history and main View, we copy the main expression
        // to allow independent evaluation by both.

        /**
         * Read-only copy of main expression.
         */
        const val HISTORY_MAIN_INDEX: Long = -1

        // When naming variables and fields, "Offset" denotes a character offset in a string
        // representing a decimal number, where the offset is relative to the decimal point.  1 =
        // tenths position, -1 = units position.  Integer.MAX_VALUE is sometimes used for the offset
        // of the last digit in an a non-terminating decimal expansion.  We use the suffix "Index" to
        // denote a zero-based absolute index into such a string. (In other contexts, like above,
        // we also use "index" to refer to the key in mExprs below, the list of all known
        // expressions.)

        /**
         * The key under which the current `degreeMode` is stored in the shared preferences file.
         */
        private const val KEY_PREF_DEGREE_MODE = "degree_mode"
        /**
         * The key under which the index of the expression copied to clipboard is stored in the
         * shared preferences file.
         */
        private const val KEY_PREF_SAVED_INDEX = "saved_index"
        /**
         * The key under which the index of the expression in the memory register is stored in the
         * shared preferences file.
         */
        private const val KEY_PREF_MEMORY_INDEX = "memory_index"
        /**
         * The key under which the saved name of the expression copied to clipboard is stored in the
         * shared preferences file.
         */
        private const val KEY_PREF_SAVED_NAME = "saved_name"

        /**
         * The minimum number of extra digits we always try to compute to improve the chance of
         * producing a correctly-rounded-towards-zero result.  The extra digits can be displayed to
         * avoid generating placeholder digits, but should only be displayed briefly while computing.
         */
        private const val EXTRA_DIGITS = 20

        /**
         * We adjust EXTRA_DIGITS by adding the length of the previous result divided by
         * EXTRA_DIVISOR.  This helps hide recompute latency when long results are requested;
         * We start the re-computation substantially before the need is likely to be visible.
         */
        private const val EXTRA_DIVISOR = 5

        /**
         * In addition to insisting on extra digits (see above), we minimize reevaluation frequency
         * by pre-computing an extra PRECOMPUTE_DIGITS + current_precision_offset/PRECOMPUTE_DIVISOR
         * digits, whenever we are forced to reevaluate. The last term is dropped if prec < 0.
         */
        private const val PRECOMPUTE_DIGITS = 30
        /**
         * Divisor used to divide current_precision_offset by when calculating how many extra digits
         * we should pre-compute.
         */
        private const val PRECOMPUTE_DIVISOR = 5

        /**
         * Initial evaluation precision. Enough to guarantee that we can compute the short
         * representation, and that we rarely have to evaluate nonzero results to MAX_MSD_PREC_OFFSET.
         * It also helps if this is at least EXTRA_DIGITS + display width, so that we don't
         * immediately need a second evaluation.
         */
        private const val INIT_PREC = 50

        /**
         * The largest number of digits to the right of the decimal point to which we will evaluate
         * to compute proper scientific notation for values close to zero. Chosen to ensure that we
         * always do better than IEEE double precision at identifying non-zeros. And then some.
         * This is used only when we cannot a priori determine the most significant digit position,
         * as we always can if we have a rational representation.
         */
        private const val MAX_MSD_PREC_OFFSET = 1_100

        /**
         * If we can replace an exponent by this many leading zeroes, we do so. Also used in
         * estimating exponent size for truncating short representation.
         */
        private const val EXP_COST = 3

        /**
         * The value returned if we cannot determine the index of the most significant digit.
         */
        const val INVALID_MSD = Integer.MAX_VALUE

        /**
         * Used to represent an erroneous result or a required evaluation. Not displayed.
         */
        private const val ERRONEOUS_RESULT = "ERR"

        /**
         * Timeout for unrequested, speculative evaluations, in milliseconds.
         */
        private const val QUICK_TIMEOUT: Long = 1_000

        /**
         * Timeout for non-MAIN expressions. Note that there may be many such evaluations in
         * progress on the same thread or core. Thus the evaluation latency may include that needed
         * to complete previously enqueued evaluations. Thus the `longTimeout` flag is not very
         * meaningful, and is currently ignored. Since this is only used for expressions that we
         * have previously successfully evaluated, these timeouts should never trigger.
         */
        private const val NON_MAIN_TIMEOUT: Long = 100_000

        /**
         * Maximum result bit length for unrequested, speculative evaluations.
         * Also used to bound evaluation precision for small non-zero fractions.
         */
        private const val QUICK_MAX_RESULT_BITS = 150_000

        /**
         * Check whether a new higher precision result flips previously computed trailing 9s
         * to zeroes. If so, flip them back. Return the adjusted result.
         *
         * Assumes [newPrecOffset] >= [oldPrecOffset] > 0.
         *
         * Since our results are accurate to < 1 ulp, this can only happen if the true result
         * is less than the new result with trailing zeroes, and thus appending 9s to the
         * old result must also be correct. Such flips are impossible if the newly computed
         * digits consist of anything other than zeroes. It is unclear that there are real cases
         * in which this is necessary, but we have failed to prove there aren't such cases.
         *
         * We initialize our `val oldLen` to the length of [oldDigs]. If the last character of
         * [oldDigs] is not '9' we just return [newDigs]. We then initialize our `val newLen` to
         * the length of [newDigs], our `val precDiff` to [newPrecOffset] minus [oldPrecOffset],
         * and our `val oldLastInNew` to `newLen` minus 1 minus `precDiff` (this is the index of
         * the least significant digit of [oldDigs] in [newDigs]). If the digit at `oldLastInNew`
         * in [newDigs] is not now a '0' character we just return [newDigs] to the caller. We then
         * make sure that earlier digits of [newDigs] starting at `newLen` minus `precDiff` are all
         * now `precDiff` repeats of the '0' character and throw an [AssertionError] if they are not
         * all '0' characters. Otherwise we return the string formed by concatenating [oldDigs] to
         * a string formed of `precDiff` '9' digits.
         *
         * @param oldDigs The old result string approximation.
         * @param oldPrecOffset The precision of the [oldDigs] string approximation.
         * @param newDigs The new result string approximation.
         * @param newPrecOffset The precision of the [newDigs] string approximation.
         * @return The [newDigs] result string adjusted to undo any trailing 9s flipped to 0s.
         */
        @VisibleForTesting
        fun unflipZeroes(
                oldDigs: String,
                oldPrecOffset: Int,
                newDigs: String,
                newPrecOffset: Int
        ): String {
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
         * Return the rightmost nonzero digit position, if any. If the `definitelyZero` method of
         * [value] determines that the [BoundedRational] it holds is actually "0" we just return
         * [Integer.MIN_VALUE] to the caller. Otherwise we initialize our `var result` to the value
         * that the `digitsRequired` method of [value] returns for the number of decimal digits to
         * the right of the decimal point required to represent the argument exactly (this will be
         * [Integer.MAX_VALUE] if the number of digits cannot be determined). If the number of
         * digits to the right of the decimal point in `result` is 0, we need to search to the
         * right of the decimal point for a non-zero digit, so we initialize our `var i` to minus 1
         * then loop while [decIndex] plus `i` is greater than 0, and the character at [decIndex]
         * plus `i` in [cache] is a '0' character decrementing `i`. When a non-zero character is
         * found (or we run out of characters to check) we set `result` to `i`.
         *
         * Finally we return `result` to the caller.
         *
         * @param value UnifiedReal value of result.
         * @param cache Current cached decimal string representation of result.
         * @param decIndex Index of decimal point in cache.
         * @return Position of rightmost nonzero digit relative to decimal point.
         * Integer.MIN_VALUE if we cannot determine. Integer.MAX_VALUE if there is no lsd,
         * or we cannot determine it.
         */
        internal fun lsdOffsetGet(
                value: UnifiedReal,
                cache: String?,
                decIndex: Int
        ): Int {
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
         * Retrieve the preferred precision "offset" for the currently displayed result. May be
         * called from non-UI thread.
         *
         * We initialize our `var msdLocal` to [msd] and our `var lastDigitOffsetLocal` to
         * [lastDigitOffset]. We initialize our `val lineLength` to the maximum number of characters
         * that will fit in the result display returned by the `maxCharsGet` method  of [cm]. We
         * initialize our `val wholeSize` to the index of the decimal point in [cache]. We initialize
         * our `val rawSepChars` to the number of additional digit widths required to add digit
         * separators to the first `wholeSize` characters of [cache]. We initialize our
         * `val rawSepCharsNoDecimal` to `rawSepChars` minus the extra width credit for absence of
         * ellipsis, and our `val rawSepCharsWithDecimal` to `rawSepCharsNoDecimal` minus the extra
         * width credit for presence of a decimal point. We initialize our `val sepCharsNoDecimal`
         * to the ceiling of the maximum of `rawSepCharsNoDecimal` and 0, and `val sepCharsWithDecimal`
         * to the ceiling of the maximum of `rawSepCharsWithDecimal` and 0. We initialize our
         * `val negative` to 1 if the zeroth character of [cache] is a '-' or to 0 if it is not. If
         * `lastDigitOffsetLocal` is equal to 0 (result is an integer) we set it to -1 so as to not
         * display the decimal point for integers. If `lastDigitOffsetLocal` is not [Integer.MAX_VALUE]
         * then we check if `wholeSize` is less then `lineLength` minus `sepCharsNoDecimal` and
         * `lastDigitOffsetLocal` is less than or equal to 0, in which case we have an exact integer
         * so we return minus 1 because we prefer to display it as an integer, without decimal point.
         * Otherwise we initialize our `val fitsInDisplay` to *true* if `wholeSize` plus
         * `lastDigitOffsetLocal` plus 1 is less than or equal to `lineLength` minus `sepCharsWithDecimal`.
         * Then if `lastDigitOffsetLocal` is greater than or equal to 0 and `fitsInDisplay` is *true*
         * we return `lastDigitOffsetLocal` in order to display the full exact number without scientific
         * notation.
         *
         * Otherwise we check if `msdLocal` is greater than `wholeSize` and `msdLocal` is less then
         * or equal to `wholeSize` plus EXP_COST plus 1, and if it is we can display the number
         * without scientific notation treating leading zero as msd so we set `msdLocal` to `wholeSize`
         * minus 1. Now we check if `msdLocal` is greater than QUICK_MAX_RESULT_BITS in which case
         * we return `lineLength` minus 2 in order to display a probable but uncertain 0 as "0.000000000",
         * without exponent.
         *
         * At this point we need to return a position corresponding to having msd at left, effectively
         * presuming scientific notation that preserves the left part of the result. So we initialize
         * our `var result` to `msdLocal` minus `wholeSize` plus `lineLength` minus `negative` minus
         * 1. Then if `wholeSize` is less than or equal to `lineLength` minus `sepCharsNoDecimal` we
         * subtract `sepCharsNoDecimal` from `result` if `wholeSize` is less than `lineLength` minus
         * `sepCharsWithDecimal` or we subtract `sepCharsNoDecimal` if it is not. Finally we return
         * `result` to the caller.
         *
         * @param cache Current approximation as string.
         * @param msd Position of most significant digit in result. Index of digit in [cache].
         * Can be INVALID_MSD if we haven't found it yet.
         * @param lastDigitOffset Position of least significant digit (1 = tenths digit)
         * or Integer.MAX_VALUE.
         * @param cm the [CharMetricsInfo] to use to query for information based on character widths.
         * @return precision that the result in [cache] should be displayed at.
         */
        private fun preferredPrecGet(
                cache: String,
                msd: Int,
                lastDigitOffset: Int,
                cm: CharMetricsInfo
        ): Int {
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
                val fitsInDisplay = wholeSize + lastDigitOffsetLocal + 1 <= lineLength - sepCharsWithDecimal
                if (lastDigitOffsetLocal >= 0 && fitsInDisplay) {
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

        /**
         * Target length of the short representation generated by our [shortStringGet] method.
         */
        private const val SHORT_TARGET_LENGTH = 8
        /**
         * String to be returned by our [shortStringGet] method if value "might" be 0.00000...
         */
        private const val SHORT_UNCERTAIN_ZERO = "0.00000" + KeyMaps.ELLIPSIS

        /**
         * Get a short representation of the value represented by the string [cache]. We try to match
         * the [CalculatorResult] code when the result is finite and small enough to suit our needs.
         * The result is not internationalized. We initialize our `var msdIndexLocal` to our parameter
         * [msdIndex] and our `var lsdOffsetLocal` to our parameter [lsdOffset]. We initialize our
         * `val dotIndex` to the character index of the decimal point in [cache], initialize our
         * `val negative` to 1 if the zeroth character in [cache] is '-' or to 0 if it is not and
         * initialize our `val negativeSign` to the string "-" if `negative` is 1 or to the empty
         * string if it is not. Then if `msdIndexLocal` is greater than or equal to the length of
         * [cache] minus SHORT_TARGET_LENGTH we set `msdIndexLocal` to INVALID_MSD to avoid running
         * off the end of [cache]. Then is `msdIndexLocal` is equal to INVALID_MSD we return the
         * string "0" if `lsdOffsetLocal` is less than INIT_PREC and our constant SHORT_UNCERTAIN_ZERO
         * if it is not. Otherwise we check if the whole number will fit in the allotted space and
         * if so we avoid scientific notation by setting `lsdOffsetLocal` to minus 1. If `msdIndexLocal`
         * is greater than `dotIndex` we branch on whether `msdIndexLocal` is less than or equal to
         * `dotIndex` plus EXP_COST plus 1:
         * - `msdIndexLocal` is less than or equal: The preferred display format in this case is with
         * leading zeroes, even if it doesn't fit entirely so we set `msdIndexLocal` to `dotIndex`
         * minus 1.
         * - Otherwise if `lsdOffsetLocal` is less than or equal to SHORT_TARGET_LENGTH minus `negative`
         * minus 2 the fraction that fits entirely in allotted space so we do not need scientific
         * notation and we also set `msdIndexLocal` to `dotIndex` minus 1.
         *
         * We now initialize our `var exponent` to `dotIndex` minus `msdIndexLocal`, and if `exponent`
         * is greater than 0 we subtract one from it to adjust for the fact that the decimal point
         * itself takes space.
         *
         * If `lsdOffsetLocal` is not equal to [Integer.MAX_VALUE] we initialize our `val lsdIndex`
         * to `dotIndex` plus `lsdOffsetLocal`, and initialize our `val totalDigits` to `lsdIndex`
         * minus `msdIndexLocal` plus `negative` plus 1. If `totalDigits` is less than SHORT_TARGET_LENGTH
         * and `dotIndex` is greater than `msdIndexLocal` and `lsdOffsetLocal` is greater than or equal
         * to minus 1 the number fits so no exponent is needed and we initialize our `val wholeWithCommas`
         * to the comma separated string that our [StringUtils.addCommas] method creates from [cache]
         * then return the string formed by concatenating `negativeSign` followed by `wholeWithCommas`
         * followed by the substring of [cache] from `dotIndex` to `lsdIndex` plus 1.
         *
         * Otherwise `lsdOffsetLocal` is equal to [Integer.MAX_VALUE] so we need to abbreviate:
         * If `dotIndex` is greater than `msdIndexLocal` and `dotIndex` is less than `msdIndexLocal`
         * plus SHORT_TARGET_LENGTH minus `negative` minus 1 we initialize our `val wholeWithCommas`
         * to the comma separated string that our [StringUtils.addCommas] method creates from [cache]
         * then return the string formed by concatenating `negativeSign` followed by `wholeWithCommas`
         * followed by the substring of [cache] from `dotIndex` to `msdIndexLocal` plus SHORT_TARGET_LENGTH
         * minus `negative` minus 1, followed by the [KeyMaps.ELLIPSIS] character (...).
         *
         * If we reach this point we need an abbreviation plus an exponent: So we return the string
         * formed by concatenating `negativeSign` followed by the `msdIndexLocal` character in [cache],
         * followed by the "." decimal point, followed by the substring of [cache] from `msdIndexLocal`
         * plus 1 to `msdIndexLocal` plus SHORT_TARGET_LENGTH minus `negative` minus 4 followed by
         * the [KeyMaps.ELLIPSIS] character (...), followed by the "E" string, followed by the string
         * value of `exponent`.
         *
         * @param cache String approximation of value. Assumed to be long enough that if it doesn't
         * contain enough significant digits, we can reasonably abbreviate as SHORT_UNCERTAIN_ZERO.
         * @param msdIndex Index of most significant digit in cache, or INVALID_MSD.
         * @param lsdOffset Position of least significant digit in finite representation, relative
         * to decimal point, or MAX_VALUE.
         * @return a shorter representation of the value in [cache] (less than or equal to our
         * SHORT_TARGET_LENGTH (8) characters) suitable for display in an abbreviated expression.
         */
        private fun shortStringGet(
                cache: String,
                msdIndex: Int,
                lsdOffset: Int
        ): String {
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
                    if (lsdOffsetLocal <= SHORT_TARGET_LENGTH - negative - 2
                            && lsdOffsetLocal <= CalculatorResult.MAX_LEADING_ZEROES + 1) {
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
         * Return the most significant digit index in the given numeric string. Return INVALID_MSD
         * if there are not enough digits to prove the numeric value is different from zero. As
         * usual, we assume an error of strictly less than 1 ulp. We initialize our `val len` to the
         * length of [s], and our `var nonzeroIndex` to minus 1. We then loop over `i` from 0 until
         * `len` initializing our `val c` to the `i`th character in [s], and if `c` is not '-' and
         * not '.' and not '0' we set `nonzeroIndex` to `i` and break out of the loop. Then if
         * `nonzeroIndex` is greater than or equal to 0 and `nonzeroIndex` is less than `len` minus
         * 1 or the `nonzeroIndex` character in [s] is not equal to '1' we return `nonzeroIndex` to
         * the caller, otherwise we return INVALID_MSD.
         *
         * @param s The [String] we are searching for the most significant digit in.
         * @return the index of the first non-zero digit in [s].
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

        /**
         * Refuse to scroll past the point at which this many digits from the whole number
         * part of the result are still displayed. Avoids silly displays like 1E1.
         */
        private const val MIN_DISPLAYED_DIGS = 5

        /**
         * Maximum number of characters in a scientific notation exponent.
         */
        private const val MAX_EXP_CHARS = 8

        /**
         * Return the index of the character after the exponent starting at the [offset] character
         * in [s]. Return [offset] if there is no exponent at that position.
         * Exponents have syntax E[-]digit* . "E2" and "E-2" are valid. "E+2" and "e2" are not.
         * We allow any Unicode digits, and either of the commonly used minus characters.
         *
         * We initialize `var i` to our parameter [offset], and our `val len` to the length of [s].
         * If `i` is greater than or equal to `len` minus 1, or the `i`'th character of [s] is not
         * 'E' we return [offset]. Otherwise we increment `i`, and if the resource ID of the `i`'th
         * character of [s] is the minus sign (R.id.op_sub) we increment it again. If `i` is equal
         * to `len` or the `i`'th character of [s] is not a digit we return [offset]. Otherwise we
         * increment `i` then loop while `i` is less than `len` and the i`'th character of [s] is a
         * digit character incrementing `i`, and if `i` is greater than [offset] plus MAX_EXP_CHARS
         * we return [offset]. When we finally locate a non-digit in [s] we return `i` to the caller.
         *
         * @param s Numeric [String] we are to search for the character after the exponent.
         * @param offset Index into [s] to start looking from.
         * @return Index of the character after the exponent to start looking.
         */
        fun exponentEnd(
                s: String,
                offset: Int
        ): Int {
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
