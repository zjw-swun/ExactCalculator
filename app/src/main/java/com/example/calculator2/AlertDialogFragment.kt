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

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity

/**
 * Display a message with a dismiss button, and optionally a second button.
 */
class AlertDialogFragment : DialogFragment(), DialogInterface.OnClickListener {

    /**
     * Interface which should be implemented by any code which wants to know is a button was clicked.
     */
    interface OnClickListener {
        /**
         * This method will be invoked when a button in the dialog is clicked.
         *
         * @param fragment the AlertDialogFragment that received the click
         * @param which the button that was clicked (e.g. [DialogInterface.BUTTON_POSITIVE]) or the
         * position of the item clicked
         */
        fun onClick(fragment: AlertDialogFragment, which: Int)
    }

    /**
     * Here we set the style of our *AlertDialogFragment*. The theme used by the java code used to be
     * android.R.attr.alertDialogTheme, but lint did not like that as the annotation of the function
     * specified "@StyleRes" (odd that).
     */
    init {
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Material_Dialog_Alert)
    }

    /**
     * We override this to build our own custom [Dialog] container. If there were no arguments supplied
     * when we were created (*getArguments* returns *null*) we inintialize our variable *args* to
     * Bundle.EMPTY, otherwise we initialize it to the arguments supplied when the fragment was
     * instantiated. We initialize our variable *builder* with a new instance of *AlertDialog.Builder*
     * that uses *activity* as the parent context and initialize our variable *inflater* with a handle
     * to the [LayoutInflater] for the context of the *builder* (which is the [FragmentActivity] this
     * fragment is currently associated with). We then use *inflater* to inflate our layout file
     * R.layout.dialog_message into our [TextView] variable *messageView*, set its *text* to the
     * [CharSequence] in *args* stored under the key KEY_MESSAGE, and then set the view of *builder*
     * to *messageView*. We initialize our variable *positiveButtonLabel* with the [CharSequence]
     * stored in *args* under the key KEY_BUTTON_POSITIVE, and if this is not null we set the positive
     * button of *builder* to have *positiveButtonLabel* as its text and *this* as its [OnClickListener].
     * We then set the title of *builder* to the [CharSequence] stored under the key KEY_TITLE in
     * *args* and return the [AlertDialog] built by the *create* method of *builder* to the caller.
     *
     * @param savedInstanceState The last saved instance state of the *Fragment* or null if this is
     * a freshly created *Fragment*.
     * @return a new [Dialog] instance to be displayed by the *Fragment*.
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = arguments ?: Bundle.EMPTY
        val builder = AlertDialog.Builder(activity)

        val inflater = LayoutInflater.from(builder.context)
        @SuppressLint("InflateParams")
        val messageView = inflater.inflate(R.layout.dialog_message, null) as TextView
        messageView.text = args.getCharSequence(KEY_MESSAGE)
        builder.setView(messageView)

        builder.setNegativeButton(args.getCharSequence(KEY_BUTTON_NEGATIVE), null/* listener */)

        val positiveButtonLabel = args.getCharSequence(KEY_BUTTON_POSITIVE)
        if (positiveButtonLabel != null) {
            builder.setPositiveButton(positiveButtonLabel, this)
        }

        builder.setTitle(args.getCharSequence(KEY_TITLE))

        return builder.create()
    }

    /**
     * This method will be invoked when a button in the dialog is clicked. We initialize our variable
     * *activity* to the [FragmentActivity] this fragment is currently associated with. If *activity*
     * is an *AlertDialogFragment* *OnClickListener* we call its *onClick* method with *this* as the
     * [AlertDialogFragment] that received the click, and [which] as the button that was clicked.
     *
     * @param dialog the dialog that received the click.
     * @param which the button that was clicked.
     */
    override fun onClick(dialog: DialogInterface, which: Int) {
        val activity = activity
        if (activity is OnClickListener /* always true */) {
            (activity as OnClickListener).onClick(this, which)
        }
    }

    /**
     * Contains our constants and static factory methods.
     */
    companion object {

        private val NAME = AlertDialogFragment::class.java.name
        private val KEY_MESSAGE = NAME + "_message"
        private val KEY_BUTTON_NEGATIVE = NAME + "_button_negative"
        private val KEY_BUTTON_POSITIVE = NAME + "_button_positive"
        private val KEY_TITLE = NAME + "_title"

        /**
         * Convenience method for creating and showing a [DialogFragment] with the given message and
         * title string resource id's. We just fetch the strings that are stored in our APK for the
         * resource id's [title], [message] and [positiveButtonLabel] (if they are not 0) then call
         * the implementation of [showMessageDialog] which uses strings with these strings instead
         * of the resource id's.
         *
         * @param activity originating [FragmentActivity]
         * @param title resource id for the title string
         * @param message resource id for the displayed message string
         * @param positiveButtonLabel label for second button, if any. If non-null, activity must
         * implement *AlertDialogFragment.OnClickListener* to respond to that button being clicked.
         * @param tag tag for the *Fragment* that the *FragmentManager* will add.
         */
        fun showMessageDialog(activity: FragmentActivity,
                              @StringRes title: Int,
                              @StringRes message: Int,
                              @StringRes positiveButtonLabel: Int,
                              tag: String?) {
            showMessageDialog(activity,
                    if (title != 0) activity.getString(title) else null,
                    activity.getString(message),
                    if (positiveButtonLabel != 0) activity.getString(positiveButtonLabel) else null,
                    tag)
        }

        /**
         * Create and show a DialogFragment with the given message. We initialize our variable
         * *manager* to a handle to the FragmentManager for interacting with fragments associated
         * with [activity]. If *manager* has been destroyed we return having done nothing. We
         * initialize our variable *dialogFragment* with a new instance of [AlertDialogFragment],
         * and our variable *args* with a new instance of [Bundle]. We add [message] to *args* under
         * the key KEY_MESSAGE, add the string with resource id R.string.dismiss ("Dismiss") to *args*
         * under the key KEY_BUTTON_NEGATIVE, add [positiveButtonLabel] to *args* under the key
         * KEY_BUTTON_POSITIVE (if it is not null), and add [title] to *args* under the key KEY_TITLE.
         * We then set the arguments of *dialogFragment* to *args*, and call its *show* method to
         * have *manager* display the dialog.
         *
         * @param activity originating Activity
         * @param title displayed title, if any
         * @param message displayed message
         * @param positiveButtonLabel label for second button, if any. If non-null, activity must
         * implement *AlertDialogFragment.OnClickListener* to respond to that button being clicked.
         * @param tag tag for the *Fragment* that the *FragmentManager* will add.
         */
        fun showMessageDialog(activity: FragmentActivity,
                              title: CharSequence?,
                              message: CharSequence,
                              positiveButtonLabel: CharSequence?,
                              tag: String?) {
            val manager = activity.supportFragmentManager
            if (manager.isDestroyed) {
                return
            }
            val dialogFragment = AlertDialogFragment()
            val args = Bundle()
            args.putCharSequence(KEY_MESSAGE, message)
            args.putCharSequence(KEY_BUTTON_NEGATIVE, activity.getString(R.string.dismiss))
            if (positiveButtonLabel != null) {
                args.putCharSequence(KEY_BUTTON_POSITIVE, positiveButtonLabel)
            }
            args.putCharSequence(KEY_TITLE, title)
            dialogFragment.arguments = args
            dialogFragment.show(manager, tag /* tag */)
        }
    }
}
