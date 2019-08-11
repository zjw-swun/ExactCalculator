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

/**
 * Some helpful methods operating on strings.
 */

object StringUtils {

    /**
     * Return a string with [n] copies of [c].
     *
     * @param c Character we wish to copy.
     * @param n Number of copies of [c] to make.
     * @return A [String] consisting of [n] copies of [c].
     */
    fun repeat(c: Char, n: Int): String {
        val result = StringBuilder()
        for (i in 0 until n) {
            result.append(c)
        }
        return result.toString()
    }

    /**
     * Return a copy of the supplied string with commas added every three digits. The substring
     * indicated by the supplied range is assumed to contain only a whole number, with no decimal
     * point. Inserting a digit separator every 3 digits appears to be at least somewhat acceptable,
     * though not necessarily preferred, everywhere. The grouping separator in the result is NOT
     * localized.
     *
     * First we initialize our `val result` with a new instance of [StringBuilder]. We initialize
     * our `var current` to [begin], then while `current` is less than [end] and the `current`
     * character in [s] is either an '-' or an ' ' character we increment `current` to skip it.
     * We then append the characters between [begin] and `current` in [s] to `result`. Next we loop
     * while `current` is less than [end] appending the `current` character in [s] to `result` then
     * incrementing `current`. If the characters remaining ([end] minus `current`) modulo 3 is 0 and
     * [end] is not equal to `current` we also append a ',' character to `result`.
     *
     * When done (`current` is not equal to [end]) we return the string value of `result`.
     *
     * @param s String to have commas inserted into.
     * @param begin Starting index of substring of [s] to consider.
     * @param end Ending index of substring of [s] to consider.
     * @return Copy of [s] with commas inserted every three digits.
     */
    fun addCommas(s: String, begin: Int, end: Int): String {
        // Resist the temptation to use Java's NumberFormat, which converts to long or double
        // and hence doesn't handle very large numbers.
        val result = StringBuilder()
        var current = begin
        while (current < end && (s[current] == '-' || s[current] == ' ')) {
            ++current
        }
        result.append(s, begin, current)
        while (current < end) {
            result.append(s[current])
            ++current
            if ((end - current) % 3 == 0 && end != current) {
                result.append(',')
            }
        }
        return result.toString()
    }

    /**
     * Ignoring all occurrences of [c] in both strings, check whether [prefix] is a prefix of
     * [whole]. If so, return the remaining sub-sequence of [whole]. If not, return null. We
     * initialize our `var wIndex` to 0 (our character index into [whole]), our `var pIndex`
     * to 0 (our character index into [prefix]), our `val wLen` to the length of [whole], and
     * our `val pLen` to the length of [prefix]. We then loop forever:
     * - We increment `pIndex` as long as the `pIndex` character of [prefix] is [c].
     * - We increment `wIndex` as long as the `wIndex` character of [whole] is [c].
     * - If `pIndex` is equal to `pLen` we break out of the loop.
     * - If `wIndex` is equal to `wLen` or the `wIndex` character of [whole] is not equal to the
     * `pIndex` character of [prefix] we return *null* (there are either no new characters at the
     * end of [whole] or [prefix] is not a prefix of [whole] after all).
     * - We increment both `pIndex` and `wIndex` and loop around to compare the next character in
     * [prefix] and [whole].
     *
     * If we break out of the infinite loop without returning *null* there are new characters at
     * the end of [whole], so we first increment `wIndex` to skip any [c] characters in [whole],
     * then return the subsequence of [whole] from `wIndex` to `wLen` to the caller.
     *
     * @param whole String to examine for [prefix], and whose extension to [prefix] to return.
     * @param prefix Old string which might be extended in [whole].
     * @param c Character to ignore while comparing (the separator character in our case).
     * @return String of characters added to the end of [prefix] to create [whole] or *null*.
     */
    fun getExtensionIgnoring(
            whole: CharSequence,
            prefix: CharSequence,
            c: Char
    ): CharSequence? {
        var wIndex = 0
        var pIndex = 0
        val wLen = whole.length
        val pLen = prefix.length
        while (true) {
            while (pIndex < pLen && prefix[pIndex] == c) {
                ++pIndex
            }
            while (wIndex < wLen && whole[wIndex] == c) {
                ++wIndex
            }
            if (pIndex == pLen) {
                break
            }
            if (wIndex == wLen || whole[wIndex] != prefix[pIndex]) {
                return null
            }
            ++pIndex
            ++wIndex
        }
        while (wIndex < wLen && whole[wIndex] == c) {
            ++wIndex
        }
        return whole.subSequence(wIndex, wLen)
    }
}
