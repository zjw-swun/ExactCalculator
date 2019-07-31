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
class HistoryAdapter(dataSet: ArrayList<HistoryItem?>)
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
    private var mDataSet: MutableList<HistoryItem?>? = null

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
     * `setHasStableIds` with *true* to notify our super (`RecyclerView.Adapter`) that each item in
     * the data set can be represented with a unique identifier.
     */
    init {
        mDataSet = dataSet
        setHasStableIds(true)
    }

    /**
     * Called when RecyclerView needs a new [ViewHolder] of the given type to represent an item. If
     * our parameter [viewType] is HISTORY_VIEW_TYPE we initialize our `val v` with a [View] which
     * is inflated from the layout file R.layout.history_item, otherwise we initialize it with a
     * [View] inflated from the layout file R.layout.empty_history_view. Then we return a new instance
     * of [ViewHolder] constructed from `v` and [viewType].
     *
     * @param parent The [ViewGroup] into which the new View will be added after it is bound to
     * an adapter position.
     * @param viewType The view type of the new View.
     * @return A new [ViewHolder] that holds a View of the given view type.
     */
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

    /**
     * Called by [RecyclerView] to display the data at the specified position. This method should
     * update the contents of the [ViewHolder] to reflect the item at the given [position]. We
     * initialize our `val item` with the [HistoryItem] at position [position] in our dataset that
     * is returned by our [getItem] method. If the `isEmptyView` method of `item` returns *true*
     * (its `mIsEmpty` field is *true* indicating the [HistoryItem] is the "empty history" view) we
     * return having done nothing. Otherwise we set the text of the `formula` field of [holder] to
     * the formula `Spannable` returned by the `formulaGet` method of `item` (which is the contents
     * of its `mFormula` field). We then call the `setEvaluator` method of the `result` field of
     * `item` to set its [Evaluator] to our [mEvaluator] field and the index of the expression it is
     * showing to the index returned by the `evaluatorIndexGet` method of `item`. If the index it is
     * showing is HISTORY_MAIN_INDEX we set the text of the `date` field of [holder] to the string
     * "Current Expression", and the visibility of the `result` field of [holder] to GONE is our
     * [mIsOneLine] is *true* or to VISIBLE if it is not. If the index it is showing is not
     * HISTORY_MAIN_INDEX we need to consider the proper text for the `date` field of [holder],
     * so if our [shouldShowHeader] method returns *true* for [position] and `item` indicating that
     * we should show a date header for the `item` we set the text of the the `date` field of [holder]
     * to the date string returned by the `dateStringGet` method of `item`, and set the visibility
     * of the divider above the `item` to GONE for the first [position], or to VISIBLE for all the
     * others. If our [shouldShowHeader] method returns *false* for [position] and `item` we need
     * not display the date (it is the same as the previous) so we set the visibility of the `date`
     * field of [holder] to GONE and the `divider` field visibility to INVISIBLE.
     *
     * @param holder The [ViewHolder] which should be updated to represent the contents of the
     * item at the given [position] in the data set.
     * @param position The position of the item within the adapter's data set.
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        if (item.isEmptyView) {
            return
        }

        holder.formula!!.text = item.formulaGet()
        // Note: HistoryItems that are not the current expression will always have interesting ops.
        holder.result!!.setEvaluator(mEvaluator!!, item.evaluatorIndexGet())
        if (item.evaluatorIndexGet() == Evaluator.HISTORY_MAIN_INDEX) {
            holder.date!!.setText(R.string.title_current_expression)
            holder.result.visibility = if (mIsOneLine) View.GONE else View.VISIBLE
        } else {
            // If the previous item occurred on the same date, the current item does not need
            // a date header.
            if (shouldShowHeader(position, item)) {
                holder.date!!.text = item.dateStringGet()
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

    /**
     * Called when a view created by this adapter has been recycled. A view is recycled when a
     * `LayoutManager` decides that it no longer needs to be attached to its parent [RecyclerView].
     * This can be because it has fallen out of visibility or a set of cached views represented by
     * views still attached to the parent [RecyclerView]. If an item view has large or expensive
     * data bound to it such as large bitmaps, this may be a good place to release those
     * resources.
     *
     * [RecyclerView] calls this method right before clearing [ViewHolder]'s internal data and
     * sending it to [RecyclerView.RecycledViewPool]. This way, if [ViewHolder] was holding valid
     * information before being recycled, you can call `ViewHolder.getAdapterPosition()` to get
     * its adapter position.
     *
     * If the item view type of [holder] is EMPTY_VIEW_TYPE we just return having done nothing.
     * Otherwise we call the `cancel` method of our [mEvaluator] field with the item ID of [holder]
     * to have it quietly cancel any current background task associated with the item. We then set
     * the visibility of the `date` and `divider` fields of [holder] to VISIBLE, and set the text
     * of the `date`, `formula` and `result` fields to *null*. Finally we call our super's
     * implementation of `onViewRecycled`.
     *
     * @param holder The [ViewHolder] for the view being recycled
     */
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

    /**
     * Return the stable ID for the item at [position]. We retrieve the [HistoryItem] at position
     * [position] in our dataset, then return the index value for the expression that is returned
     * by the `evaluatorIndexGet` method of the [HistoryItem].
     *
     * @param position Adapter position to query
     * @return the stable ID of the item at position
     */
    override fun getItemId(position: Int): Long {
        return getItem(position).evaluatorIndexGet()
    }

    /**
     * Return the view type of the item at [position] for the purposes of view recycling. If the
     * `isEmptyView` method of the [HistoryItem] at position [position] in our dataset returns
     * *true* we return EMPTY_VIEW_TYPE, otherwise we return HISTORY_VIEW_TYPE.
     *
     * @param position position to query
     * @return integer value identifying the type of the view needed to represent the item at
     * [position]. Either EMPTY_VIEW_TYPE, or HISTORY_VIEW_TYPE in our case.
     */
    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isEmptyView) EMPTY_VIEW_TYPE else HISTORY_VIEW_TYPE
    }

    /**
     * Returns the total number of items in the data set held by the adapter. We just return the
     * size of our [mDataSet] dataset field.
     *
     * @return The total number of items in this adapter.
     */
    override fun getItemCount(): Int {
        return mDataSet!!.size
    }

    /**
     * This is called from the `onActivityCreated` override of the [HistoryFragment] in order to set
     * our dataset field [mDataSet] to the [ArrayList] of *null* [HistoryItem]'s that it creates.
     * (Note that our `getItem` method lazy-fills the [HistoryItem]'s when they are needed).
     *
     * @param dataSet the [ArrayList] of [HistoryItem]'s we should use as our dataset.
     */
    fun setDataSet(dataSet: ArrayList<HistoryItem?>) {
        mDataSet = dataSet
    }

    /**
     * Setter for our [mIsResultLayout] field.
     *
     * @param isResult [Boolean] value to set our [mIsResultLayout] field to.
     */
    fun setIsResultLayout(isResult: Boolean) {
        mIsResultLayout = isResult
    }

    /**
     * Setter for our [mIsOneLine] field.
     *
     * @param isOneLine [Boolean] value to set our [mIsOneLine] field to.
     */
    fun setIsOneLine(isOneLine: Boolean) {
        mIsOneLine = isOneLine
    }

    /**
     * Setter for our [mIsDisplayEmpty] field.
     *
     * @param isDisplayEmpty [Boolean] value to set our [mIsDisplayEmpty] field to.
     */
    fun setIsDisplayEmpty(isDisplayEmpty: Boolean) {
        mIsDisplayEmpty = isDisplayEmpty
    }

    /**
     * Setter for our [mEvaluator] field.
     *
     * @param evaluator [Evaluator] instance to set our [mEvaluator] field to.
     */
    fun setEvaluator(evaluator: Evaluator) {
        mEvaluator = evaluator
    }

    /**
     * Retrieves the expression index of the expression at position [position] in our dataset. If
     * the calculator display is empty ([mIsDisplayEmpty] is *true*) or it is displaying a result
     * ([mIsResultLayout] is *true*) we just return the maximum index value returned by the
     * `maxIndexGet` method of our [mEvaluator] field minus [position], otherwise we need to account
     * for the additional "Current Expression" so we return 1 more than this value.
     *
     * @param position the position of the item in our dataset whose expression index is needed.
     * @return the expression index of the expression at position [position] in our dataset.
     */
    private fun getEvaluatorIndex(position: Int): Int {
        return if (mIsDisplayEmpty || mIsResultLayout) {
            (mEvaluator!!.maxIndexGet() - position).toInt()
        } else {
            // Account for the additional "Current Expression" with the +1.
            (mEvaluator!!.maxIndexGet() - position + 1).toInt()
        }
    }

    /**
     * Determines whether a date header should be shown above the [HistoryItem] found at position
     * [position] in our dataset. If [position] is one less than our item count it is the first
     * (oldest) element in our dataset and should always show the header so we return *true*.
     * Otherwise we initialize our `val prevItem` with the previous [HistoryItem] (at position
     * [position] plus 1). We then set the time in milliseconds of our [mCalendar] field to the
     * timestamp of [item] and initialize our `val year` with the YEAR of [mCalendar], and our
     * `val day` with the DAY_OF_YEAR of [mCalendar]. We then set the time in milliseconds of our
     * [mCalendar] field to the timestamp of `prevItem` and initialize our `val prevYear` with the
     * YEAR of [mCalendar], and our `val prevDay` with the DAY_OF_YEAR of [mCalendar]. We then
     * return *true* if `year` is not equal to `prevYear` or `day` is not equal to `prevDay`.
     *
     * @param position the position of the item in our dataset we are interested in.
     * @param item the [HistoryItem] at position [position] in our dataset.
     * @return *true* if a date header should be shown above the [item].
     */
    private fun shouldShowHeader(position: Int, item: HistoryItem): Boolean {
        if (position == itemCount - 1) {
            // First/oldest element should always show the header.
            return true
        }
        val prevItem = getItem(position + 1)
        // We need to use Calendars to determine this because of Daylight Savings.
        mCalendar.timeInMillis = item.timeInMillisGet()
        val year = mCalendar.get(Calendar.YEAR)
        val day = mCalendar.get(Calendar.DAY_OF_YEAR)
        mCalendar.timeInMillis = prevItem.timeInMillisGet()
        val prevYear = mCalendar.get(Calendar.YEAR)
        val prevDay = mCalendar.get(Calendar.DAY_OF_YEAR)
        return year != prevYear || day != prevDay
    }

    /**
     * Gets the [HistoryItem] from [mDataSet], lazy-filling the dataSet if necessary. We initalize
     * our `var item` with the [HistoryItem] at position [position] in our [MutableList] dataset
     * field [mDataSet]. If `item` is *null*, we initialize our `val evaluatorIndex` with the
     * expression index of the expression for [position], and then set `item` to a new instance of
     * [HistoryItem] constructed from `evaluatorIndex`, the timestamp of the expression and the
     * `Spannable` representation of the expression at `evaluatorIndex`, and the set the [position]
     * entry of [mDataSet] to `item`.
     *
     * @param position the position in our dataset that we are interested in.
     * @return the [HistoryItem] at position [position] in our dataset.
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

    /**
     * Our custom [RecyclerView.ViewHolder].
     *
     * @param v the [View] we are displayed in.
     * @param viewType the type of [View] we hold (either EMPTY_VIEW_TYPE, or HISTORY_VIEW_TYPE).
     */
    class ViewHolder(v: View, viewType: Int) : RecyclerView.ViewHolder(v) {

        /**
         * The [TextView] displaying our date header (if any).
         */
        val date: TextView?
        /**
         * The [AlignedTextView] displaying our formula.
         */
        val formula: AlignedTextView?
        /**
         * The [CalculatorResult] displaying our result.
         */
        val result: CalculatorResult?
        /**
         * The divider [View].
         */
        val divider: View?

        /**
         * Our *init* block. If our `viewType` is EMPTY_VIEW_TYPE we just set all our fields to
         * *null*, otherwise we set our `date` field by finding the `View` with id R.id.history_date,
         * our `formula` field by finding the `View` with id R.id.history_formula, our `result`
         * field by finding the `View` with id R.id.history_result, and our `divider` field by
         * finding the `View` with id R.id.history_divider.
         */
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

    /**
     * Our static constants.
     */
    companion object {
        /**
         * TAG useful for logging.
         */
        @Suppress("unused")
        private const val TAG = "HistoryAdapter"
        /**
         * View type used when history is empty.
         */
        private const val EMPTY_VIEW_TYPE = 0
        /**
         * View type which holds a normal [HistoryItem]
         */
        const val HISTORY_VIEW_TYPE = 1
    }
}
