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

import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import java.util.ArrayList
import java.util.Calendar

/**
 * Adapter for [RecyclerView] of [HistoryItem].
 */
@RequiresApi(api = Build.VERSION_CODES.N)
class HistoryAdapter(dataSet: ArrayList<HistoryItem>)
    : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    /**
     * The singleton [Evaluator] instance for our activity.
     */
    private var mEvaluator: Evaluator? = null

    /**
     * The [Calendar] we use in our [shouldShowHeader] method to compare times.
     */
    private val mCalendar = Calendar.getInstance()

    /**
     * Our dataset of [HistoryItem] entries.
     */
    private var mDataSet: MutableList<HistoryItem>? = null

    /**
     * Is the calculator display displaying a result line at the moment?
     */
    private var mIsResultLayout: Boolean = false
    /**
     * Does the calculator display use a one line layout?
     */
    private var mIsOneLine: Boolean = false
    /**
     * Is the calculator display empty?
     */
    private var mIsDisplayEmpty: Boolean = false

    /**
     * Our *init* block, we just save our parameter `dataSet` in our field `mDataSet` and call the
     * `setHasStableIds` with *true* to notify our super that each item in the data set can be
     * represented with a unique identifier.
     */
    init {
        mDataSet = dataSet
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v: View = if (viewType == HISTORY_VIEW_TYPE) {
            LayoutInflater.from(parent.context)
                    .inflate(R.layout.history_item, parent, false)
        } else {
            LayoutInflater.from(parent.context)
                    .inflate(R.layout.empty_history_view, parent, false)
        }
        return ViewHolder(v, viewType)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        if (item.isEmptyView) {
            return
        }

        holder.formula!!.text = item.formula
        // Note: HistoryItems that are not the current expression will always have interesting ops.
        holder.result!!.setEvaluator(mEvaluator!!, item.evaluatorIndex)
        if (item.evaluatorIndex == Evaluator.HISTORY_MAIN_INDEX) {
            holder.date!!.setText(R.string.title_current_expression)
            holder.result.visibility = if (mIsOneLine) View.GONE else View.VISIBLE
        } else {
            // If the previous item occurred on the same date, the current item does not need
            // a date header.
            if (shouldShowHeader(position, item)) {
                holder.date!!.text = item.dateString
                // Special case -- very first item should not have a divider above it.
                holder.divider!!.visibility = if (position == itemCount - 1)
                    View.GONE
                else
                    View.VISIBLE
            } else {
                holder.date!!.visibility = View.GONE
                holder.divider!!.visibility = View.INVISIBLE
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        if (holder.itemViewType == EMPTY_VIEW_TYPE) {
            return
        }
        mEvaluator!!.cancel(holder.itemId, true)

        holder.date!!.visibility = View.VISIBLE
        holder.divider!!.visibility = View.VISIBLE
        holder.date.text = null
        holder.formula!!.text = null
        holder.result!!.text = null

        super.onViewRecycled(holder)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).evaluatorIndex
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isEmptyView) EMPTY_VIEW_TYPE else HISTORY_VIEW_TYPE
    }

    override fun getItemCount(): Int {
        return mDataSet!!.size
    }

    fun setDataSet(dataSet: ArrayList<HistoryItem>) {
        mDataSet = dataSet
    }

    fun setIsResultLayout(isResult: Boolean) {
        mIsResultLayout = isResult
    }

    fun setIsOneLine(isOneLine: Boolean) {
        mIsOneLine = isOneLine
    }

    fun setIsDisplayEmpty(isDisplayEmpty: Boolean) {
        mIsDisplayEmpty = isDisplayEmpty
    }

    fun setEvaluator(evaluator: Evaluator) {
        mEvaluator = evaluator
    }

    private fun getEvaluatorIndex(position: Int): Int {
        return if (mIsDisplayEmpty || mIsResultLayout) {
            (mEvaluator!!.maxIndexGet() - position).toInt()
        } else {
            // Account for the additional "Current Expression" with the +1.
            (mEvaluator!!.maxIndexGet() - position + 1).toInt()
        }
    }

    private fun shouldShowHeader(position: Int, item: HistoryItem): Boolean {
        if (position == itemCount - 1) {
            // First/oldest element should always show the header.
            return true
        }
        val prevItem = getItem(position + 1)
        // We need to use Calendars to determine this because of Daylight Savings.
        mCalendar.timeInMillis = item.timeInMillis
        val year = mCalendar.get(Calendar.YEAR)
        val day = mCalendar.get(Calendar.DAY_OF_YEAR)
        mCalendar.timeInMillis = prevItem.timeInMillis
        val prevYear = mCalendar.get(Calendar.YEAR)
        val prevDay = mCalendar.get(Calendar.DAY_OF_YEAR)
        return year != prevYear || day != prevDay
    }

    /**
     * Gets the HistoryItem from mDataSet, lazy-filling the dataSet if necessary.
     */
    private fun getItem(position: Int): HistoryItem {
        var item: HistoryItem? = mDataSet!![position]
        // Lazy-fill the data set.
        if (item == null) {
            val evaluatorIndex = getEvaluatorIndex(position)
            item = HistoryItem(evaluatorIndex.toLong(),
                    mEvaluator!!.timeStampGet(evaluatorIndex.toLong()),
                    mEvaluator!!.exprAsSpannableGet(evaluatorIndex.toLong()))
            mDataSet!![position] = item
        }
        return item
    }

    class ViewHolder(v: View, viewType: Int) : RecyclerView.ViewHolder(v) {

        val date: TextView?
        val formula: AlignedTextView?
        val result: CalculatorResult?
        val divider: View?

        init {
            if (viewType == EMPTY_VIEW_TYPE) {
                date = null
                formula = null
                result = null
                divider = null
            } else {
                date = v.findViewById(R.id.history_date)
                formula = v.findViewById(R.id.history_formula)
                result = v.findViewById(R.id.history_result)
                divider = v.findViewById(R.id.history_divider)
            }
        }
    }

    companion object {

        @Suppress("unused")
        private const val TAG = "HistoryAdapter"

        private const val EMPTY_VIEW_TYPE = 0
        const val HISTORY_VIEW_TYPE = 1
    }
}
