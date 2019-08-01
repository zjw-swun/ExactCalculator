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
     * to its `isOneLine` property.
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

    override fun onStart() {
        super.onStart()

        val activity = activity as Calculator2?

        mDragController.initializeAnimation(activity!!.isResultLayout, activity.isOneLine,
                mIsDisplayEmpty)
    }

    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): Animator? {
        return mDragLayout!!.createAnimator(enter)
    }

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

    private fun initializeController(isResult: Boolean, isOneLine: Boolean, isDisplayEmpty: Boolean) {

        mDragController.setDisplayFormula(
                activity!!.findViewById<View>(R.id.formula) as CalculatorFormula)
        mDragController.setDisplayResult(
                activity!!.findViewById<View>(R.id.result) as CalculatorResult)
        mDragController.setToolbar(activity!!.findViewById(R.id.toolbar))
        mDragController.setEvaluator(mEvaluator!!)
        mDragController.initializeController(isResult, isOneLine, isDisplayEmpty)
    }

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

    override fun onStartDraggingOpen() {
        // no-op
    }

    override fun onInstanceStateRestored(isOpen: Boolean) {
        if (isOpen) {
            mRecyclerView!!.visibility = View.VISIBLE
        }
    }

    override fun whileDragging(yFraction: Float) {
        if (isVisible || isRemoving) {
            mDragController.animateViews(yFraction, mRecyclerView!!)
        }
    }

    override fun shouldCaptureView(view: View, x: Int, y: Int): Boolean {
        return !/* scrolling down */mRecyclerView!!.canScrollVertically(1)
    }

    override fun displayHeightFetch(): Int {
        return 0
    }

    companion object {

        const val TAG = "HistoryFragment"
        const val CLEAR_DIALOG_TAG = "clear"
    }

    /* End override DragCallback methods. */
}
