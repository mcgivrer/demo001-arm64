import java.util.Random;

/**
 * Procedural star name generator. Names are assembled from syllable tables
 * (onset + optional middle + ending), optionally followed by a catalog
 * designation (Greek letter or Roman numeral). All randomness is drawn from
 * the caller-supplied {@link Random}, so a star seeded from the master SEED
 * always receives the same name.
 */
public final class StarNameGenerator {

    private static final String[] ONSETS = {
        "Al", "Be", "Ca", "De", "El", "Fa", "Ga", "Ha", "Ike", "Ju",
        "Ke", "Ly", "Mi", "Na", "Or", "Pha", "Qui", "Ra", "Sa", "Ta",
        "Ur", "Ve", "Wy", "Xa", "Yo", "Ze", "Kra", "Tho", "Vel", "Ny"
    };

    private static final String[] MIDDLES = {
        "b", "br", "d", "dr", "g", "k", "l", "ll", "m", "n",
        "nd", "r", "rr", "s", "st", "t", "th", "v", "x", "z"
    };

    private static final String[] VOWELS = {
        "a", "e", "i", "o", "u", "ae", "ia", "ei", "ou", "y"
    };

    private static final String[] ENDINGS = {
        "n", "r", "s", "th", "x", "ris", "rus", "lon", "mar", "nis",
        "dar", "tis", "phos", "gon", "des", "lia", "nia", "ra", "on", "ar"
    };

    private static final String[] GREEK = {
        "Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta",
        "Eta", "Theta", "Iota", "Kappa", "Lambda", "Sigma", "Omega"
    };

    private static final String[] ROMAN = {
        "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    };

    private StarNameGenerator() {}

    /** Builds one name, consuming a fixed-purpose stream of draws from {@code rng}. */
    public static String generate(Random rng) {
        StringBuilder name = new StringBuilder();
        name.append(ONSETS[rng.nextInt(ONSETS.length)]);

        // 0–2 internal syllables: consonant cluster + vowel
        int syllables = rng.nextInt(3);
        for (int s = 0; s < syllables; s++) {
            name.append(MIDDLES[rng.nextInt(MIDDLES.length)]);
            name.append(VOWELS[rng.nextInt(VOWELS.length)]);
        }
        name.append(ENDINGS[rng.nextInt(ENDINGS.length)]);

        // ~35 % of names carry a catalog designation
        double d = rng.nextDouble();
        if (d < 0.15) {
            name.insert(0, GREEK[rng.nextInt(GREEK.length)] + " ");
        } else if (d < 0.35) {
            name.append(' ').append(ROMAN[rng.nextInt(ROMAN.length)]);
        }
        return name.toString();
    }
}
