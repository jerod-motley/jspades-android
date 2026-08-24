package jmotley.com.jspades.data

fun formatBidForDisplay(bid: Int, isBlind: Boolean): String = when {
    bid == 0 && isBlind -> "B Nil"
    bid == 0 -> "Nil"
    isBlind -> "B$bid"
    else -> "$bid"
}
