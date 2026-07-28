package com.sluggyard.tv.ui.rewrite

fun seasonDisplayLabel(number: Int): String = if (number == 0) "Specials" else "Season $number"

fun <T> List<T>.regularSeasonsThenSpecials(number: (T) -> Int): List<T> =
    sortedWith(compareBy<T> { number(it) == 0 }.thenBy(number))
