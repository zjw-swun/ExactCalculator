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
     * Return a string with n copies of c.
     */
    fun repeat(c: Char, n: Int): String {
        val result = StringBuilder()
        for (i in 0 until n) {
            result.append(c)
        }
        return result.toString()
    }

    /**
     * Return a copy of the supplied string with commas added every three digits.
     * The substring indicated by the supplied range is assumed to contain only
     * a whole number, with no decimal point.
     * Inserting a digit separator every 3 digits appears to be
     * at least somewhat acceptable, though not necessarily preferred, everywhere.
     * The grouping separator in the result is NOT localized.
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
     * Ignoring all occurrences of c in both strings, check whether old is a prefix of new.
     * If so, return the remaining sub-sequence of whole. If not, return null.
     */
    fun getExtensionIgnoring(whole: CharSequence, prefix: CharSequence,
                             c: Char): CharSequence? {
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
