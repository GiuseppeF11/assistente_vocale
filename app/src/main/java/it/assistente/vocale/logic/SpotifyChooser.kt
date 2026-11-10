package it.assistente.vocale.logic

/** Un risultato della ricerca su Spotify. */
data class SpotifyItem(
    val kind: Kind,
    val name: String,
    val uri: String,
    /** per i brani: l'artista */
    val artist: String? = null,
    /** per i brani: l'album che li contiene (con account gratuito si riproduce quello) */
    val albumUri: String? = null,
) {
    enum class Kind { TRACK, ARTIST, ALBUM, PLAYLIST }

    /** "Albachiara di Vasco Rossi", "l'album Nevermind" */
    fun spoken(): String = when (kind) {
        Kind.TRACK -> if (artist.isNullOrBlank()) name else "$name di $artist"
        Kind.ARTIST -> name
        Kind.ALBUM -> "l'album $name"
        Kind.PLAYLIST -> "la playlist $name"
    }
}

/** Sceglie cosa riprodurre tra i risultati, in base a cosa si è chiesto. */
object SpotifyChooser {

    private fun close(a: String, b: String, min: Double): Boolean {
        val x = Text.normalize(a).replace(" ", "")
        val y = Text.normalize(b).replace(" ", "")
        return x.isNotEmpty() && Text.soundsLike(x, y) >= min
    }

    fun choose(
        query: String,
        focus: MediaFocus,
        tracks: List<SpotifyItem>,
        artists: List<SpotifyItem>,
        albums: List<SpotifyItem>,
        playlists: List<SpotifyItem>,
    ): SpotifyItem? = when (focus) {
        MediaFocus.SONG -> tracks.firstOrNull()
        MediaFocus.ARTIST -> artists.firstOrNull()
        MediaFocus.ALBUM -> albums.firstOrNull()
        MediaFocus.PLAYLIST -> playlists.firstOrNull()
        MediaFocus.ANY -> {
            val artist = artists.firstOrNull()
            val track = tracks.firstOrNull()
            when {
                // "metti Vasco Rossi": il nome detto è quello di un artista
                artist != null && close(query, artist.name, 0.85) -> artist
                // "metti Albachiara di Vasco Rossi": titolo + artista
                track != null && close(query, "${track.name} ${track.artist.orEmpty()}", 0.8) -> track
                track != null && close(query, track.name, 0.8) -> track
                // nome esatto di un album o di una playlist
                albums.firstOrNull()?.takeIf { close(query, it.name, 0.9) } != null -> albums.first()
                playlists.firstOrNull()?.takeIf { close(query, it.name, 0.9) } != null -> playlists.first()
                track != null -> track
                else -> artist ?: albums.firstOrNull() ?: playlists.firstOrNull()
            }
        }
    }
}
