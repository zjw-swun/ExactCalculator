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

import android.animation.Animator
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toolbar
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING
import java.util.*

/**
 * This Fragment displays the History database entries in its `RecyclerView`.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
class HistoryFragment : Fragment(), DragLayout.DragCallback {

    /**
     * The [DragController] which animates our dragging down over the calculator display.
     */
    private val mDragController = DragController()

    /**
     * The [RecyclerView] in our layout file (R.layout.fragment_history) with the resource ID
     * R.id.history_recycler_view which displays the History database entries.
     */
    private var mRecyclerView: RecyclerView? = null
    /**
     * The custom [RecyclerView.Adapter] which fills our [RecyclerView] field [mRecyclerView] with
     * [HistoryItem] entries from its dataset when requested to do so.
     */
    private var mAdapter: HistoryAdapter? = null
    /**
     * The [DragLayout] in the main layout file layout/activity_calculator_main.xml which holds both
     * the calculator display layout file activity_calculator and the `FrameLayout` we display in.
     */
    private var mDragLayout: DragLayout? = null

    /**
     * Our handle to our app's singleton [Evaluator] instance (manages the background evaluation of
     * all expressions).
     */
    private var mEvaluator: Evaluator? = null

    /**
     * Our dataset, which is lazy-filled by [mAdapter] from our history database.
     */
    private var mDataSet = ArrayList<HistoryItem?>()

    /**
     * This is set to *true* when the calculator display is cleared
     */
    private var mIsDisplayEmpty: Boolean = false

    /**
     * Called to do initial creation of a fragment. This is called after [onAttach] and before
     * [onCreateView]. First we call our super's implementation of `onCreate`, then we initialize
     * our field [mAdapter] with a new instance of [HistoryAdapter] constructed to use [mDataSet]
     * as its dataset.
     *
     * @param savedInstanceState If the fragment is being re-created from a previous saved state,
     * this is the state.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mAdapter = HistoryAdapter(mDataSet)
    }

    /**
     * Called to have the fragment instantiate its user interface view. This will be called between
     * [onCreate] and [onActivityCreated]. We initialize our `val view` by using our parameter
     * [inflater] to inflate our layout file R.layout.fragment_history into it using our parameter
     * [container] for its `LayoutParams` without attaching to it. We initialize our field
     * [mDragLayout] by retrieving the root view of [container] and finding the [DragLayout] with
     * the ID R.id.drag_layout, then add *this* as a `DragCallback` callback to it. We initialize
     * our field [mRecyclerView] by finding the [RecyclerView] in `view` with the resource ID
     * R.id.history_recycler_view, then add an anonymous [RecyclerView.OnScrollListener] to it
     * whose `onScrollStateChanged` override calls our [stopActionModeOrContextMenu] method if the
     * [RecyclerView] is currently being dragged (this will close the action mode or context menu
     * if they are currently open). We then call the `setHasFixedSize` method of [mRecyclerView] to
     * inform [RecyclerView] that the size of the [RecyclerView] is not affected by the adapter's
     * contents (this allows it to perform several optimizations). We then set the adapter of
     * [mRecyclerView] to our field [mAdapter].
     *
     * We then initialize our `val toolbar` by finding the [Toolbar] in `view` with the resource id
     * R.id.history_toolbar. We inflate the menu resource with the ID R.menu.fragment_history into
     * `toolbar` and set its [Toolbar.OnMenuItemClickListener] to a lambda which overrides its
     * `onMenuItemClick(MenuItem item)` method in order to launch an [AlertDialogFragment] that
     * allows the user to clear the history and memory if the item ID clicked is the one with the
     * ID R.id.menu_clear_history ("Clear"). Finally we set the navigation on click listener of
     * `toolbar` to a lambda which calls the `onBackPressed` method of the `FragmentActivity` this
     * fragment is currently associated with to have it "navigate back" to the calculator display by
     * finishing this [Fragment] when the back button is pressed. We then return `view` to the
     * caller.
     *
     * @param inflater The LayoutInflater object that can be used to inflate any views.
     * @param container Parent view that our fragment's UI will be attached to.
     * @param savedInstanceState If non-null, this fragment is being re-constructed
     * from a previous saved state as given here.
     * @return The [View] for our fragment's UI.
     */
    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_history, container, false)

        mDragLayout = container!!.rootView.findViewById(R.id.drag_layout)
        mDragLayout!!.addDragCallback(this)

        mRecyclerView = view.findViewById(R.id.history_recycler_view)

        mRecyclerView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            /**
             * Callback method to be invoked when RecyclerView's scroll state changes. If our
             * parameter [newState] is SCROLL_STATE_DRAGGING (the [RecyclerView] is currently
             * being dragged by outside input such as user touch input) we call our method
             * [stopActionModeOrContextMenu] to close the action mode or context menu if they
             * are currently open. In any case we then call our super's implementation of
             * `onScrollStateChanged`.
             *
             * @param recyclerView The RecyclerView whose scroll state has changed.
             * @param newState     The updated scroll state. One of {@link #SCROLL_STATE_IDLE},
             *                     {@link #SCROLL_STATE_DRAGGING} or {@link #SCROLL_STATE_SETTLING}.
             */
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_DRAGGING) {
                    stopActionModeOrContextMenu()
                }
                super.onScrollStateChanged(recyclerView, newState)
            }
        })

        // The size of the RecyclerView is not affected by the adapter's contents.
        mRecyclerView!!.setHasFixedSize(true)
        mRecyclerView!!.adapter = mAdapter

        val toolbar = view.findViewById<Toolbar>(R.id.history_toolbar)
        toolbar.inflateMenu(R.menu.fragment_history)
        toolbar.setOnMenuItemClickListener(Toolbar.OnMenuItemClickListener { item ->
            if (item.itemId == R.id.menu_clear_history) {
                val calculator = activity as Calculator2?
                AlertDialogFragment.showMessageDialog(calculator!!, "" /* title */,
                        getString(R.string.dialog_clear),
                        getString(R.string.menu_clear_history),
                        CLEAR_DIALOG_TAG)
                return@OnMenuItemClickListener true
            }
            onOptionsItemSelected(item)
        })
        toolbar.setNavigationOnClickListener { activity!!.onBackPressed() }
        return view
    }

    /**
     * Called when the fragment's activity has been created and this fragment's view hierarchy
     * instantiated. This is called after [onCreateView] and before [onViewStateRestored]. First
     * we call our super's implementation of `onActivityCreated`. We then initialize our
     * `val activity` with the `FragmentActivity` this fragment is currently associated with
     * (casting it to [Calculator2]). We initialize our field [mEvaluator] to our singleton instance
     * of [Evaluator] and set the [Evaluator] of our field [mAdapter] to it. We initialize our
     * `val isResultLayout` to the `isResultLayout` property of `activity` and our `val isOneLine`
     * to its `isOneLine` property. We initialize our `val mainExpr` with the [CalculatorExpr] for
     * the MAIN_INDEX expression returned by the `exprGet` method of [mEvaluator]. We then initialize
     * our field [mIsDisplayEmpty] field to *true* if `mainExpr` is *null* or it is empty (cleared).
     * We then call our method [initializeController] to have it initialize the animations performed
     * by the [DragController] of our field [mDragController]. We initialize our `val maxIndex` with
     * the maximum index value returned by the `maxIndexGet` method of [mEvaluator] (this is the
     * number of history expressions in the expression database that have positive indices). We
     * initialize our `val newDataSet` with a new instance of [ArrayList] that will hold the
     * [HistoryItem] objects that will be displayed in our [RecyclerView]. If [mIsDisplayEmpty] is
     * *false* and `isResultLayout` is *false* we want to add the current expression as the first
     * element in the list `newDataSet` so we call the `copyMainToHistory` method of [mEvaluator] to
     * have it discard the previous expression in HISTORY_MAIN_INDEX and replace it by a fresh copy
     * of the main expression, we then add to `newDataSet` a new instance of [HistoryItem] constructed
     * to hold the HISTORY_MAIN_INDEX expression, with a timestamp of the current time in milliseconds,
     * and the string representation of the value of the expression at index 0 (which is the main
     * expression displayed in the calculator display that was just cached of course). When done
     * dealing with the possible value in the calculator display we then loop over `i` from 0 *until*
     * `maxIndex` adding *null* entries to `newDataSet` (they will be lazy filled with entries from
     * the database by the [RecyclerView]'s adapter). We initialize our `val isEmpty` to *true* if
     * `newDataSet` is empty (both the display and history are cleared at the moment). We then set
     * the background color of [mRecyclerView] to R.color.empty_history_color (a shade of gray) if
     * `isEmpty` is *true* or to R.color.display_background_color (a dark blue) if it is *false*.
     * If `isEmpty` is *true* we add an empty [HistoryItem] to `newDataSet`. We then set our field
     * [mDataSet] to `newDataSet`, set the dataset of our field [mAdapter] to [mDataSet] then set
     * the "is result layout property" of [mAdapter] to `isResultLayout`, set its "is one line"
     * property to the `isOneLine` property of `activity`, set its "is display empty" property to
     * our field [mIsDisplayEmpty], and finally call the `notifyDataSetChanged` method of [mAdapter]
     * to notify it that its dataset has changed and it needs to redisplay its contents.
     *
     * @param savedInstanceState If the fragment is being re-created from a previous saved state,
     * this is the state.
     */
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val activity = (activity as Calculator2?)!!
        mEvaluator = Evaluator.instanceGet(activity)
        mAdapter!!.setEvaluator(mEvaluator!!)

        val isResultLayout = activity.isResultLayout
        val isOneLine = activity.isOneLine

        // Snapshot display state here. For the rest of the lifecycle of this current
        // HistoryFragment, this is what we will consider the display state.
        // In rare cases, the display state can change after our adapter is initialized.
        val mainExpr = mEvaluator!!.exprGet(Evaluator.MAIN_INDEX)

        @Suppress("SENSELESS_COMPARISON")
        mIsDisplayEmpty = mainExpr == null || mainExpr.isEmpty

        initializeController(isResultLayout, isOneLine, mIsDisplayEmpty)

        val maxIndex = mEvaluator!!.maxIndexGet()

        val newDataSet = ArrayList<HistoryItem?>()

        if (!mIsDisplayEmpty && !isResultLayout) {
            // Add the current expression as the first element in the list (the layout is
            // reversed and we want the current expression to be the last one in the
            // RecyclerView).
            // If we are in the result state, the result will animate to the last history
            // element in the list and there will be no "Current Expression."
            mEvaluator!!.copyMainToHistory()
            newDataSet.add(HistoryItem(Evaluator.HISTORY_MAIN_INDEX,
                    System.currentTimeMillis(), mEvaluator!!.exprAsSpannableGet(0)))
        }
        for (i in 0 until maxIndex) {
            newDataSet.add(null)
        }
        val isEmpty = newDataSet.isEmpty()
        mRecyclerView!!.setBackgroundColor(ContextCompat.getColor(activity,
                if (isEmpty) R.color.empty_history_color else R.color.display_background_color))
        if (isEmpty) {
            newDataSet.add(HistoryItem())
        }
        mDataSet = newDataSet
        mAdapter!!.setDataSet(mDataSet)
        mAdapter!!.setIsResultLayout(isResultLayout)
        mAdapter!!.setIsOneLine(activity.isOneLine)
        mAdapter!!.setIsDisplayEmpty(mIsDisplayEmpty)
        mAdapter!!.notifyDataSetChanged()
    }

    /**
     * Called when the Fragment is visible to the user. First we call our super's implementation of
     * `onStart`, then we initialize our `val activity` to the `FragmentActivity` this fragment is
     * currently associated with (cast to [Calculator2]). Then we call the `initializeAnimation`
     * method of our field [mDragController] to have it initialize itself to the state it should be
     * in when the [HistoryFragment] is first visible to the user.
     */
    override fun onStart() {
        super.onStart()

        val activity = activity as Calculator2?

        mDragController.initializeAnimation(
                activity!!.isResultLayout,
                activity.isOneLine,
                mIsDisplayEmpty)
    }

    /**
     * Called when a fragment loads an animator. We just return the [Animator] created by the
     * `createAnimator` method of our field [mDragLayout].
     *
     * @param transit The value set in `FragmentTransaction.setTransition(int)` or 0 if not set.
     * @param enter *true* when the fragment is added/attached/shown or *false* when
     *              the fragment is removed/detached/hidden.
     * @param nextAnim The resource set in `FragmentTransaction.setCustomAnimations` or
     *                 0 if it was not called. The value will depend on the current operation.
     * @return an [Animator] with an `AnimatorListenerAdapter` attached to it.
     */
    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): Animator? {
        return mDragLayout!!.createAnimator(enter)
    }

    /**
     * Called when the fragment is no longer in use. This is called after [onStop] and
     * before [onDetach]. First we call our super's implementation of `onDestroy`. If
     * our field [mDragLayout] is not *null* we call its `removeDragCallback` method to
     * remove *this* as a callback for it. If our field [mEvaluator] is not *null* we call
     * its `cancelNonMain` method to have it quietly cancel all evaluations associated
     * with expressions other than the main one.
     */
    override fun onDestroy() {
        super.onDestroy()

        if (mDragLayout != null) {
            mDragLayout!!.removeDragCallback(this)
        }

        if (mEvaluator != null) {
            // Note that the view is destroyed when the fragment backstack is popped, so
            // these are essentially called when the DragLayout is closed.
            mEvaluator!!.cancelNonMain()
        }
    }

    /**
     * Called to initialize the [DragController] we use (our [mDragController] field) when the
     * fragment is created. We call the `setDisplayFormula` method of [mDragController] to have
     * it set its formula display to the view with ID R.id.formula in our `FragmentActivity` UI,
     * call its `setDisplayResult` method to have it set its result display to the view with ID
     * R.id.result, and call its `setToolbar` method to have it set its tool bar reference to the
     * view with ID R.id.toolbar. We call its `setEvaluator` method to have it set its reference
     * to the app's singleton [Evaluator] to our field [mEvaluator], and then call its
     * `initializeController` method to have it initialize its animation.
     *
     * @param isResult *true* if the display is in the RESULT state.
     * @param isOneLine *true* if the device needs to use the one line layout.
     * @param isDisplayEmpty *true* if the calculator display is cleared (no result or formula)
     */
    private fun initializeController(
            isResult: Boolean,
            isOneLine: Boolean,
            isDisplayEmpty: Boolean
    ) {

        mDragController.setDisplayFormula(
                activity!!.findViewById<View>(R.id.formula) as CalculatorFormula)
        mDragController.setDisplayResult(
                activity!!.findViewById<View>(R.id.result) as CalculatorResult)
        mDragController.setToolbar(activity!!.findViewById(R.id.toolbar))
        mDragController.setEvaluator(mEvaluator!!)
        mDragController.initializeController(isResult, isOneLine, isDisplayEmpty)
    }

    /**
     * Stops any active `ActionMode` or `ContextMenu` in progress. If our field [mRecyclerView] is
     * *null* we just return *false* to the caller (these menus can only be open if one of the
     * views in the [RecyclerView] has been long-clicked). Otherwise we loop over `i` from 0
     * *until* the number of children of [mRecyclerView]:
     * - We initialize our `val view` with the `i`'th child of [mRecyclerView].
     * - We initialize our `val viewHolder` to the [HistoryAdapter.ViewHolder] of that `view`.
     * - If `viewHolder` is not *null*, and its `result` field is not *null* we call the
     * `stopActionModeOrContextMenu` method of the `result` field of `viewHolder` and return
     * *true* if the method returns *true*. Otherwise we just loop around for the next child
     * of [mRecyclerView].
     *
     * If we found no children of [mRecyclerView] that had a menu that needed closing we return
     * *false* to the caller.
     *
     * @return *true* if there was an open action mode or context menu that we had to close,
     * otherwise *false*
     */
    fun stopActionModeOrContextMenu(): Boolean {
        if (mRecyclerView == null) {
            return false
        }
        for (i in 0 until mRecyclerView!!.childCount) {
            val view = mRecyclerView!!.getChildAt(i)
            val viewHolder = mRecyclerView!!.getChildViewHolder(view) as HistoryAdapter.ViewHolder
            @Suppress("NullChecksToSafeCall", "SENSELESS_COMPARISON")
            if (viewHolder != null && viewHolder.result != null
                    && viewHolder.result.stopActionModeOrContextMenu()) {
                return true
            }
        }
        return false
    }

    /* Begin override DragCallback methods. */

    /**
     * Callback when a drag to open begins. We ignore.
     */
    override fun onStartDraggingOpen() {
        // no-op
    }

    /**
     * Callback which is called from the [DragLayout] `onRestoreInstanceState` override. If our
     * parameter [isOpen] is *true* we were open when we were paused, so we set the visibility
     * of our field [mRecyclerView] to VISIBLE.
     *
     * @param isOpen the value of its field [isOpen] that was stored by its `onSaveInstanceState`.
     */
    override fun onInstanceStateRestored(isOpen: Boolean) {
        if (isOpen) {
            mRecyclerView!!.visibility = View.VISIBLE
        }
    }

    /**
     * Animate the RecyclerView text. If our fragment is visible or we are currently being removed
     * from our activity, we call the `animateViews` method of our field [mDragController] to have
     * it animate our [mRecyclerView] to the state it should be in when [yFraction] of the view is
     * visible.
     *
     * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
     */
    override fun whileDragging(yFraction: Float) {
        if (isVisible || isRemoving) {
            mDragController.animateViews(yFraction, mRecyclerView!!)
        }
    }

    /**
     * Whether we should allow the view to be dragged. We call the `canScrollVertically` method of
     * our field [mRecyclerView] to see if it can be scrolled down, and return *true* if it cannot
     * be scrolled down.
     *
     * @param view the [View] that the user is attempting to capture.
     * @param x the X coordinate of the user's motion event
     * @param y the Y coordinate of the user's motion event
     * @return *true* if the dragging of the [view] should be allowed.
     */
    override fun shouldCaptureView(view: View, x: Int, y: Int): Boolean {
        return !mRecyclerView!!.canScrollVertically(1) // +1 is Scrolling down.
    }

    /**
     * Override this to return the measured height of the calculator display. We just return 0.
     *
     * @return the current height of the display.
     */
    override fun displayHeightFetch(): Int {
        return 0
    }

    /* End override DragCallback methods. */

    /**
     * Our static constants.
     */
    companion object {

        /**
         * The TAG used for our `HistoryFragment` when adding our fragment to the fragment manager.
         */
        const val TAG = "HistoryFragment"
        /**
         * The TAG used for the `AlertDialogFragment` which clears the history.
         */
        const val CLEAR_DIALOG_TAG = "clear"
    }
}
