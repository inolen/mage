package mage.collectors;

import mage.cards.Card;
import mage.cards.Cards;
import mage.game.Game;
import mage.game.Table;
import mage.game.events.GameEvent;
import mage.players.Player;

import mage.target.Target;

import java.util.UUID;

/**
 * Data collection for better debugging. Can collect server/table/game events and process related data.
 * <p>
 * Supported features:
 * - [x] collect and print game logs in server output, including unit tests
 * - [x] collect and save full games history and decks
 * - [ ] TODO: collect and print performance metrics like ApplyEffects calc time or inform players time (pings)
 * - [ ] TODO: collect and send metrics to third party tools like prometheus + grafana
 * - [x] tests: print used selections (choices, targets, modes, skips) TODO: add yes/no, replacement effect, coins, other choices
 * - [ ] TODO: tests: print additional info like current resolve ability?
 * - [ ] TODO: prepare "attachable" game data for bug reports
 * - [ ] TODO: record game replays data (GameView history)
 * <p>
 * How-to enable or disable:
 * - use java params like -Dxmage.dataCollectors.saveGameHistory=true
 * <p>
 * How-to add new service:
 * - create new class and extends EmptyDataCollector
 * - each service must use unique service code
 * - override only needed events
 * - modify DataCollectorServices.init with new class
 * - make sure it's fast and never raise errors
 *
 * @author JayDi85
 */
public interface DataCollector {

    /**
     * Return unique service code to enable by command line
     */
    String getServiceCode();

    /**
     * Show some hints on service enabled, e.g. root folder path
     */
    String getInitInfo();

    /* ------------------------------------------------------------------
     * Server / table lifecycle
     * ------------------------------------------------------------------ */

    void onServerStart();

    void onTableStart(Table table);

    void onTableEnd(Table table);

    /* ------------------------------------------------------------------
     * Game lifecycle
     * ------------------------------------------------------------------ */

    void onGameStart(Game game);

    void onGameReady(Game game);

    void onGameLog(Game game, String message);

    void onGameEvent(Game game, GameEvent event);

    void onGameEnd(Game game);

    /* ------------------------------------------------------------------
     * Turn / step flow
     * ------------------------------------------------------------------ */

    void onTurnBegin(Game game);

    void onTurnEnd(Game game);

    void onStepBegin(Game game);

    void onStepEnd(Game game);

    /* ------------------------------------------------------------------
     * Player decisions
     * ------------------------------------------------------------------ */

    void onMulliganDecision(Game game, UUID playerId, boolean keep);

    void onMulliganPutBack(Game game, UUID playerId, UUID cardId);

    /**
     * On opening hand action (e.g. leyline placement from hand to battlefield)
     */
    void onOpeningHandAction(Game game, UUID playerId, UUID cardId);

    void onPlayerPass(Game game, UUID playerId);

    void onChooseUse(Game game, Player player, boolean choice);

    void onChooseRandom(Game game, Player player, Card card, mage.constants.ChooseContext context);

    /**
     * On a player revealing cards (e.g. Polymorph library iteration)
     */
    void onCardsRevealed(Game game, Player player, mage.cards.Cards cards);

    void onCardsLookedAt(Game game, Player player, mage.cards.Cards cards);

    void onTopCardMayHaveChanged(Game game, Player player);

    void onChoose(Game game, Player player, Target target, mage.constants.ChooseKind kind);

    void onChoose(Game game, Player player, mage.choices.Choice choice, mage.constants.ChooseKind kind);

    /* ------------------------------------------------------------------
     * Chat
     * ------------------------------------------------------------------ */

    /**
     * @param userName can be null for system messages
     */
    void onChatRoom(UUID roomId, String userName, String message);

    void onChatTourney(UUID tourneyId, String userName, String message);

    void onChatTable(UUID tableId, String userName, String message);

    /**
     * @param gameId chat session don't have full game access, so use onGameStart event to find game's ID before chat
     */
    void onChatGame(UUID gameId, String userName, String message);

    /* ------------------------------------------------------------------
     * Tests only
     * ------------------------------------------------------------------ */

    /**
     * Tests only: on any non-target choice like yes/no, mode, etc
     */
    void onTestsChoiceUse(Game game, Player player, String source, String usingChoice);

    /**
     * Tests only: on a target-based choice (e.g. sacrifice, searchLibrary).
     * Implementations resolve target UUIDs to names internally.
     */
    void onTestsChoiceUse(Game game, Player player, String source, Target target);

    /**
     * Tests only: on a card-based choice (e.g. random card from hand).
     */
    void onTestsChoiceUse(Game game, Player player, String source, mage.cards.Card card);

    /**
     * Tests only: on a target being selected (e.g. by cast/activate or addTarget command).
     */
    void onTestsTargetUse(Game game, Player player, String usingTarget, String reason);

    /**
     * Tests only: on push object to stack (calls before activate and make any choice/announce)
     */
    void onTestsStackPush(Game game);

    /**
     * Tests only: on stack object resolve (calls before starting resolve)
     */
    void onTestsStackResolve(Game game);
}
