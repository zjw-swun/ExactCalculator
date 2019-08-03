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

class HistoryItem {

    private var mEvaluatorIndex: Long = 0
    /** Date in millis  */
    private var mTimeInMillis: Long = 0
    private lateinit var mFormula: Spannable

    /** This is true only for the "empty history" view.  */
    val isEmptyView: Boolean

    constructor(evaluatorIndex: Long, millis: Long, formula: Spannable) {
        mEvaluatorIndex = evaluatorIndex
        mTimeInMillis = millis
        mFormula = formula
        isEmptyView = false
    }

    fun evaluatorIndexGet(): Long {
        return mEvaluatorIndex
    }

    constructor() {
        isEmptyView = true
    }

    /**
     * @return String in format "n days ago"
     * For n > 7, the date is returned.
     */
    fun dateStringGet(): CharSequence {
        return DateUtils.getRelativeTimeSpanString(mTimeInMillis, System.currentTimeMillis(),
                DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
    }

    fun timeInMillisGet(): Long {
        return mTimeInMillis
    }

    fun formulaGet(): Spannable {
        return mFormula
    }
}
