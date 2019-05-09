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

    interface OnClickListener {
        /**
         * This method will be invoked when a button in the dialog is clicked.
         *
         * @param fragment the AlertDialogFragment that received the click
         * @param which the button that was clicked (e.g.
         * [DialogInterface.BUTTON_POSITIVE]) or the position
         * of the item clicked
         */
        fun onClick(fragment: AlertDialogFragment, which: Int)
    }

    init {
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = if (arguments == null) Bundle.EMPTY else arguments
        val builder = AlertDialog.Builder(activity)

        val inflater = LayoutInflater.from(builder.context)
        @SuppressLint("InflateParams") val messageView = inflater.inflate(
                R.layout.dialog_message, null/* root */) as TextView
        messageView.text = args!!.getCharSequence(KEY_MESSAGE)
        builder.setView(messageView)

        builder.setNegativeButton(args.getCharSequence(KEY_BUTTON_NEGATIVE), null/* listener */)

        val positiveButtonLabel = args.getCharSequence(KEY_BUTTON_POSITIVE)
        if (positiveButtonLabel != null) {
            builder.setPositiveButton(positiveButtonLabel, this)
        }

        builder.setTitle(args.getCharSequence(KEY_TITLE))

        return builder.create()
    }

    override fun onClick(dialog: DialogInterface, which: Int) {
        val activity = activity
        if (activity is OnClickListener /* always true */) {
            (activity as OnClickListener).onClick(this, which)
        }
    }

    companion object {

        private val NAME = AlertDialogFragment::class.java.name
        private val KEY_MESSAGE = NAME + "_message"
        private val KEY_BUTTON_NEGATIVE = NAME + "_button_negative"
        private val KEY_BUTTON_POSITIVE = NAME + "_button_positive"
        private val KEY_TITLE = NAME + "_title"

        /**
         * Convenience method for creating and showing a DialogFragment with the given message and
         * title.
         *
         * @param activity originating Activity
         * @param title resource id for the title string
         * @param message resource id for the displayed message string
         * @param positiveButtonLabel label for second button, if any.  If non-null, activity must
         * implement AlertDialogFragment.OnClickListener to respond.
         */
        fun showMessageDialog(activity: FragmentActivity, @StringRes title: Int,
                              @StringRes message: Int, @StringRes positiveButtonLabel: Int, tag: String?) {
            showMessageDialog(activity, if (title != 0) activity.getString(title) else null,
                    activity.getString(message),
                    if (positiveButtonLabel != 0) activity.getString(positiveButtonLabel) else null,
                    tag)
        }

        /**
         * Create and show a DialogFragment with the given message.
         *
         * @param activity originating Activity
         * @param title displayed title, if any
         * @param message displayed message
         * @param positiveButtonLabel label for second button, if any.  If non-null, activity must
         * implement AlertDialogFragment.OnClickListener to respond.
         */
        fun showMessageDialog(activity: FragmentActivity, title: CharSequence?,
                              message: CharSequence, positiveButtonLabel: CharSequence?, tag: String?) {
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
