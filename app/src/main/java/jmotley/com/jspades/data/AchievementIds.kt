package jmotley.com.jspades.data

data class AchievementMeta(val id: String, val title: String, val description: String)

object AchievementIds {
    const val WINS = "Wins"
    const val LOSSES = "Losses"
    const val QUITS = "Quits"
    const val GOT_ALL = "GotAll"
    const val GOT_NIL = "GotNil"
    const val GOT_BLIND_NIL = "GotBlindNil"
    const val GOT_TEN = "GotTen"
    const val GOT_EXACT_BID = "GotExactBid"
    const val SET_OPPONENTS = "SetOpponents"
    const val SET_MULTIPLE_OPPONENTS = "SetMultipleOpponents"
    const val BLOWOUT_WINS = "BlowoutWins"

    val ALL = listOf(
        AchievementMeta(WINS, "Winner", "Won a game"),
        AchievementMeta(LOSSES, "Tough Break", "Lost a game"),
        AchievementMeta(QUITS, "Early Exit", "Quit a game in progress"),
        AchievementMeta(GOT_ALL, "Boston", "Won every book in a hand"),
        AchievementMeta(GOT_NIL, "Nil Master", "Made a successful nil bid"),
        AchievementMeta(GOT_BLIND_NIL, "Flying Blind", "Made a successful blind nil"),
        AchievementMeta(GOT_TEN, "The Big Ten", "Won 10 or more books in a single hand"),
        AchievementMeta(GOT_EXACT_BID, "Precision Bidder", "Made exactly the bid (zero overtricks)"),
        AchievementMeta(SET_OPPONENTS, "The Setter", "Set the opposing team"),
        AchievementMeta(SET_MULTIPLE_OPPONENTS, "Double Trouble", "Set 2+ opponents in a solo hand"),
        AchievementMeta(BLOWOUT_WINS, "Blowout", "Won by a wide margin")
    )
}
