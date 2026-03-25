package org.mage.test.replay;

import mage.ObjectColor;
import mage.cards.repository.CardCriteria;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.constants.*;
import mage.game.Game;
import mage.game.GameException;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.util.RandomUtil;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates .mtg replay files from random decks built from a given set.
 *
 * Usage:
 *   mvn test -pl Mage.Tests \
 *     -Dtest=StaxReplayGenerator#generate \
 *     -Dstax.set=M11 \
 *     -Dstax.seed=0xdeadbeef \
 *     -Dxmage.staxReplayWriter.replayFile=replay-M11-deadbeef.mtg
 */
public class StaxReplayGenerator extends CardTestPlayerBaseAI {

    private static final int DECK_SIZE = 60;
    private static final int LAND_COUNT = 24;
    private static final int NONLAND_COUNT = DECK_SIZE - LAND_COUNT;

    @Override
    protected Game createNewGameAndPlayers() throws GameException, java.io.FileNotFoundException {
        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
        playerA = createPlayer(game, "Alice");
        playerB = createPlayer(game, "Bob");
        return game;
    }

    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("Alice", "Bob");
    }

    @Test
    public void generate() {
        String setCode = System.getProperty("stax.set", "M11");
        long seed = Long.decode(System.getProperty("stax.seed", "0xdeadbeef"));

        // Enable StaxReplayWriter automatically
        System.setProperty("xmage.dataCollectors.staxReplayWriter", "true");

        // Default replay file path if not specified
        if (System.getProperty("xmage.staxReplayWriter.replayFile") == null) {
            System.setProperty("xmage.staxReplayWriter.replayFile",
                    String.format("replay-%s-%08x.mtg", setCode, seed));
        }

        RandomUtil.setSeed(seed);
        Random deckRng = new Random(seed);

        // Load all cards from the set
        List<CardInfo> allCards = CardRepository.instance.findCards(new CardCriteria().setCodes(setCode));
        Assert.assertFalse("No cards found for set " + setCode, allCards.isEmpty());

        // Separate into categories
        List<CardInfo> basicLands = new ArrayList<>();
        List<CardInfo> nonbasicLands = new ArrayList<>();
        List<CardInfo> creatures = new ArrayList<>();
        List<CardInfo> nonCreatureSpells = new ArrayList<>();

        for (CardInfo card : allCards) {
            List<CardType> types = card.getTypes();
            List<SuperType> supertypes = card.getSupertypes();

            if (types.contains(CardType.LAND)) {
                if (supertypes.contains(SuperType.BASIC)) {
                    basicLands.add(card);
                } else {
                    nonbasicLands.add(card);
                }
            } else if (types.contains(CardType.CREATURE)) {
                creatures.add(card);
            } else if (!types.isEmpty()) {
                nonCreatureSpells.add(card);
            }
        }

        // Build two decks with different color pairs
        String[][] colorPairs = {
                {"white", "blue"}, {"blue", "black"}, {"black", "red"},
                {"red", "green"}, {"green", "white"}, {"white", "black"},
                {"blue", "red"}, {"black", "green"}, {"red", "white"},
                {"green", "blue"}
        };

        int pairIdx1 = deckRng.nextInt(colorPairs.length);
        int pairIdx2;
        do {
            pairIdx2 = deckRng.nextInt(colorPairs.length);
        } while (pairIdx2 == pairIdx1);

        List<String> deckA = buildDeck(setCode, creatures, nonCreatureSpells, basicLands, nonbasicLands,
                colorPairs[pairIdx1][0], colorPairs[pairIdx1][1], deckRng);
        List<String> deckB = buildDeck(setCode, creatures, nonCreatureSpells, basicLands, nonbasicLands,
                colorPairs[pairIdx2][0], colorPairs[pairIdx2][1], deckRng);

        // Set up the game
        skipInitShuffling();
        enableReplayMode();
        removeAllCardsFromLibrary(playerA);
        removeAllCardsFromLibrary(playerB);
        removeAllCardsFromHand(playerA);
        removeAllCardsFromHand(playerB);

        // Add cards — last added = top of library = drawn first
        for (String cardName : deckA) {
            addCard(Zone.LIBRARY, playerA, cardName, 1);
        }
        for (String cardName : deckB) {
            addCard(Zone.LIBRARY, playerB, cardName, 1);
        }

        setStopAt(50, PhaseStep.END_TURN);
        execute();

        Assert.assertTrue("Game should end before turn 50", currentGame.hasEnded());
    }

    /**
     * Build a 60-card deck for two colors.
     * ~24 lands, ~22-24 creatures, ~12-14 spells.
     * Follows a basic mana curve: prefer low-cost creatures, some mid-range, a few bombs.
     */
    private List<String> buildDeck(String setCode, List<CardInfo> creatures, List<CardInfo> spells,
                                    List<CardInfo> basicLands, List<CardInfo> nonbasicLands,
                                    String color1, String color2, Random rng) {
        List<String> deck = new ArrayList<>();

        // Filter creatures and spells that match our colors (mono or both)
        List<CardInfo> colorCreatures = filterByColors(creatures, color1, color2);
        List<CardInfo> colorSpells = filterByColors(spells, color1, color2);

        // Shuffle pools
        Collections.shuffle(colorCreatures, rng);
        Collections.shuffle(colorSpells, rng);

        // Target: ~22 creatures, ~14 spells
        int targetCreatures = Math.min(22, colorCreatures.size());
        int targetSpells = Math.min(NONLAND_COUNT - targetCreatures, colorSpells.size());
        // If not enough spells, add more creatures
        if (targetCreatures + targetSpells < NONLAND_COUNT) {
            targetCreatures = Math.min(NONLAND_COUNT - targetSpells, colorCreatures.size());
        }

        // Sort by mana value for a reasonable curve, then pick
        colorCreatures.sort(Comparator.comparingInt(CardInfo::getManaValue));
        colorSpells.sort(Comparator.comparingInt(CardInfo::getManaValue));

        // Pick creatures with a curve bias: more low-cost, fewer high-cost
        List<CardInfo> pickedCreatures = pickWithCurve(colorCreatures, targetCreatures, rng);
        List<CardInfo> pickedSpells = pickWithCurve(colorSpells, targetSpells, rng);

        for (CardInfo c : pickedCreatures) {
            deck.add(c.getSetCode() + "-" + c.getName());
        }
        for (CardInfo c : pickedSpells) {
            deck.add(c.getSetCode() + "-" + c.getName());
        }

        // Pad with colorless creatures/spells if we're short
        if (deck.size() < NONLAND_COUNT) {
            List<CardInfo> colorless = creatures.stream()
                    .filter(c -> c.getColor().isColorless())
                    .collect(Collectors.toList());
            Collections.shuffle(colorless, rng);
            for (CardInfo c : colorless) {
                if (deck.size() >= NONLAND_COUNT) break;
                deck.add(c.getSetCode() + "-" + c.getName());
            }
        }

        // Add lands — split roughly evenly between the two colors
        String land1 = setCode + "-" + basicLandForColor(color1);
        String land2 = setCode + "-" + basicLandForColor(color2);
        int lands1 = LAND_COUNT / 2;
        int lands2 = LAND_COUNT - lands1;
        for (int i = 0; i < lands1; i++) {
            deck.add(land1);
        }
        for (int i = 0; i < lands2; i++) {
            deck.add(land2);
        }

        // Shuffle the deck
        Collections.shuffle(deck, rng);

        return deck;
    }

    private List<CardInfo> filterByColors(List<CardInfo> cards, String color1, String color2) {
        return cards.stream().filter(c -> {
            ObjectColor color = c.getColor();
            boolean matchesColor = matchesColor(color, color1) || matchesColor(color, color2);
            boolean isColorless = color.isColorless();
            // Exclude cards that require colors outside our pair
            boolean hasOffColor = false;
            if (color.isBlack() && !color1.equals("black") && !color2.equals("black")) hasOffColor = true;
            if (color.isBlue() && !color1.equals("blue") && !color2.equals("blue")) hasOffColor = true;
            if (color.isGreen() && !color1.equals("green") && !color2.equals("green")) hasOffColor = true;
            if (color.isRed() && !color1.equals("red") && !color2.equals("red")) hasOffColor = true;
            if (color.isWhite() && !color1.equals("white") && !color2.equals("white")) hasOffColor = true;
            return (matchesColor || isColorless) && !hasOffColor;
        }).collect(Collectors.toList());
    }

    private boolean matchesColor(ObjectColor color, String colorName) {
        switch (colorName) {
            case "white": return color.isWhite();
            case "blue": return color.isBlue();
            case "black": return color.isBlack();
            case "red": return color.isRed();
            case "green": return color.isGreen();
            default: return false;
        }
    }

    /**
     * Pick cards with mana curve bias.
     * Cards should already be sorted by mana value.
     * Prefer: ~40% 1-2 CMC, ~35% 3-4 CMC, ~25% 5+ CMC
     */
    private List<CardInfo> pickWithCurve(List<CardInfo> sorted, int count, Random rng) {
        List<CardInfo> low = new ArrayList<>();    // CMC 0-2
        List<CardInfo> mid = new ArrayList<>();    // CMC 3-4
        List<CardInfo> high = new ArrayList<>();   // CMC 5+

        for (CardInfo c : sorted) {
            int mv = c.getManaValue();
            if (mv <= 2) low.add(c);
            else if (mv <= 4) mid.add(c);
            else high.add(c);
        }

        Collections.shuffle(low, rng);
        Collections.shuffle(mid, rng);
        Collections.shuffle(high, rng);

        List<CardInfo> picked = new ArrayList<>();
        int wantLow = (int) (count * 0.40);
        int wantMid = (int) (count * 0.35);
        int wantHigh = count - wantLow - wantMid;

        addUpTo(picked, low, wantLow);
        addUpTo(picked, mid, wantMid);
        addUpTo(picked, high, wantHigh);

        // Fill remaining from whatever is available
        List<CardInfo> remaining = new ArrayList<>();
        remaining.addAll(low.subList(Math.min(wantLow, low.size()), low.size()));
        remaining.addAll(mid.subList(Math.min(wantMid, mid.size()), mid.size()));
        remaining.addAll(high.subList(Math.min(wantHigh, high.size()), high.size()));
        Collections.shuffle(remaining, rng);
        addUpTo(picked, remaining, count - picked.size());

        return picked;
    }

    private void addUpTo(List<CardInfo> dest, List<CardInfo> src, int count) {
        for (int i = 0; i < Math.min(count, src.size()); i++) {
            dest.add(src.get(i));
        }
    }

    private String basicLandForColor(String color) {
        switch (color) {
            case "white": return "Plains";
            case "blue": return "Island";
            case "black": return "Swamp";
            case "red": return "Mountain";
            case "green": return "Forest";
            default: throw new IllegalArgumentException("Unknown color: " + color);
        }
    }
}
