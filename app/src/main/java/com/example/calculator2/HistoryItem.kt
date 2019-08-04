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

import android.text.Spannable
import android.text.format.DateUtils

/**
 * Holds information about an expression loaded from our history database when its `isEmptyView`
 * field is *false*
 */
class HistoryItem {

    /**
     * Index of the expression in our history database.
     */
    private var mEvaluatorIndex: Long = 0
    /**
     * Date in millis when the expression was added to the database.
     */
    private var mTimeInMillis: Long = 0
    /**
     * [Spannable] representation of the expression.
     */
    private lateinit var mFormula: Spannable

    /**
     * This is true only for the "empty history" view.
     */
    val isEmptyView: Boolean

    /**
     * The constructor used when [HistoryAdapter] lazy fills its dataset with entries read from the
     * database. We just save our parameters in their respective fields and set [isEmptyView] to
     * *false*.
     *
     * @param evaluatorIndex Index of the expression in our history database.
     * @param millis Date in millis when the expression was added to the database.
     * @param formula [Spannable] representation of the expression.
     */
    constructor(evaluatorIndex: Long, millis: Long, formula: Spannable) {
        mEvaluatorIndex = evaluatorIndex
        mTimeInMillis = millis
        mFormula = formula
        isEmptyView = false
    }

    /**
     * The constructor used when the history and calculator display have been cleared. We just set
     * our field [isEmptyView] to *true*.
     */
    constructor() {
        isEmptyView = true
    }

    /**
     * Getter for our [mEvaluatorIndex] field.
     *
     * @return the value of our [mEvaluatorIndex] field.
     */
    fun evaluatorIndexGet(): Long {
        return mEvaluatorIndex
    }

    /**
     * Formats our [mTimeInMillis] time stamp into a string in the format "n days ago".
     *
     * @return String in format "n days ago". For n > 7, the date is returned.
     */
    fun dateStringGet(): CharSequence {
        return DateUtils.getRelativeTimeSpanString(mTimeInMillis, System.currentTimeMillis(),
                DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
    }

    /**
     * Getter for our [mTimeInMillis] time stamp field..
     *
     * @return the contents of our [mTimeInMillis] time stamp field.
     */
    fun timeInMillisGet(): Long {
        return mTimeInMillis
    }

    /**
     * Getter for our field [mFormula] string representation of the expression.
     *
     * @return the contents of our field [mFormula] string representation of the expression.
     */
    fun formulaGet(): Spannable {
        return mFormula
    }
}
