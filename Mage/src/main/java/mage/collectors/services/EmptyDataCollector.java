package mage.collectors.services;

import mage.cards.Card;
import mage.collectors.DataCollector;
import mage.game.Game;
import mage.game.Table;
import mage.game.events.GameEvent;
import mage.players.Player;
import mage.target.Target;

import java.util.UUID;

/**
 * Base implementation of Data Collector, do nothing. Use it to implement own or simple collectors, e.g. chats only collectors
 *
 * @author JayDi85
 */
public abstract class EmptyDataCollector implements DataCollector {

    @Override
    public String getInitInfo() {
        return "";
    }

    /* ------------------------------------------------------------------
     * Server / table lifecycle
     * ------------------------------------------------------------------ */

    @Override
    public void onServerStart() {
        // nothing
    }

    @Override
    public void onTableStart(Table table) {
        // nothing
    }

    @Override
    public void onTableEnd(Table table) {
        // nothing
    }

    /* ------------------------------------------------------------------
     * Game lifecycle
     * ------------------------------------------------------------------ */

    @Override
    public void onGameStart(Game game) {
        // nothing
    }

    @Override
    public void onGameReady(Game game) {
        // nothing
    }

    @Override
    public void onGameLog(Game game, String message) {
        // nothing
    }

    @Override
    public void onGameEvent(Game game, GameEvent event) {
        // nothing
    }

    @Override
    public void onGameEnd(Game game) {
        // nothing
    }

    /* ------------------------------------------------------------------
     * Turn / step flow
     * ------------------------------------------------------------------ */

    @Override
    public void onTurnBegin(Game game) {
        // nothing
    }

    @Override
    public void onTurnEnd(Game game) {
        // nothing
    }

    @Override
    public void onStepBegin(Game game) {
        // nothing
    }

    @Override
    public void onStepEnd(Game game) {
        // nothing
    }

    /* ------------------------------------------------------------------
     * Player decisions
     * ------------------------------------------------------------------ */

    @Override
    public void onMulliganDecision(Game game, UUID playerId, boolean keep) {
        // nothing
    }

    @Override
    public void onMulliganPutBack(Game game, UUID playerId, UUID cardId) {
        // nothing
    }

    @Override
    public void onOpeningHandAction(Game game, UUID playerId, UUID cardId) {
        // nothing
    }

    @Override
    public void onPlayerPass(Game game, UUID playerId) {
        // nothing
    }

    @Override
    public void onDeclareAttacker(Game game, UUID playerId, UUID attackerId, UUID defenderId) {
        // nothing
    }

    @Override
    public void onDeclareBlocker(Game game, UUID playerId, UUID blockerId, UUID attackerId) {
        // nothing
    }

    @Override
    public void onChooseUse(Game game, Player player, boolean choice) {
        // nothing
    }

    @Override
    public void onChooseRandom(Game game, Player player, Card card, mage.constants.ChooseContext context) {
        // nothing
    }

    @Override
    public void onBeginActivateAbility(Game game, Player player, mage.abilities.ActivatedAbility ability) {
        // nothing
    }

    @Override
    public void onEndActivateAbility(Game game, Player player, boolean success) {
        // nothing
    }

    @Override
    public void onBeginManaAbility(Game game, Player player, mage.abilities.mana.ActivatedManaAbilityImpl ability) {
        // nothing
    }

    @Override
    public void onEndManaAbility(Game game, Player player, boolean success) {
        // nothing
    }

    @Override
    public void onBeginTriggeredAbility(Game game, Player player, mage.abilities.TriggeredAbility ability) {
        // nothing
    }

    @Override
    public void onBeginLandPlay(Game game, Player player) {
        // nothing
    }

    @Override
    public void onBeginCastSpell(Game game, Player player, mage.abilities.SpellAbility ability, mage.ApprovingObject approvingObject) {
        // nothing
    }

    @Override
    public void onEndCastSpell(Game game, Player player, boolean success) {
        // nothing
    }

    @Override
    public void onCardsRevealed(Game game, Player player, mage.cards.Cards cards) {
        // nothing
    }

    @Override
    public void onCardsLookedAt(Game game, Player player, mage.cards.Cards cards) {
        // nothing
    }

    @Override
    public void onTopCardMayHaveChanged(Game game, Player player) {
        // nothing
    }

    @Override
    public void onScry(Game game, Player player, java.util.List<String> cardNames) {
        // nothing
    }

    @Override
    public void onScryPutBottom(Game game, Player player, java.util.List<String> cardNames) {
        // nothing
    }

    @Override
    public void onScryPutTop(Game game, Player player, java.util.List<String> cardNames) {
        // nothing
    }

    @Override
    public void onChoose(Game game, Player player, Target target, mage.constants.ChooseKind kind) {
        // nothing
    }

    @Override
    public void onChoose(Game game, Player player, mage.choices.Choice choice, mage.constants.ChooseKind kind) {
        // nothing
    }

    @Override
    public void onMultiChoose(Game game, Player player, java.util.List<mage.choices.Choice> choices, mage.constants.ChooseKind kind) {
        // nothing
    }

    /* ------------------------------------------------------------------
     * Chat
     * ------------------------------------------------------------------ */

    @Override
    public void onChatRoom(UUID roomId, String userName, String message) {
        // nothing
    }

    @Override
    public void onChatTourney(UUID tourneyId, String userName, String message) {
        // nothing
    }

    @Override
    public void onChatTable(UUID tableId, String userName, String message) {
        // nothing
    }

    @Override
    public void onChatGame(UUID gameId, String userName, String message) {
        // nothing
    }

    /* ------------------------------------------------------------------
     * Tests only
     * ------------------------------------------------------------------ */

    @Override
    public void onTestsChoiceUse(Game game, Player player, String source, String usingChoice) {
        // nothing
    }

    @Override
    public void onTestsChoiceUse(Game game, Player player, String source, Target target) {
        // nothing
    }

    @Override
    public void onTestsChoiceUse(Game game, Player player, String source, mage.cards.Card card) {
        // nothing
    }

    @Override
    public void onTestsTargetUse(Game game, Player player, String usingTarget, String reason) {
        // nothing
    }

    @Override
    public void onTestsStackPush(Game game) {
        // nothing
    }

    @Override
    public void onTestsStackResolve(Game game) {
        // nothing
    }
}
