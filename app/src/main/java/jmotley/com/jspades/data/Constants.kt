package jmotley.com.jspades.data

object Constants {
    val OFFLINE_PLAYER_NAMES = listOf(
        "Al", "Ms Jenkins", "cornbread", "Moon Beam", "Luv2 renig",
        "stay high", "pizza man", "Lil Buddy", "Mr Bob", "Melvis", "Jethro",
        "The Cutie", "Sherod", "Pookie", "Ray Ray", "Tyrone", "Vinny", "Don",
        "groovy", "Ephram", "Afro Mama", "Bus Driver",
        "worldwide", "Homer", "Foot Dr.", "Jebediah", "Nephew",
        "Funkadelic", "Whack MC", "1st 2 run",
        "King Tut", "Junebug", "Mr Fix It", "Just Say No",
        "R&B thug", "Abubakari", "Flo", "Junior",
        "Ted", "Jake", "Kip", "Sam",
        "Sunny", "Mike", "Bill", "Rob", "Amy", "Susan", "Sarah",
        "Larry", "Clarence", "Simone", "Barbara",
        "Ralph", "Loise", "Richie", "Rev Brown", "smoke", "Buddy", "Car shed",
        "Val", "Grammy", "suchNsuch", "Auntie", "Uncle", "Babu", "Alex",
        "Igor", "Kumar", "Vlad", "Sergie", "Singh", "Patel", "Smithers",
        "Raheem", "Ibn", "Natasha", "Tony", "Macho", "fugitive", "Olaudah",
        "Booker", "W.E.B.", "Frederick", "Harriett", "Rosa", "Malcolm", "Mohandas", "Kyla",
        "Haley", "Albert", "Isaac", "Luther", "Dan", "Dmitriy",
        "Joel", "Chuck", "Jane", "Yohan", "TiTi", "Deacon", "Frank", "Ruth",
        "Zach", "CraigAndNim", "Kam", "Nathaniel", "Heath", "Mac", "Kamala", "Sonia",
        "Morpheus", "Nostradamus",  "Ringo", "Worse Bunny",  "Kendrick", "Shakira", "Chef", "Bron", "Ant", "Ed", "Bruno", "Nicki", "Jerry Lawson",
        "Imhotep", "Benjamin", "Mary", "Granville", "CJ", "Backup QB", "Hattie", "Otis", "Elijah", "Mae", "Ayanna", "Lilia" ,
        "Walt", "Ursula",  "Usain", "Shelley", "Elaine", "Shericka", "Allison", "Sydney", "Isiah",  "Zeke", "GoLow", "Pre Malone", "Adult Gambino", "21 Sandwich",
        "Couch Potato"
    )

    val NAME_PAIRS = hashMapOf("Kid" to "Play", "Barack" to "Michelle", "Peaches" to "Herbes",
        "Salt" to "Pepa","Tommy" to "Cole","Martin" to "Gina","Heathcliff" to "Claire","George" to "Wheezy",
        "Dre" to "Snoopy","Mama" to "Bobo","Lisa" to "Wendy","Will" to "Carlton","Sanford" to "Son","Redd F" to "Richard P",
        "Tito" to "Michael","Chris" to "Dave","Bernie" to "Steve","Cedric" to "Hughley","Katherine" to "Dorothy",
        "Ceephus" to "Reecie","Sheneneh" to "Keylolo","Kareem" to "Oscar","Arnold" to "Willis","Mark" to "Dean",
        "Garett" to "Morgan","Stevie" to "Prince","Bacardi" to "Megan","Frank" to "Beans","Siri" to "Alexa"
    )

    val RENEGE_JOKES = listOf(
        "You must like reneging.",
        "Seriously, this game doesn't let you renege.",
        "I'm going to close my eyes and pretend you didn't try to renege.",
        "I must admit, you are very persistent.",
        "I need a rap name. Is S. Doggy Dog taken? That's the one I want.",
        "There are people in your phone. And they're watching you renege.",
        "Remind me not to play you in real life.",
        "I bet you thought renege was spelled RENIG. I sure did.",
        "Did your friends ever catch you cheating?",
        "Do you think you're going to renege one day and I'm going to let it slide?",
        "We truly appreciate you playing this game, even though you always try to cheat.",
        "I hope the IRS doesn't find out how much money I owe them.",
        "Four and a strong possible is not a good answer when someone asks a man how many kids he has.",
        "I'm running out of renege jokes here.",
        "You're gonna get carpal tunnel syndrome from all this reneging.",
        "You must enjoy these conversations.",
        "Thanks to you, I have to release a new version of the game just to come up with more renege jokes.",
        "Next time you renege, I'm gonna let it slide. I promise.",
        "You didn't really think I'd let it slide did you?",
        "Confucius say, stop reneging.",
        "All men are created equal, but not all cards are - Abraham Lincoln, 1861",
        "It's only reneging if you get caught. BTW, you got caught.",
        "If you knew how to bid, you wouldn't have to renege so much.",
        "A wise man once said something about reneging. You obviously didn't listen.",
        "I steal cable. How about you?",
        "Don't do drugs. They make people renege.",
        "Is it a nice day for reneging?",
        "Do you want your kids to grow up and become people who renege?",
        "Friends don't let friends renege.",
        "Don't forget to wear a mask so people won't see you renege."
    )

    fun generateCpuNames(count: Int): List<String> {
        if (count <= 0) return emptyList()
        val offline  = OFFLINE_PLAYER_NAMES
        val pairKeys = NAME_PAIRS.keys.toList()
        val combined = offline.size + pairKeys.size
        val used     = mutableSetOf<String>()
        val names    = mutableListOf<String>()
        val rng      = kotlin.random.Random.Default

        val wi = rng.nextInt(combined)
        val firstName: String
        val forcedSecond: String?
        if (wi < offline.size) {
            firstName    = offline[wi]
            forcedSecond = null
        } else {
            firstName    = pairKeys[wi - offline.size]
            forcedSecond = NAME_PAIRS[firstName]!!
        }
        used += firstName
        forcedSecond?.let { used += it }
        names += firstName

        if (count >= 2) {
            val second = forcedSecond ?: offline.filter { it !in used }.random(rng)
            used += second
            names += second
        }
        if (count >= 3) {
            val third = offline.filter { it !in used }.random(rng)
            names += third
        }
        return names
    }

    // Helper function to reset renege counter for testing
    fun resetRenegeCounter(context: android.content.Context) {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("renege_count", 0)
            .putInt("last_joke_index", -1)
            .apply()
    }
}