package mage.collectors.services;

import mage.Mana;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.game.ExileZone;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.events.ManaEvent;
import mage.game.events.ZoneChangeEvent;
import mage.constants.Zone;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentToken;
import mage.players.ManaPool;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.SpellAbility;
import mage.abilities.TriggeredAbility;
import mage.abilities.effects.Effect;
import mage.abilities.effects.mana.AddManaOfAnyColorEffect;
import mage.abilities.mana.ManaAbility;
import mage.constants.AbilityType;
import mage.game.stack.Spell;
import mage.game.stack.StackObject;
import mage.players.Player;
import mage.target.Target;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class StaxReplayWriter extends EmptyDataCollector {

    /* ---------------------------------------------------------------------
     * 1. Constants
     * ------------------------------------------------------------------- */

    public static final String SERVICE_CODE = "staxReplayWriter";

    /* ---------------------------------------------------------------------
     * 2. Static Fields
     * ------------------------------------------------------------------- */

    private static final Logger logger = Logger.getLogger(StaxReplayWriter.class);
    private static final String REPLAY_FILE = System.getProperty("xmage.staxReplayWriter.replayFile");

    /* ---------------------------------------------------------------------
     * 3. Inner Types
     * ------------------------------------------------------------------- */

    enum StaxEventType {
        PLAY_LAND,
        CAST_SPELL,
        TAP_MANA,
        DECLARE_ATTACKER,
        DECLARE_BLOCKER,
        ACTIVATE_ABILITY,
        MOVE,
        CHOOSE,
        TRIGGER,
        PASS,
        LEYLINE,
        DRAW,
        DRAW_BOTTOM,
        PUT_BOTTOM,
        MILL,
        REVEAL,
        LOOK,
        TOP,
        DISCARD,
        KEEP,
        MULLIGAN
    }

    private static class StaxEvent {
        StaxEventType type;
        String player;
        String arg;
        String manaArgs = "";
        String targets = "";
    }

    private static class PlayerSnapshot {
        String name;
        int life;
        int landsPlayed;
        int handSize;
        int librarySize;
        int graveyardSize;
        int exileSize;
        int manaW, manaU, manaB, manaR, manaG, manaC;
    }

    private static class PermanentSnapshot {
        UUID id;
        String path;
        boolean tapped;
        int power;
        int toughness;
        int damage;
        boolean summoningSick;
        boolean attacking;
        boolean blocking;
        boolean faceDown;
        boolean phasedOut;
        String controller;
        Map<String, Integer> counters = new LinkedHashMap<>();
    }

    private static class GameSnapshot {
        Map<UUID, PlayerSnapshot> players = new LinkedHashMap<>();
        Map<UUID, PermanentSnapshot> permanents = new LinkedHashMap<>();
    }

    /* ---------------------------------------------------------------------
     * 4. Instance Fields
     * ------------------------------------------------------------------- */

    private final Path replayFile;
    private int currentTurn;
    private String currentStepKey;
    private final Map<UUID, Integer> cardInstanceMap = new HashMap<>();
    private final Map<String, Integer> battlefieldInstanceCount = new HashMap<>();
    private final ArrayDeque<StaxEvent> pendingEvents = new ArrayDeque<>();
    private final List<StaxEvent> deferredPaymentChoices = new ArrayList<>();
    private GameSnapshot lastSnapshot;

    public StaxReplayWriter() {
        if (REPLAY_FILE != null) {
            this.replayFile = Paths.get(REPLAY_FILE);
            logger.info(String.format("StaxReplayWriter: writing to %s", this.replayFile));
            try {
                Files.createDirectories(this.replayFile.getParent());
            } catch (IOException e) {
                logger.error(String.format("StaxReplayWriter: failed to create output dir: %s", e), e);
            }
            try {
                Files.deleteIfExists(this.replayFile);
            } catch (IOException e) {
                logger.error(String.format("StaxReplayWriter: failed to clean old file: %s", e), e);
            }
        } else {
            this.replayFile = null;
            logger.info("StaxReplayWriter: writing to stdout");
        }
    }

    /* ---------------------------------------------------------------------
     * 5. Static Methods
     * ------------------------------------------------------------------- */

    private static Player requirePlayer(Game game, UUID playerId) {
        Player player = game.getPlayer(playerId);
        if (player == null) {
            throw new IllegalStateException(String.format("player not found: %s", playerId));
        }
        return player;
    }

    private static StackObject requireStackObject(Game game, UUID objectId) {
        StackObject stackObj = game.getStack().getStackObject(objectId);
        if (stackObj == null) {
            throw new IllegalStateException(String.format("stack object not found: %s", objectId));
        }
        return stackObj;
    }

    private static String sanitizeCounterName(String name) {
        if (name.equals("+1/+1")) return "p1p1";
        if (name.equals("-1/-1")) return "m1m1";
        return name.toLowerCase().replaceAll("[^a-z0-9_]", "");
    }

    private static String verbFor(StaxEventType type) {
        if (type == StaxEventType.PLAY_LAND) return "play";
        if (type == StaxEventType.CAST_SPELL) return "cast";
        if (type == StaxEventType.TAP_MANA) return "tap";
        if (type == StaxEventType.DECLARE_ATTACKER || type == StaxEventType.DECLARE_BLOCKER) return "declare";
        if (type == StaxEventType.ACTIVATE_ABILITY) return "activate";
        if (type == StaxEventType.MOVE) return "move";
        if (type == StaxEventType.CHOOSE) return "choose";
        if (type == StaxEventType.TRIGGER) return "trigger";
        if (type == StaxEventType.PASS) return "pass";
        if (type == StaxEventType.LEYLINE) return "leyline";
        if (type == StaxEventType.DRAW) return "draw";
        if (type == StaxEventType.DRAW_BOTTOM) return "draw_bottom";
        if (type == StaxEventType.PUT_BOTTOM) return "put_bottom";
        if (type == StaxEventType.MILL) return "mill";
        if (type == StaxEventType.REVEAL) return "reveal";
        if (type == StaxEventType.LOOK) return "look";
        if (type == StaxEventType.TOP) return "top";
        if (type == StaxEventType.DISCARD) return "discard";
        if (type == StaxEventType.KEEP) return "keep";
        if (type == StaxEventType.MULLIGAN) return "mulligan";
        return "unknown";
    }

    private static List<String[]> computeDelta(GameSnapshot prev, GameSnapshot curr) {
        List<String[]> delta = new ArrayList<>();
        if (prev == null || curr == null) {
            return delta;
        }

        for (Map.Entry<UUID, PlayerSnapshot> entry : curr.players.entrySet()) {
            UUID pid = entry.getKey();
            PlayerSnapshot cp = entry.getValue();
            PlayerSnapshot pp = prev.players.get(pid);
            if (pp == null) continue;

            if (cp.life != pp.life) delta.add(new String[]{String.format("%s.life", cp.name), String.valueOf(cp.life)});
            if (cp.landsPlayed != pp.landsPlayed) delta.add(new String[]{String.format("%s.lands_played", cp.name), String.valueOf(cp.landsPlayed)});
            if (cp.handSize != pp.handSize) delta.add(new String[]{String.format("%s.hand.length", cp.name), String.valueOf(cp.handSize)});
            if (cp.librarySize != pp.librarySize) delta.add(new String[]{String.format("%s.library.length", cp.name), String.valueOf(cp.librarySize)});
            if (cp.graveyardSize != pp.graveyardSize) delta.add(new String[]{String.format("%s.graveyard.length", cp.name), String.valueOf(cp.graveyardSize)});
            if (cp.exileSize != pp.exileSize) delta.add(new String[]{String.format("%s.exile.length", cp.name), String.valueOf(cp.exileSize)});
            if (cp.manaW != pp.manaW) delta.add(new String[]{String.format("%s.mana.w", cp.name), String.valueOf(cp.manaW)});
            if (cp.manaU != pp.manaU) delta.add(new String[]{String.format("%s.mana.u", cp.name), String.valueOf(cp.manaU)});
            if (cp.manaB != pp.manaB) delta.add(new String[]{String.format("%s.mana.b", cp.name), String.valueOf(cp.manaB)});
            if (cp.manaR != pp.manaR) delta.add(new String[]{String.format("%s.mana.r", cp.name), String.valueOf(cp.manaR)});
            if (cp.manaG != pp.manaG) delta.add(new String[]{String.format("%s.mana.g", cp.name), String.valueOf(cp.manaG)});
            if (cp.manaC != pp.manaC) delta.add(new String[]{String.format("%s.mana.c", cp.name), String.valueOf(cp.manaC)});
        }

        for (Map.Entry<UUID, PermanentSnapshot> entry : curr.permanents.entrySet()) {
            UUID permId = entry.getKey();
            PermanentSnapshot cp = entry.getValue();
            PermanentSnapshot pp = prev.permanents.get(permId);
            if (pp == null) continue;

            if (cp.tapped != pp.tapped) delta.add(new String[]{String.format("%s.tapped", cp.path), String.valueOf(cp.tapped)});
            if (cp.power != pp.power) delta.add(new String[]{String.format("%s.power", cp.path), String.valueOf(cp.power)});
            if (cp.toughness != pp.toughness) delta.add(new String[]{String.format("%s.toughness", cp.path), String.valueOf(cp.toughness)});
            if (cp.damage != pp.damage) delta.add(new String[]{String.format("%s.damage", cp.path), String.valueOf(cp.damage)});
            if (cp.summoningSick != pp.summoningSick) delta.add(new String[]{String.format("%s.summoning_sick", cp.path), String.valueOf(cp.summoningSick)});
            if (cp.attacking != pp.attacking) delta.add(new String[]{String.format("%s.attacking", cp.path), String.valueOf(cp.attacking)});
            if (cp.blocking != pp.blocking) delta.add(new String[]{String.format("%s.blocking", cp.path), String.valueOf(cp.blocking)});
            if (cp.faceDown != pp.faceDown) delta.add(new String[]{String.format("%s.face_down", cp.path), String.valueOf(cp.faceDown)});
            if (cp.phasedOut != pp.phasedOut) delta.add(new String[]{String.format("%s.phased_out", cp.path), String.valueOf(cp.phasedOut)});
            if (!cp.controller.equals(pp.controller)) delta.add(new String[]{String.format("%s.controller", cp.path), cp.controller});

            Set<String> allCounters = new LinkedHashSet<>(cp.counters.keySet());
            allCounters.addAll(pp.counters.keySet());
            for (String counter : allCounters) {
                int cv = cp.counters.getOrDefault(counter, 0);
                int pv = pp.counters.getOrDefault(counter, 0);
                if (cv != pv) delta.add(new String[]{String.format("%s.counters.%s", cp.path, counter), String.valueOf(cv)});
            }
        }

        return delta;
    }

    private static String resolveCardName(Game game, UUID objectId) {
        if (objectId == null) return "Unknown";
        Permanent perm = game.getPermanent(objectId);
        if (perm != null) return perm.getName();
        Card card = game.getCard(objectId);
        if (card != null) return card.getName();
        StackObject stackObj = game.getStack().getStackObject(objectId);
        if (stackObj != null) return stackObj.getName();
        Player player = game.getPlayer(objectId);
        if (player != null) return player.getName();
        return "unknown";
    }

    private static String formatCardInstance(String name, int instance) {
        if (instance > 0) {
            return String.format("\"%s\":%d", name, instance);
        }
        return String.format("\"%s\"", name);
    }

    private static Map<Character, Integer> getManaColorCounts(Mana mana) {
        Map<Character, Integer> counts = new LinkedHashMap<>();
        if (mana.getWhite() > 0) counts.put('W', mana.getWhite());
        if (mana.getBlue() > 0) counts.put('U', mana.getBlue());
        if (mana.getBlack() > 0) counts.put('B', mana.getBlack());
        if (mana.getRed() > 0) counts.put('R', mana.getRed());
        if (mana.getGreen() > 0) counts.put('G', mana.getGreen());
        if (mana.getColorless() > 0) counts.put('C', mana.getColorless());
        return counts;
    }

    private static String formatManaEntry(char symbol, int count) {
        return count == 1 ? String.valueOf(symbol) : String.format("%c:%d", symbol, count);
    }

    private static String convertManaPaymentToArgs(Mana mana) {
        if (mana == null || mana.count() == 0) return "";
        Map<Character, Integer> counts = getManaColorCounts(mana);
        if (counts.isEmpty()) return "";
        StringJoiner sj = new StringJoiner(" ");
        for (Map.Entry<Character, Integer> entry : counts.entrySet()) {
            if (entry.getKey() == 'C') {
                sj.add(String.format("{%d}", entry.getValue()));
            } else {
                sj.add(String.format("{%s}", formatManaEntry(entry.getKey(), entry.getValue())));
            }
        }
        return sj.toString();
    }

    private static String mapPhaseStep(PhaseStep step) {
        if (step == null) {
            return "pregame";
        }
        switch (step) {
            case UNTAP:              return "untap";
            case UPKEEP:             return "upkeep";
            case DRAW:               return "draw";
            case PRECOMBAT_MAIN:     return "main1";
            case BEGIN_COMBAT:       return "begin_combat";
            case DECLARE_ATTACKERS:  return "declare_attackers";
            case DECLARE_BLOCKERS:   return "declare_blockers";
            case FIRST_COMBAT_DAMAGE: return "first_combat_damage";
            case COMBAT_DAMAGE:      return "combat_damage";
            case END_COMBAT:         return "end_combat";
            case POSTCOMBAT_MAIN:    return "main2";
            case END_TURN:           return "end_turn";
            case CLEANUP:            return "cleanup";
            default:                 return step.name().toLowerCase();
        }
    }

    /* ---------------------------------------------------------------------
     * 6. Public Instance Methods
     * ------------------------------------------------------------------- */

    @Override
    public String getServiceCode() {
        return SERVICE_CODE;
    }

    @Override
    public String getInitInfo() {
        if (REPLAY_FILE != null) {
            return String.format("emit .mtg replay to %s", Paths.get(REPLAY_FILE).toAbsolutePath());
        }
        return "emit .mtg replay to stdout";
    }

    @Override
    public void onGameStart(Game game) {
        this.currentTurn = 0;
        this.currentStepKey = null;

        this.pendingEvents.clear();
        this.lastSnapshot = null;
        this.cardInstanceMap.clear();
        this.battlefieldInstanceCount.clear();

        write("game {");
        String deckFormat = System.getProperty("xmage.staxReplayWriter.deckFormat", "Modern");
        write(String.format("  format = %s", deckFormat));
        String staxSeed = System.getProperty("stax.seed");
        if (staxSeed != null) {
            String seedHex = String.format("0x%08x", Long.decode(staxSeed));
            write(String.format("  seed = %s", seedHex));
        }
        write("}");
        write("");

        write("players {");
        for (Player player : game.getPlayers().values()) {
            write(String.format("  %s {", player.getName()));

            /* Group all copies of a card, preserving first-seen order. */
            List<Card> cards = new ArrayList<>(player.getLibrary().getCards(game));
            LinkedHashMap<String, Integer> deckCounts = new LinkedHashMap<>();
            for (Card card : cards) {
                String line = String.format("%s (%s) %s", card.getName(), card.getExpansionSetCode(), card.getCardNumber());
                deckCounts.merge(line, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : deckCounts.entrySet()) {
                write(String.format("    %d %s", e.getValue(), e.getKey()));
            }

            write("  }");
        }
        write("}");
        write("");

        write("begin pregame");
    }

    @Override
    public void onGameReady(Game game) {
        for (StaxEvent se : pendingEvents) {
            writeStaxEvent(se);
        }
        pendingEvents.clear();
        write("end pregame");
    }

    private void write(String line) {
        if (replayFile == null) {
            System.out.println(line);
            return;
        }
        try {
            Files.write(replayFile, (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.error(String.format("StaxReplayWriter: failed to write .mtg: %s", e), e);
        }
    }

    private GameSnapshot captureSnapshot(Game game) {
        GameSnapshot snap = new GameSnapshot();

        for (UUID playerId : game.getPlayerList()) {
            Player player = game.getPlayer(playerId);
            if (player == null) continue;

            PlayerSnapshot ps = new PlayerSnapshot();
            ps.name = player.getName();
            ps.life = player.getLife();
            ps.landsPlayed = player.getLandsPlayed();
            ps.handSize = player.getHand().size();
            ps.librarySize = player.getLibrary().size();
            ps.graveyardSize = player.getGraveyard().size();

            int exileCount = 0;
            for (ExileZone zone : game.getExile().getExileZones()) {
                for (UUID cardId : zone) {
                    Card card = game.getCard(cardId);
                    if (card != null && card.getOwnerId().equals(playerId)) {
                        exileCount++;
                    }
                }
            }
            ps.exileSize = exileCount;

            ManaPool pool = player.getManaPool();
            ps.manaW = pool.getWhite();
            ps.manaU = pool.getBlue();
            ps.manaB = pool.getBlack();
            ps.manaR = pool.getRed();
            ps.manaG = pool.getGreen();
            ps.manaC = pool.getColorless();

            snap.players.put(playerId, ps);
        }

        for (Permanent perm : game.getBattlefield().getAllActivePermanents()) {
            PermanentSnapshot ps = new PermanentSnapshot();
            ps.id = perm.getId();
            Player owner = game.getPlayer(perm.getOwnerId());
            String ownerName = owner != null ? owner.getName() : "?";
            int instance = cardInstance(perm.getId());
            if (instance > 0) {
                ps.path = String.format("%s.\"%s\":%d", ownerName, perm.getName(), instance);
            } else {
                ps.path = String.format("%s.\"%s\"", ownerName, perm.getName());
            }
            ps.tapped = perm.isTapped();
            ps.power = perm.getPower().getValue();
            ps.toughness = perm.getToughness().getValue();
            ps.damage = perm.getDamage();
            ps.summoningSick = perm.hasSummoningSickness();
            ps.attacking = perm.isAttacking();
            ps.blocking = perm.getBlocking() > 0;
            ps.faceDown = perm.isFaceDown(game);
            ps.phasedOut = !perm.isPhasedIn();
            Player controller = game.getPlayer(perm.getControllerId());
            ps.controller = controller != null ? controller.getName() : "?";

            for (Map.Entry<String, mage.counters.Counter> entry : perm.getCounters(game).entrySet()) {
                ps.counters.put(sanitizeCounterName(entry.getKey()), entry.getValue().getCount());
            }

            snap.permanents.put(perm.getId(), ps);
        }

        return snap;
    }

    private int cardInstance(UUID objectId) {
        Integer instance = cardInstanceMap.get(objectId);
        return instance != null ? instance : 0;
    }

    @Override
    public void onTurnBegin(Game game) {
        currentTurn = game.getState().getTurnNum();
        write("");
        write(String.format("begin turn %d", currentTurn));
    }

    @Override
    public void onTurnEnd(Game game) {
        write("end turn");
    }

    @Override
    public void onStepBegin(Game game) {
        if (lastSnapshot == null) {
            lastSnapshot = captureSnapshot(game);
        }
    }

    @Override
    public void onStepEnd(Game game) {
        /* ignore when resolving the stack */
        if (!game.getStack().isEmpty()) {
            return;
        }

        flushStepEvents(game);
    }

    @Override
    public void onGameEnd(Game game) {
        if (replayFile != null) {
            logger.info(String.format("StaxReplay: finished %s", replayFile.toAbsolutePath()));
        }
    }

    private void flushStepEvents(Game game) {
        GameSnapshot current = captureSnapshot(game);
        List<String[]> delta = computeDelta(lastSnapshot, current);

        if (pendingEvents.isEmpty() && delta.isEmpty()) {
            return;
        }

        /* Skip steps that only contain pass actions and no assertions —
         * the driver knows everyone passes if no begin/end block exists. */
        boolean onlyPasses = delta.isEmpty() && pendingEvents.stream()
                .allMatch(se -> se.type == StaxEventType.PASS);
        if (onlyPasses) {
            pendingEvents.clear();
            lastSnapshot = current;
            return;
        }

        String prefix = requirePlayer(game, game.getActivePlayerId()).getName();
        String step = mapPhaseStep(game.getState().getTurnStepType());

        write(String.format("%s begin %s", prefix, step));

        for (StaxEvent se : pendingEvents) {
            writeStaxEvent(se);
        }

        write(String.format("%s end %s", prefix, step));

        for (String[] assertion : delta) {
            write(String.format("! %s == %s", assertion[0], assertion[1]));
        }

        pendingEvents.clear();

        lastSnapshot = current;
    }

    private void writeStaxEvent(StaxEvent se) {
        StringBuilder sb = new StringBuilder();
        if (se.type == StaxEventType.TRIGGER) {
            sb.append(verbFor(se.type));
        } else {
            sb.append(String.format("%s %s", se.player, verbFor(se.type)));
        }
        if (!se.arg.isEmpty()) {
            sb.append(' ').append(se.arg);
        }
        if (!se.manaArgs.isEmpty()) {
            sb.append(' ').append(se.manaArgs);
        }
        if (!se.targets.isEmpty()) {
            sb.append(" -> ").append(se.targets);
        }
        write(sb.toString());
    }

    @Override
    public void onMulliganDecision(Game game, UUID playerId, boolean keep) {
        Player p = requirePlayer(game, playerId);
        pushAction(keep ? StaxEventType.KEEP : StaxEventType.MULLIGAN, p.getName(), "");
    }

    @Override
    public void onOpeningHandAction(Game game, UUID playerId, UUID cardId) {
        // onChooseUse already queued a choose "Yes". Pop it, push leyline,
        // push choose back. Flushed by onGameReady.
        StaxEvent chooseEvent = null;
        if (!pendingEvents.isEmpty() && pendingEvents.peekLast().type == StaxEventType.CHOOSE) {
            chooseEvent = pendingEvents.removeLast();
        }
        Player p = requirePlayer(game, playerId);
        String name = resolveCardName(game, cardId);
        pushAction(StaxEventType.LEYLINE, p.getName(), formatCardInstance(name, 0), "", "");
        if (chooseEvent != null) {
            pendingEvents.add(chooseEvent);
        }
    }

    @Override
    public void onMulliganPutBack(Game game, UUID playerId, UUID cardId) {
        Player p = requirePlayer(game, playerId);
        String name = resolveCardName(game, cardId);
        String arg = formatCardInstance(name, 0);
        write(String.format("%s put_bottom %s", p.getName(), arg));
    }

    @Override
    public void onPlayerPass(Game game, UUID playerId) {
        Player p = requirePlayer(game, playerId);
        pushAction(StaxEventType.PASS, p.getName(), "");
    }

    @Override
    public void onGameEvent(Game game, GameEvent event) {
        switch (event.getType()) {
            case LAND_PLAYED:        handleLandPlayed(game, event); break;
            case SPELL_CAST:         handleSpellCast(game, event); break;
            case MANA_ADDED:         handleManaAdded(game, event); break;
            case ATTACKER_DECLARED:  handleAttackerDeclared(game, event); break;
            case BLOCKER_DECLARED:   handleBlockerDeclared(game, event); break;
            case ACTIVATED_ABILITY:  handleActivatedAbility(game, event); break;
            case TRIGGERED_ABILITY:  handleTriggeredAbility(game, event); break;
            case DREW_CARD:          handleDrewCard(game, event); break;
            case CREATED_TOKEN:      handleCreatedToken(game, event); break;
            case ZONE_CHANGE:        handleZoneChange(game, event); break;
            case MILLED_CARD:        handleMilledCard(game, event); break;
            default: break;
        }
    }

    private void handleLandPlayed(Game game, GameEvent event) {
        String player = requirePlayer(game, event.getPlayerId()).getName();
        String card = formatCardInstance(resolveCardName(game, event.getTargetId()), 0);
        pushAction(StaxEventType.PLAY_LAND, player, card);
    }

    private void handleSpellCast(Game game, GameEvent event) {
        // Effect-driven casts (e.g. Wild Evocation) are not player decisions;
        // suppress the cast entry but emit choose entries for any targets so the
        // replay driver can answer ChooseTarget requests during the cast.
        if (event.getApprovingObject() != null) {
            StackObject stackObj = game.getStack().getStackObject(event.getTargetId());
            if (stackObj != null) {
                String player = requirePlayer(game, event.getPlayerId()).getName();
                emitTargetChoices(game, stackObj, player);
            }
            return;
        }

        String player = requirePlayer(game, event.getPlayerId()).getName();
        StackObject stackObj = requireStackObject(game, event.getTargetId());

        String name;
        String manaArgs = "";
        String targets = "";

        if (stackObj instanceof Spell) {
            Spell spell = (Spell) stackObj;
            name = spell.getName();
            Mana usedMana = spell.getSpellAbility().getManaCostsToPay().getUsedManaToPay();
            manaArgs = convertManaPaymentToArgs(usedMana);
            targets = extractTargets(game, stackObj);
        } else {
            name = resolveCardName(game, event.getTargetId());
        }

        pushAction(StaxEventType.CAST_SPELL, player,
                formatCardInstance(name, 0), manaArgs, targets);
        pendingEvents.addAll(deferredPaymentChoices);
        deferredPaymentChoices.clear();
    }

    private void handleManaAdded(Game game, GameEvent event) {
        if (!(event instanceof ManaEvent)) {
            throw new IllegalStateException("MANA_ADDED event is not a ManaEvent");
        }
        Mana mana = ((ManaEvent) event).getMana();
        if (mana == null || mana.count() == 0) {
            return;
        }

        String player = requirePlayer(game, event.getPlayerId()).getName();
        Permanent source = game.getPermanent(event.getSourceId());
        if (source == null) {
            return;
        }
        String sourceRef = formatCardInstance(source.getName(), cardInstance(source.getId()));

        /* Emit {IDX:N} when multiple mana abilities exist */
        String manaArg = "";
        int manaAbilityCount = 0;
        for (Ability ability : source.getAbilities(game)) {
            if (ability instanceof ManaAbility) {
                manaAbilityCount++;
            }
        }
        if (manaAbilityCount > 1) {
            int idx = findManaAbilityIndex(source, mana, game);
            if (idx > 0) {
                manaArg = String.format("{IDX:%d}", idx);
            }
        }

        pushAction(StaxEventType.TAP_MANA, player, sourceRef, manaArg, "");
        pendingEvents.addAll(deferredPaymentChoices);
        deferredPaymentChoices.clear();
    }

    private int findManaAbilityIndex(Permanent source, Mana producedMana, Game game) {
        int idx = 0;
        for (Ability ability : source.getAbilities(game)) {
            if (ability instanceof ManaAbility) {
                /* Check if this ability could have produced the given mana */
                for (Effect effect : ability.getEffects()) {
                    if (effect instanceof AddManaOfAnyColorEffect) {
                        return idx;
                    }
                    if (effect instanceof mage.abilities.effects.mana.BasicManaEffect) {
                        Mana template = ((mage.abilities.effects.mana.BasicManaEffect) effect).getManaTemplate();
                        if (manaMatchesTemplate(producedMana, template)) {
                            return idx;
                        }
                    }
                }
                idx++;
            }
        }
        return 0;
    }

    private static boolean manaMatchesTemplate(Mana produced, Mana template) {
        if (template.getWhite() > 0 && produced.getWhite() > 0) return true;
        if (template.getBlue() > 0 && produced.getBlue() > 0) return true;
        if (template.getBlack() > 0 && produced.getBlack() > 0) return true;
        if (template.getRed() > 0 && produced.getRed() > 0) return true;
        if (template.getGreen() > 0 && produced.getGreen() > 0) return true;
        if (template.getColorless() > 0 && produced.getColorless() > 0) return true;
        return false;
    }

    private void handleAttackerDeclared(Game game, GameEvent event) {
        String player = requirePlayer(game, event.getPlayerId()).getName();
        String attackerRef = formatCardInstance(resolveCardName(game, event.getSourceId()), cardInstance(event.getSourceId()));
        Player defender = game.getPlayer(event.getTargetId());
        String defenderName = defender != null
                ? defender.getName()
                : formatCardInstance(resolveCardName(game, event.getTargetId()), cardInstance(event.getTargetId()));
        pushAction(StaxEventType.DECLARE_ATTACKER, player, attackerRef, "", defenderName);
    }

    private void handleBlockerDeclared(Game game, GameEvent event) {
        String player = requirePlayer(game, event.getPlayerId()).getName();
        String blockerRef = formatCardInstance(resolveCardName(game, event.getSourceId()), cardInstance(event.getSourceId()));
        String attackerRef = formatCardInstance(resolveCardName(game, event.getTargetId()), cardInstance(event.getTargetId()));
        pushAction(StaxEventType.DECLARE_BLOCKER, player, blockerRef, "", attackerRef);
    }


    private void handleActivatedAbility(Game game, GameEvent event) {
        String player = requirePlayer(game, event.getPlayerId()).getName();
        String sourceRef = formatCardInstance(resolveCardName(game, event.getSourceId()), cardInstance(event.getSourceId()));
        String manaArgs = "";

        StackObject stackObj = requireStackObject(game, event.getTargetId());
        String targets = extractTargets(game, stackObj);

        Map<String, Object> tags = stackObj.getStackAbility().getCostsTagMap();
        if (tags != null && tags.containsKey("X")) {
            Object xVal = tags.get("X");
            if (xVal instanceof Number) {
                manaArgs = String.format("{X:%d}", ((Number) xVal).intValue());
            }
        }

        int abilityIndex = findAbilityIndex(game, event.getSourceId(), stackObj);
        if (abilityIndex > 0) {
            manaArgs = (manaArgs.isEmpty() ? "" : manaArgs + " ") + String.format("{IDX:%d}", abilityIndex);
        }

        pushAction(StaxEventType.ACTIVATE_ABILITY, player, sourceRef, manaArgs, targets);
        pendingEvents.addAll(deferredPaymentChoices);
        deferredPaymentChoices.clear();
    }

    private int findAbilityIndex(Game game, UUID sourceId, StackObject stackObj) {
        Permanent perm = game.getPermanent(sourceId);
        if (perm == null) {
            return 0;
        }
        UUID activatedId = stackObj.getStackAbility().getOriginalId();
        int idx = 0;
        for (Ability ability : perm.getAbilities(game)) {
            if (ability instanceof ActivatedAbility
                    && !(ability instanceof SpellAbility)) {
                if (ability.getAbilityType() == AbilityType.ACTIVATED_MANA) {
                    continue;
                }
                if (ability.getOriginalId().equals(activatedId)) {
                    return idx;
                }
                idx++;
            }
        }
        return 0;
    }

    private void handleDrewCard(Game game, GameEvent event) {
        Player p = requirePlayer(game, event.getPlayerId());
        String arg = formatCardInstance(resolveCardName(game, event.getTargetId()), 0);
        StaxEventType type = p.isDrawsFromBottom() ? StaxEventType.DRAW_BOTTOM : StaxEventType.DRAW;
        pushAction(type, p.getName(), arg);
    }

    private void handleCreatedToken(Game game, GameEvent event) {
        /* Instance assignment is now handled by ZONE_CHANGE. */
    }

    private void handleMilledCard(Game game, GameEvent event) {
        String name = resolveCardName(game, event.getTargetId());
        Player owner = requirePlayer(game, event.getPlayerId());
        String arg = formatCardInstance(name, 0);
        pushAction(StaxEventType.MILL, owner.getName(), arg);
    }

    private void handleZoneChange(Game game, GameEvent event) {
        ZoneChangeEvent zce = (ZoneChangeEvent) event;

        if (zce.getFromZone() == Zone.BATTLEFIELD) {
            cardInstanceMap.remove(event.getTargetId());
        }

        if (zce.getToZone() == Zone.BATTLEFIELD) {
            Permanent perm = zce.getTarget();

            if (perm != null) {
                int count = battlefieldInstanceCount.merge(perm.getName(), 1, Integer::sum);
                cardInstanceMap.put(perm.getId(), count);
            }
        }
    }

    private void pushAction(StaxEventType type, String player, String arg,
                             String manaArgs, String targets) {
        StaxEvent se = new StaxEvent();
        se.type = type;
        se.player = player;
        se.arg = arg;
        se.manaArgs = manaArgs != null ? manaArgs : "";
        se.targets = targets != null ? targets : "";
        pendingEvents.add(se);
    }

    private void pushAction(StaxEventType type, String player, String arg) {
        pushAction(type, player, arg, "", "");
    }

    // XMage's AI (ComputerPlayer7) picks actions by simulating future game states,
    // then replays the chosen action on the real game.  Targets selected during
    // simulation are baked into the copied ability object as "pre-filled" targets.
    // When the real game activates the ability, Targets.makeChoice() sees
    // isChoiceSelected()==true and skips the normal Player.choose() call -- so our
    // data-collector callback fires during cost payment, BEFORE the
    // ACTIVATED_ABILITY / SPELL_CAST event.  We stash those choose entries in
    // deferredPaymentChoices and drain them after the cast/activate/tap handler so
    // they appear in the correct order in the replay file.
    private void pushChoose(String player, String ref, mage.constants.ChooseKind kind) {
        if (kind == mage.constants.ChooseKind.PAYMENT) {
            StaxEvent se = new StaxEvent();
            se.type = StaxEventType.CHOOSE;
            se.player = player;
            se.arg = ref;
            se.manaArgs = "";
            se.targets = "";
            deferredPaymentChoices.add(se);
        } else {
            pushAction(StaxEventType.CHOOSE, player, ref);
        }
    }

    @Override
    public void onChooseUse(Game game, Player player, boolean choice) {
        pushAction(StaxEventType.CHOOSE, player.getName(),
            String.format("\"%s\"", choice ? "Yes" : "No"));
    }

    @Override
    public void onChooseRandom(Game game, Player player, Card card, mage.constants.ChooseContext context) {
        StaxEventType eventType = context == mage.constants.ChooseContext.DISCARD
            ? StaxEventType.DISCARD : StaxEventType.CHOOSE;
        pushAction(eventType, player.getName(), String.format("\"%s\"", card.getName()));
    }

    @Override
    public void onChoose(Game game, Player player, Target target, mage.constants.ChooseKind kind) {
        if (kind == mage.constants.ChooseKind.TARGET) {
            return;
        }
        boolean isDiscard = target instanceof mage.target.common.TargetDiscard;
        for (UUID targetId : target.getTargets()) {
            Permanent perm = game.getPermanent(targetId);
            if (perm != null) {
                pushChoose(player.getName(), formatCardInstance(perm.getName(), cardInstance(perm.getId())), kind);
            } else {
                Player p = game.getPlayer(targetId);
                if (p != null) {
                    pushChoose(player.getName(), String.format("\"%s\"", p.getName()), kind);
                } else {
                    Card card = game.getCard(targetId);
                    if (card != null) {
                        if (isDiscard) {
                            pushAction(StaxEventType.DISCARD, player.getName(),
                                String.format("\"%s\"", card.getName()));
                        } else {
                            pushChoose(player.getName(), formatCardInstance(card.getName(), cardInstance(card.getId())), kind);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onChoose(Game game, Player player, mage.choices.Choice choice, mage.constants.ChooseKind kind) {
        if (kind == mage.constants.ChooseKind.TARGET) {
            return;
        }
        pushChoose(player.getName(), String.format("\"%s\"", choice.getChoice()), kind);
    }

    @Override
    public void onTestsChoiceUse(Game game, Player player, String source, String usingChoice) {
        // no-op: replaced by onChoose(Choice)
    }

    @Override
    public void onTestsChoiceUse(Game game, Player player, String source, Target target) {
        // no-op: replaced by onChoose(Target)
    }

    @Override
    public void onTestsChoiceUse(Game game, mage.players.Player player, String source, mage.cards.Card card) {
        // no-op: replaced by onChoose(Target)
    }

    @Override
    public void onTopCardMayHaveChanged(Game game, Player player) {
        emitTopCardIfNeeded(game, player);
    }

    private void emitTopCardIfNeeded(Game game, Player player) {
        if (!player.isTopCardRevealed() || player.getLibrary().size() == 0) {
            return;
        }
        Card topCard = player.getLibrary().getFromTop(game);
        if (topCard != null) {
            String arg = formatCardInstance(topCard.getName(), 0);
            pushAction(StaxEventType.TOP, player.getName(), "library." + arg);
        }
    }

    @Override
    public void onCardsLookedAt(Game game, Player player, mage.cards.Cards cards) {
        for (Card card : cards.getCards(game)) {
            Zone zone = game.getState().getZone(card.getId());
            String zoneName;
            if (zone == Zone.LIBRARY) {
                zoneName = "library";
            } else {
                continue;
            }
            String arg = formatCardInstance(card.getName(), 0);
            pushAction(StaxEventType.LOOK, player.getName(), zoneName + "." + arg);
        }
    }

    @Override
    public void onCardsRevealed(Game game, Player player, mage.cards.Cards cards) {
        for (Card card : cards.getCards(game)) {
            Zone zone = game.getState().getZone(card.getId());
            String zoneName;
            if (zone == Zone.LIBRARY) {
                zoneName = "library";
            } else if (zone == Zone.HAND) {
                zoneName = "hand";
            } else {
                continue;
            }
            String arg = formatCardInstance(card.getName(), 0);
            pushAction(StaxEventType.REVEAL, player.getName(), zoneName + "." + arg);
        }
    }

    private String extractTargets(Game game, StackObject stackObj) {
        if (stackObj.getStackAbility() == null || stackObj.getStackAbility().getTargets() == null) {
            return "";
        }
        List<String> targetNames = new ArrayList<>();
        for (Target target : stackObj.getStackAbility().getTargets()) {
            for (UUID targetId : target.getTargets()) {
                Player player = game.getPlayer(targetId);
                if (player != null) {
                    targetNames.add(player.getName());
                } else {
                    targetNames.add(formatCardInstance(resolveCardName(game, targetId), cardInstance(targetId)));
                }
            }
        }
        return String.join(" ", targetNames);
    }

    private void emitTargetChoices(Game game, StackObject stackObj, String player) {
        if (stackObj.getStackAbility() == null || stackObj.getStackAbility().getTargets() == null) {
            return;
        }
        for (Target target : stackObj.getStackAbility().getTargets()) {
            for (UUID targetId : target.getTargets()) {
                Player targetPlayer = game.getPlayer(targetId);
                String ref;
                if (targetPlayer != null) {
                    ref = formatCardInstance(targetPlayer.getName(), 0);
                } else {
                    Permanent perm = game.getPermanent(targetId);
                    if (perm != null) {
                        ref = formatCardInstance(perm.getName(), cardInstance(targetId));
                    } else {
                        ref = formatCardInstance(resolveCardName(game, targetId), 0);
                    }
                }
                pushAction(StaxEventType.CHOOSE, player, ref);
            }
        }
    }

    private void handleTriggeredAbility(Game game, GameEvent event) {
        StackObject stackObj = game.getStack().getStackObject(event.getTargetId());
        if (stackObj == null) {
            return;
        }

        UUID sourceId = event.getSourceId();
        String name = resolveCardName(game, sourceId);
        String arg = formatCardInstance(name, cardInstance(sourceId));

        String targets = extractTargets(game, stackObj);

        int abilityIndex = findTriggeredAbilityIndex(game, sourceId, stackObj);
        String manaArgs = "";
        if (abilityIndex > 0) {
            manaArgs = String.format("{IDX:%d}", abilityIndex);
        }

        pushAction(StaxEventType.TRIGGER, "", arg, manaArgs, targets);
    }

    private int findTriggeredAbilityIndex(Game game, UUID sourceId, StackObject stackObj) {
        Permanent perm = game.getPermanent(sourceId);
        if (perm == null) {
            return 0;
        }
        UUID triggeredId = stackObj.getStackAbility().getOriginalId();
        int idx = 0;
        for (Ability ability : perm.getAbilities(game)) {
            if (ability instanceof TriggeredAbility) {
                if (ability.getOriginalId().equals(triggeredId)) {
                    return idx;
                }
                idx++;
            }
        }
        return 0;
    }
}
